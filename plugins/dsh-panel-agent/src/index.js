/**
 * Cordis entry for the panel bridge.
 *
 * The plugin is deliberately thin: it tells the model **where it is**, and it
 * gives it three tools that reach the panel with a bearer grant. It decides no
 * policy — the panel's `AgentScopes` does, in the root process, where an agent
 * cannot edit the rule that bounds it.
 *
 * Two absences shape the code:
 *
 * - **No credential is stored here.** The grant arrives from the operator (the
 *   panel's settings page mints it) and lives in memory for the life of this
 *   mount. Restarting the instance therefore drops it, which is the intended
 *   behaviour: authority granted to an agent does not survive the agent.
 * - **Nothing throws at the model.** A missing grant, an unreachable panel and
 *   a refused scope all come back as `{ok:false, error}` values, because a tool
 *   that throws reads to the model as a bug in its own plan rather than as
 *   "the operator has not authorized this yet".
 *
 * @module index
 */

import { defineTool } from '@deepseek-ai/dsh-tools'

import { PanelClient } from './client.js'
import { buildTools } from './tools.js'

/** Stable Cordis plugin name. */
export const name = 'dsh-panel-agent'

/// The two services a mount cannot work without. Everything else is optional
/// and read defensively: a missing service must degrade the plugin, never park
/// it (see `cordis.patch.yml`).
export const inject = ['tools', 'systemPrompt']

/** Where the panel lives, when the deployment does not say. */
const DEFAULT_PANEL_URL = 'https://127.0.0.1:3080'

/**
 * The grant this mount holds.
 *
 * One mutable cell, read by every tool call, so revoking means calling
 * {@link GrantHolder#clear} and nothing else. It starts empty: a plugin that
 * invented a token would be minting authority the operator never approved.
 */
class GrantHolder {
  constructor() {
    this.token = null
    this.expiresAt = 0
  }

  /** @returns {string | null} the grant while it is still alive */
  get() {
    return this.token !== null && Date.now() < this.expiresAt ? this.token : null
  }

  /**
   * @param {string} token
   * @param {number} [expiresAt] epoch millis; omitted means "until the panel
   *        refuses it", which is what a pre-minted token in the environment is
   */
  set(token, expiresAt = Number.POSITIVE_INFINITY) {
    this.token = token
    this.expiresAt = expiresAt
  }

  clear() {
    this.token = null
    this.expiresAt = 0
  }
}

/**
 * Mounts the bridge.
 *
 * @param {object} ctx    the plugin context
 * @param {object} [config] the row config
 */
export function apply(ctx, config = {}) {
  const panelUrl = config.panelUrl
    || process.env.HDSL_PANEL_URL
    || DEFAULT_PANEL_URL
  const grant = new GrantHolder()
  // The smoke-test path: the operator (or a test harness) hands the grant over
  // in the environment. There is no password here — only the token the panel
  // minted after the password was proved.
  const fromEnvironment = process.env.HDSL_PANEL_TOKEN
  if (fromEnvironment) {
    grant.set(fromEnvironment)
  }

  // The lesser secret: an operator may hand the agent its own password instead
  // of the developer one. It buys a capped, revocable grant and nothing else —
  // it cannot elevate a browser session, and the panel refuses it outright at
  // the guard levels where a session already reaches every route.
  const agentPassword = config.agentPassword || process.env.HDSL_PANEL_AGENT_PASSWORD
  const agentLabel = config.label || process.env.HDSL_PANEL_LABEL || 'dsh-panel-agent'
  const agentScopes = config.scopes
  const agentInstance = config.instanceId || process.env.HDSL_PANEL_INSTANCE
  let minting = null

  /**
   * Mints a grant from the agent password, at most one in flight.
   *
   * @returns {Promise<string | null>}
   */
  async function mint() {
    if (!agentPassword) return null
    if (minting) return minting
    minting = (async () => {
      try {
        const response = await fetch(`${panelUrl}/api/developer/agent-auth`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            password: agentPassword,
            label: agentLabel,
            scopes: agentScopes,
            instanceId: agentInstance,
          }),
        })
        if (!response.ok) {
          // 409 means "this panel's guard level does not use an agent password",
          // 401 "wrong password", 403 "the cap is narrower than asked". None of
          // them is worth retrying inside a turn.
          return null
        }
        const body = await response.json()
        grant.set(body.token, body.expiresAt ? Date.parse(body.expiresAt) : undefined)
        return grant.get()
      } catch {
        return null
      } finally {
        minting = null
      }
    })()
    return minting
  }

  const panel = new PanelClient({ baseUrl: panelUrl, token: () => grant.get() ?? mint() })

  ctx.systemPrompt?.context({
    name: 'panel:instance',
    order: ctx.systemPrompt.getContextOrder?.('HARNESS_SOURCE') ?? 10000,
    interpolate: false,
    text: () => `Current HDSL-web panel: ${JSON.stringify(panelUrl)}. `
      + `Panel grant: ${panel.authorized ? 'present' : 'absent'}.`,
  })

  ctx.systemPrompt?.section({
    name: 'panel:agent',
    order: ctx.systemPrompt.getSectionOrder?.('HARNESS_SOURCE') ?? 10000,
    interpolate: false,
    text: 'You are running inside an HDSL-web panel, which owns this instance and the '
      + 'other services on the machine: Minecraft servers, other agent instances, and the '
      + 'panel\'s own settings. When the user mentions "the panel", "the server list" or '
      + '"my instances", read them through panel_get rather than by inspecting processes '
      + 'or starting your own — a process you start yourself is invisible to the panel, '
      + 'and makes the panel\'s own state report something that is not true. '
      + 'panel_mc and panel_create_mc exist for the same reason. If a tool answers '
      + 'panel_not_authorized, tell the user to mint a grant in the panel; do not work '
      + 'around it with shell commands.',
  })

  if (!ctx.tools || typeof ctx.tools.register !== 'function') {
    // A harness without a tool registry still gets the prompt section — that is
    // the part that keeps the model from starting its own processes.
    return
  }
  for (const tool of buildTools(panel, defineTool)) {
    ctx.tools.register(tool)
  }

  // A credential change in the panel is reported as a 401 on the next call;
  // clearing here is what the operator's revoke button means locally.
  ctx.effect?.(() => () => grant.clear())
}
