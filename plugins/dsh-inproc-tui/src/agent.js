/**
 * The agent this terminal drives.
 *
 * Unlike the ACP terminal, this one lives inside the harness process: the bundle
 * patch mounts it over `dsh-base`, so it talks to the agent registry and the
 * session log directly. That is what buys token-level streaming, live reasoning,
 * approval prompts and model switching without a protocol in between — at the
 * price of only working on the 0.2.x harness line, which is the trade this
 * plugin deliberately makes.
 *
 * @module agent
 */

import { randomUUID } from 'node:crypto'

// The harness packages are imported when a session is opened, not when this
// module loads. They are peers: a terminal that never reaches `open()` — the
// `--help` path, a profile whose registry is missing — must still be loadable,
// and the two error messages below are the ones that say so. It is also what
// lets this module be exercised without a harness installed.

/** How long a detach waits for the agent to go idle before giving up on it. */
const SETTLE_TIMEOUT_MS = 2_000

/** How many events one session-index read asks for. */
const SESSION_INDEX_READ = 64
/** Stop looking for the first prompt after this many events. */
const SESSION_INDEX_LIMIT = 2_048
/** How many characters of the first prompt the picker shows. */
const SESSION_TITLE_CHARS = 40
/** Newest artifacts read when building the `/sessions` index. */
const SESSION_INDEX_MAX = 200
/** Read handles opened at once; a large store must not open hundreds in parallel. */
const SESSION_INDEX_CONCURRENCY = 8

/** A model choice as the harness records it. */
export class ChatSession {
  /**
   * @param {object} ctx the Cordis context of the plugin
   * @param {string} cwd the workspace the session is opened in
   */
  constructor(ctx, cwd) {
    this.ctx = ctx
    this.cwd = cwd
    this.agent = null
    this.dispose = null
    this.sessionId = null
    /** The state object the harness assembles prompts from; see {@link open}. */
    this.modelState = null
    /** Set by {@link open}: the harness's own user-message constructor. */
    this.createUserMessage = undefined
  }

  /** The live agent, or `null` before {@link open}. */
  get live() {
    return this.agent
  }

  /**
   * Creates a fresh agent, or resumes one by id.
   *
   * @param {{resume?: string | null}} [options]
   * @returns {Promise<{resumed: boolean}>}
   */
  async open(options = {}) {
    const agents = this.ctx.get('agents')
    const defaultModel = this.ctx.get('agentDefaultModel')
    if (agents === undefined) {
      throw new Error('dsh-inproc-tui: this profile does not mount the agent registry (dsh-base does)')
    }
    if (defaultModel === undefined) {
      throw new Error('dsh-inproc-tui: this harness exposes no agentDefaultModel; the profile has no model route to start')
    }
    const selection = defaultModel.currentSelection()
    const agentOptions = { ...selection }
    // Loaded here rather than at module scope, so this module can be read without
    // a harness: both are peers, and one of them (`dsh-llm`) is only needed once a
    // prompt is actually sent.
    const { installModelSelection } = await import('@deepseek-ai/dsh-agent')
    const { createUserMessage } = await import('@deepseek-ai/dsh-llm')
    this.createUserMessage = createUserMessage
    // `installModelSelection` takes the *state object* the prompt assembly reads
    // (`{current, assembled}`), not just the context: without it the harness's
    // assemble hook dereferences undefined on the first step.
    //
    // The object is kept: `current` is what the harness's assemble hook reads on
    // **every** step, so a later `/model` has to write here — appending the
    // durable event alone only changed what the log said.
    const setup = (agentCtx, agent) => {
      const state = {
        current: agent?.model?.current?.() ?? selection,
        assembled: undefined,
      }
      this.modelState = state
      installModelSelection(agentCtx, state)
    }
    this.modelState = null
    this.resumeFailure = undefined

    let resumed = false
    if (options.resume) {
      try {
        const result = await agents.resume({ resumeSessionId: options.resume, agentOptions, setup })
        this.agent = result.agent
        this.dispose = result.dispose
        resumed = true
      } catch (error) {
        // A typo or a session from another workspace falls back to a new
        // conversation, and the app tells the person what happened.
        this.resumeFailure = error
      }
    }
    if (this.agent === null) {
      const result = await agents.create({
        sessionId: `session-${randomUUID()}`,
        meta: { cwd: this.cwd },
        agentOptions,
        setup,
      })
      this.agent = result.agent
      this.dispose = result.dispose
    }
    this.sessionId = this.agent.session.id
    await this.agent.whenIdle()
    return { resumed }
  }

