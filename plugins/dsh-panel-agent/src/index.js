/**
 * Cordis entry for the panel bridge — the **host** half.
 *
 * The plugin is deliberately thin: it tells the model where it is, gives it
 * three tools that reach the panel with a bearer grant, and hosts the one RPC
 * channel the settings page uses to hand that grant over. It decides no policy
 * — the panel's `AgentScopes` does, in the root process, where an agent cannot
 * edit the rule that bounds it.
 *
 * Two absences shape the code:
 *
 * - **No credential is stored on disk.** The grant arrives from the browser
 *   half (which posts the panel's *agent password*, never the developer one)
 *   and lives in memory for the life of this mount. Restarting the instance
 *   drops it, which is the intended behaviour: authority granted to an agent
 *   does not survive the agent.
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

/// What a mount cannot start without. `connectionRpc` is deliberately **not**
/// here: cordis reads `inject` as required services (the object form is intercept
/// config, not a required/optional split), and a TUI composition has no browser
/// to serve. Reading an uninjected service throws, so the channel is registered
/// through `ctx.inject(['connectionRpc'], …)` below, which waits for the service
/// and leaves the tools working when it never arrives.
export const inject = ['tools', 'systemPrompt']

/** Where the panel lives, when nothing has been configured yet. */
const DEFAULT_PANEL_URL = 'https://127.0.0.1:3080'

/** The logical channel the settings page calls. */
const CHANNEL = '/panel-agent'

/**
 * The grant this mount holds.
 *
 * One mutable cell read by every tool call and by the status endpoint, so
 * revoking means calling {@link GrantHolder#clear} and nothing else.
 */
class GrantHolder {
  constructor(defaultUrl) {
    this.token = null
    this.expiresAt = 0
    this.panelUrl = defaultUrl
    this.scopes = []
  }

  /** @returns {string | null} the grant while it is still alive */
  get() {
    return this.token !== null && Date.now() < this.expiresAt ? this.token : null
  }

  /**
   * @param {string} token
   * @param {number} expiresAt epoch millis
   * @param {string} panelUrl
   * @param {string[]} scopes
   */
  set(token, expiresAt, panelUrl, scopes) {
    this.token = token
    this.expiresAt = expiresAt
    this.panelUrl = panelUrl
    this.scopes = scopes ?? []
  }

  clear() {
    this.token = null
    this.expiresAt = 0
  }

  /** @returns {object} what the settings page renders */
  status() {
    return {
      configured: this.get() !== null,
      panelUrl: this.panelUrl,
      scopes: this.scopes,
      expiresAt: this.get() === null ? null : new Date(this.expiresAt).toISOString(),
    }
  }
}

/**
 * Mounts the bridge.
 *
 * @param {object} ctx    the plugin context
 * @param {object} [config] the row config
 */
export function apply(ctx, config = {}) {
  const panelUrl = config.panelUrl || process.env.HDSL_PANEL_URL || DEFAULT_PANEL_URL
  const grant = new GrantHolder(panelUrl)

  // The smoke-test path: an operator (or a test) hands over a grant that was
  // minted elsewhere. There is no password here — only the token.
  const fromEnvironment = process.env.HDSL_PANEL_TOKEN
  if (fromEnvironment) {
    grant.set(fromEnvironment, Number.POSITIVE_INFINITY, panelUrl, [])
  }

  const panel = new PanelClient({ baseUrl: panelUrl, token: () => grant.get() })

  ctx.systemPrompt?.context({
    name: 'panel:instance',
    order: ctx.systemPrompt.getContextOrder?.('HARNESS_SOURCE') ?? 10000,
    interpolate: false,
    text: () => `Current HDSL-web panel: ${JSON.stringify(grant.panelUrl)}. `
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

  /**
   * Exchanges the panel's **agent password** for a scoped grant.
   *
   * The password is used once, here, and dropped: it is never stored, never
   * logged, and never handed back to the browser.
   *
   * @param {{panelUrl?: string, password?: string, scopes?: string[]}} payload
   * @returns {Promise<object>} Connection's result envelope
   */
  async function mint(payload) {
    const url = String(payload?.panelUrl || panelUrl).replace(/\/+$/, '')
    const password = payload?.password
    if (!url || !password) {
      return { ok: false, error: { code: 'bad_request', message: 'panelUrl and password are required' } }
    }
    let response
    try {
      response = await fetch(`${url}/api/developer/agent-auth`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          password,
          label: config.label || 'dsh-panel-agent',
          instanceId: config.instanceId,
          scopes: payload?.scopes,
        }),
      })
    } catch (error) {
      return { ok: false, error: { code: 'panel_unreachable', message: String(error?.message ?? error) } }
    }
    const body = await response.json().catch(() => ({}))
    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: 'panel_refused',
          message: typeof body?.error === 'string' ? body.error : `panel answered ${response.status}`,
        },
      }
    }
    panel.baseUrl = url
    grant.set(body.token, body.expiresAt ? Date.parse(body.expiresAt) : Date.now() + 60_000,
      url, body.scopes ?? [])
    return { ok: true, value: grant.status() }
  }

  // `ctx.inject` delays the callback until the service exists, and never runs it
  // when the composition has none — the difference between "the settings page is
  // missing" and "the plugin is parked with no tools".
  ctx.inject?.(['connectionRpc'], (rpcCtx) => {
    rpcCtx.effect(() => {
      const disposing = rpcCtx.connectionRpc.handle(CHANNEL, async (endpoint, payload) => {
        switch (endpoint) {
          case 'status':
            return { ok: true, value: grant.status() }
          case 'mint':
            return mint(payload ?? {})
          case 'clear':
            grant.clear()
            return { ok: true, value: grant.status() }
          default:
            return { ok: false, error: { code: 'not_found', message: `no endpoint ${endpoint}` } }
        }
      })
      return () => {
        void disposing.then((dispose) => dispose())
      }
    })
  })

  if (!ctx.tools || typeof ctx.tools.register !== 'function') {
    // A harness without a tool registry still gets the prompt section — that is
    // the part that keeps the model from starting its own processes.
    return
  }
  for (const tool of buildTools(panel, defineTool)) {
    ctx.tools.register(tool)
  }

  ctx.effect?.(() => () => grant.clear())
}
