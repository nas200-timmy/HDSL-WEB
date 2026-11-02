/**
 * The panel's HTTP surface, as this plugin sees it: one bearer token, one base
 * URL, and a result shape that never throws into the tool loop.
 *
 * Everything that decides **whether** a call is allowed lives in the panel
 * (`AuthFilter` + `AgentScopes`); everything that decides **what** the model can
 * ask for lives in `tools.js` as a fixed schema. This module is deliberately
 * the only place that knows how to speak HTTP, so the token never escapes a
 * `fetch` call and never reaches a tool result.
 *
 * @module client
 */

/** A refusal or a failure, already shaped for the model. */
export class PanelRefused extends Error {
  /**
   * @param {number} status the HTTP status
   * @param {string} body   the panel's JSON text
   */
  constructor(status, body) {
    super(`panel answered ${status}: ${body}`)
    this.status = status
    this.body = body
  }
}

/**
 * Reads a JSON answer, or a truncated text one for non-JSON replies.
 *
 * @param {Response} response the fetch response
 * @returns {Promise<unknown>}
 */
async function decode(response) {
  const text = await response.text()
  try {
    return JSON.parse(text)
  } catch {
    return { text: text.slice(0, 2000) }
  }
}

/** The panel, addressed with the grant this plugin holds. */
export class PanelClient {
  /**
   * @param {object} options
   * @param {string} options.baseUrl  panel origin, e.g. `https://127.0.0.1:3080`
   * @param {() => string | null | Promise<string | null>} options.token reads
   *        the current grant, minting one when the caller holds a lesser
   *        secret (the agent password) and none is cached yet
   */
  constructor({ baseUrl, token }) {
    this.baseUrl = baseUrl.replace(/\/+$/, '')
    this.token = token
  }

  /** @returns {boolean} whether a grant is present right now, without minting */
  get authorized() {
    return Boolean(this.peek())
  }

  /** @returns {string | null} the cached grant, never a promise */
  peek() {
    if (typeof this.token !== 'function') {
      return this.token ?? null
    }
    const value = this.token()
    return typeof value === 'string' ? value : null
  }

  /**
   * One request. Resolves to the panel's JSON on success and to a structured
   * refusal otherwise — never throws, so a tool always returns something the
   * model can act on.
   *
   * @param {string} method
   * @param {string} path   must start with `/api/`
   * @param {object} [body]
   * @returns {Promise<{ok: true, value: unknown} | {ok: false, error: string, detail?: string}>}
   */
  async call(method, path, body) {
    if (!path.startsWith('/api/')) {
      return { ok: false, error: 'bad_path', detail: 'path must start with /api/' }
    }
    const grant = await (typeof this.token === 'function' ? this.token() : this.token)
    if (!grant) {
      return {
        ok: false,
        error: 'panel_not_authorized',
        detail:
          'no panel grant: the operator must mint one in the panel (设置 → 开发者 → agent 授权) '
          + 'or configure an agent password for this plugin. Do not work around this with '
          + "shell commands: starting a second server yourself corrupts the panel's state.",
      }
    }
    /** @type {RequestInit} */
    const init = {
      method,
      headers: { Authorization: `Bearer ${grant}` },
    }
    if (method !== 'GET' && method !== 'HEAD') {
      // The panel refuses chunked bodies on JSON routes (`411`), so every
      // request that has a body carries an explicit length — including the
      // empty ones. `fetch` rejects a body on GET outright, hence the guard.
      init.body = body === undefined ? '' : JSON.stringify(body)
      if (body !== undefined) {
        init.headers['Content-Type'] = 'application/json'
      }
    }
    let response
    try {
      response = await fetch(this.baseUrl + path, init)
    } catch (error) {
      return { ok: false, error: 'panel_unreachable', detail: String(error?.message ?? error) }
    }
    if (!response.ok) {
      const detail = await decode(response)
      return {
        ok: false,
        error: statusName(response.status, detail),
        detail: typeof detail === 'object' && detail && 'error' in detail ? String(detail.error) : undefined,
        status: response.status,
      }
    }
    return { ok: true, value: await decode(response) }
  }
}

/**
 * Turns a status into the one word a model can act on.
 *
 * @param {number} status
 * @param {unknown} detail
 * @returns {string}
 */
function statusName(status, detail) {
  const text = typeof detail === 'object' && detail && 'error' in detail ? String(detail.error) : ''
  if (text.startsWith('self_operation_refused')) return 'self_operation_refused'
  if (text.startsWith('agent_scope_denied')) return 'agent_scope_denied'
  if (status === 401) return 'panel_not_authorized'
  if (status === 403) return 'agent_scope_denied'
  if (status === 409) return 'conflict'
  return `panel_http_${status}`
}