  /** Whether an agent is open and can take a prompt. */
  get ready() {
    return this.agent !== null
  }

  /** Whether the agent is currently running a turn. */
  get busy() {
    return this.agent !== null && this.agent.status === 'running'
  }

  /**
   * Queues one user prompt.
   * @param {string} text
   */
  send(text) {
    if (this.agent === null) throw new Error('dsh-inproc-tui: no agent is open')
    if (this.createUserMessage === undefined) throw new Error('dsh-inproc-tui: the session was never opened')
    this.agent.followup(this.createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'user' },
    }))
  }

  /**
   * Feeds text to the step that is running, instead of queuing the next turn.
   *
   * This is Kimi Code's `Ctrl-S`: the agent sees the message in the current
   * turn's next step. An idle agent starts a turn, so the caller may use it
   * without checking status.
   *
   * @param {string} text
   */
  steer(text) {
    if (this.agent === null) throw new Error('dsh-inproc-tui: no agent is open')
    if (this.createUserMessage === undefined) throw new Error('dsh-inproc-tui: the session was never opened')
    this.agent.steer(this.createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'user' },
    }))
  }

  /** Interrupts whatever the agent is doing. */
  cancel() {
    this.agent?.cancel({ kind: 'user' })
  }

  /**
   * Waits until the agent is quiet, but never forever.
   *
   * A tool parked on an approval or a question keeps the agent non-idle; a
   * quit that waits for idle without a bound hangs the harness process.
   *
   * @param {number} [timeoutMs]
   */
  async settle(timeoutMs = SETTLE_TIMEOUT_MS) {
    const agent = this.agent
    if (agent === null) return
    let timer = null
    try {
      await Promise.race([
        Promise.resolve(agent.whenIdle()).catch(() => {}),
        // Not unref'd: the wait *is* the work here, and a quit that vanishes
        // into an empty event loop would leave the terminal unrestored.
        new Promise((resolve) => {
          timer = setTimeout(resolve, timeoutMs)
        }),
      ])
    } finally {
      if (timer !== null) clearTimeout(timer)
    }
  }

  /** Persists the session log, when the profile mounts persistence. */
  async flush() {
    if (this.agent === null) return
    try {
      await this.ctx.get('sessions')?.flush(this.agent.session)
    } catch {
      // A flush failure must not keep the terminal alive.
    }
  }

  /** The selection the next step will use, when known. */
  /**
   * The selection the next step will use.
   *
   * Read from the durable `model/selection` events rather than `agent.model`:
   * that accessor is not present on every 0.2.x build, while the event is the
   * projection both the harness and this terminal read anyway.
   *
   * @returns {{provider: string, model: string, reasoningEffort?: string} | null}
   */
  currentModel() {
    const session = this.agent?.session
    if (session === undefined) return null
    try {
      const events = session.snapshotEvents()
      for (let index = events.length - 1; index >= 0; index -= 1) {
        const event = events[index]
        if (event?.type === 'model/selection' && typeof event.data?.provider === 'string' && typeof event.data?.model === 'string') {
          return event.data
        }
      }
    } catch {
      // A session that cannot be read falls through to the options below.
    }
    const options = this.agent?.options
    if (typeof options?.provider === 'string' && typeof options?.model === 'string' && options.provider !== '' && options.model !== '') {
      return {
        provider: options.provider,
        model: options.model,
        ...(options.reasoningEffort === undefined ? {} : { reasoningEffort: options.reasoningEffort }),
      }
    }
    return null
  }

  /**
   * Switches the route and/or effort for the next step.
   * @param {{provider?: string, model?: string, reasoningEffort?: string}} selection
   */
  select(selection) {
    if (this.agent === null) return
    const current = this.currentModel()
    const effort = selection.reasoningEffort ?? current?.reasoningEffort
    const chosen = {
      provider: selection.provider ?? current?.provider ?? '',
      model: selection.model ?? current?.model ?? '',
      ...(effort === undefined ? {} : { reasoningEffort: effort }),
    }
    // What the next step actually assembles with. The harness's assemble hook
    // reads this object's `current` on every step, so writing only the event
    // below left the request on the old route while the UI said otherwise.
    if (this.modelState !== null && this.modelState !== undefined) {
      this.modelState.current = { ...chosen }
    }
    // The same durable event `agent.model.select` writes: a later step reads the
    // newest selection, and the log is what survives a resume.
    this.agent.session.append('model/selection', chosen)
  }

  /**
   * The live agent's session log, oldest first.
   *
   * Used to put a resumed conversation back on screen: the log is the only
   * place the earlier turns exist once the previous agent is gone.
   *
   * @returns {object[]} the events, or an empty list when none can be read
   */
  history() {
    try {
      return this.agent?.session?.snapshotEvents() ?? []
    } catch {
      return []
    }
  }

  /**
   * Every provider the profile mounted, with its catalogue.
   * @returns {Promise<{provider: string, displayName: string, models: {id: string, name: string}[]}[]>}
   */
  async modelCatalog() {
    const llm = this.ctx.get('llm')
    if (llm === undefined) return []
    const providers = llm.listProviders()
    const rows = []
    for (const provider of providers) {
      let models = []
      try {
        models = await llm.listModels(provider.provider ?? provider.id)
      } catch {
        models = []
      }
      rows.push({
        provider: provider.provider ?? provider.id,
        displayName: provider.displayName ?? provider.provider ?? provider.id,
        models: models.map((model) => ({ id: model.id ?? model.model, name: model.name ?? model.id ?? model.model })),
      })
    }
    return rows
  }

  /** Context occupancy, from the token meter when the profile mounts one. */
  usage() {
    const session = this.agent?.session
    if (session === undefined) return { used: 0, size: 0 }
    let used = 0
    try {
      const meter = this.ctx.get('tokenMeter')
      used = meter?.measure(session)?.totalTokens ?? 0
    } catch {
      used = 0
    }
    let size = 0
    try {
      size = session.requestContext()?.contextWindow ?? 0
    } catch {
      size = 0
    }
    return { used, size }
  }

  /**
   * Stored sessions with at least one human prompt, newest first.
   *
   * Persistence lists every artifact it has, including the empty session an
   * accidental `/new`, a failed resume or a killed start leaves behind. Those
   * have no first prompt and are not useful in `/sessions`, so they are read
   * (metadata only, then the first few events) and dropped. The first human
   * message is also the label the picker shows: a session id alone tells nobody
   * which conversation it is.
   *
   * @returns {Promise<{id: string, cwd: string, at: number, title: string}[]>}
   */
  async listSessions() {
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) return []
    let snapshots = []
    try {
      snapshots = await persistence.list()
    } catch {
      return []
    }
    // `list()` promises no order and a store can be large. Work newest-first so
    // the picker's 40 rows are populated from the useful end, and read with a
    // small pool: one read handle per session, but not hundreds at once.
    const ordered = [...snapshots]
      .sort((left, right) => (right.header?.updatedAt ?? right.header?.createdAt ?? 0)
        - (left.header?.updatedAt ?? left.header?.createdAt ?? 0))
      .slice(0, SESSION_INDEX_MAX)
    const rows = []
    let next = 0
    const worker = async () => {
      while (next < ordered.length) {
        const snapshot = ordered[next]
        next += 1
        const id = snapshot.header?.id ?? ''
        if (id === '') continue
        // A zero-event artifact was created but never written; no need to open it.
        if (snapshot.eventCount === 0) continue
        const title = await firstPromptTitle(persistence, id)
        if (title === '') continue
        rows.push({
          id,
          cwd: snapshot.header?.cwd ?? '',
          at: snapshot.header?.updatedAt ?? snapshot.header?.createdAt ?? 0,
          title,
        })
      }
    }
    await Promise.all(Array.from(
      { length: Math.min(SESSION_INDEX_CONCURRENCY, ordered.length) },
      worker,
    ))
    return rows.sort((left, right) => Number(right.at) - Number(left.at))
  }

  /**
   * Resumes a stored session, replacing the live one.
   * @param {string} sessionId
   */
  async resume(sessionId) {
    await this.close()
    return this.open({ resume: sessionId })
  }

  /**
   * Runs the profile's compaction over this agent.
   * @returns {Promise<boolean>} whether a compaction service was available
   */
  async compact() {
    const compaction = this.ctx.get('compaction')
    if (compaction === undefined || this.agent === null) return false
    await compaction.compactNow(this.agent, new AbortController().signal)
    return true
  }

  /**
   * Detaches and forgets the current agent.
   *
   * @param {{settleTimeoutMs?: number}} [options]
   */
  async close(options = {}) {
    await this.settle(options.settleTimeoutMs)
    await this.flush()
    try {
      this.dispose?.()
    } catch {
      // Disposal is best effort; the registry drops the agent either way.
    }
    this.dispose = null
    this.agent = null
    this.sessionId = null
    this.modelState = null
  }
}

/**
 * The first human prompt stored in a session, shortened for a picker label.
 *
 * A read handle is opened and closed per session. Reading stops at the first
 * eligible `user/message`, so a long conversation costs one small slice; the
 * cap keeps a pathological log (thousands of system events before any prompt)
 * from turning `/sessions` into a full scan.
 *
 * @param {object} persistence the session persistence service
 * @param {string} id the stored session id
 * @returns {Promise<string>} the shortened prompt, or '' when there is none
 */
async function firstPromptTitle(persistence, id) {
  let handle = null
  try {
    handle = await persistence.open(id, 'read')
    let offset = 0
    while (offset < SESSION_INDEX_LIMIT) {
      const result = await handle.read(offset, SESSION_INDEX_READ)
      const events = result?.events ?? []
      for (const event of events) {
        if (event?.type !== 'user/message' || event.data?.source?.kind !== 'user') continue
        const text = messageText(event.data)
        const title = shortenPrompt(text)
        if (title !== '') return title
      }
      if (events.length < SESSION_INDEX_READ) break
      offset += events.length
    }
    return ''
  } catch {
    // An unreadable old format or a half-written artifact is not a session the
    // picker can resume; leave it out instead of offering a broken row.
    return ''
  } finally {
    try {
      await handle?.close()
    } catch {
      // Closing a read handle is best effort.
    }
  }
}

/** The text blocks of a stored user message. */
function messageText(message) {
  const content = message?.content
  if (!Array.isArray(content)) return ''
  return content
    .map((block) => (block?.type === 'text' ? String(block.text ?? '') : ''))
    .join('')
}

/** Collapse whitespace and cap a prompt at a readable picker label. */
function shortenPrompt(text) {
  const flat = String(text ?? '')
    .replace(/[\u0000-\u001f\u007f]+/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim()
  if (flat === '') return ''
  const chars = Array.from(flat)
  return chars.length <= SESSION_TITLE_CHARS
    ? flat
    : `${chars.slice(0, SESSION_TITLE_CHARS - 1).join('')}…`
}
