/**
 * The terminal application.
 *
 * This module owns the state a person sees (transcript blocks, composer, status)
 * and the state machine that changes it: keys in, ACP updates in, frames out.
 * Everything about *how* it looks lives in `render.js`, `blocks.js` and
 * `dialogs.js`; everything about *how it talks to the harness* lives in
 * `acp.js`. Keeping the three apart is what lets the UI be tested by feeding it
 * events, and the protocol be tested by replaying frames.
 *
 * @module ui/app
 */

import { randomUUID } from 'node:crypto'

import { AcpClient } from '../acp.js'
import { createI18n, detectLanguage } from '../../../shared/i18n.js'
import { createTheme, detectMode } from '../../../shared/term/theme.js'
import { Screen } from '../../../shared/term/screen.js'
import { createKeyDecoder } from '../../../shared/term/keys.js'
import { Composer } from '../../../shared/ui/composer.js'
import { applyEditKey, isLiveOverlay } from '../../../shared/ui/edit.js'
import { renderFrame, spinnerFrame } from '../../../shared/ui/render.js'
import { formatDuration, shortId } from '../../../shared/ui/blocks.js'
import { VERSION } from '../version.js'

const TICK_MS = 33
const ESC_DELAY_MS = 25
const REVEAL_PER_FRAME = 24
const REVEAL_MAX_FRAMES = 26
/**
 * How long a session switch waits for the turn it cancelled.
 *
 * A cancel is a notification: the harness stops when it gets to it, and the
 * `session/prompt` it is running answers afterwards. A switch waits for that
 * answer, but never forever — a tool parked on an approval keeps the turn alive
 * and the terminal must still be able to move on.
 */
const TURN_SETTLE_MS = 2_000

/** Commands offered by `/` and by the help dialog. */
const COMMANDS = [
  { name: 'help', key: 'cmd.help' },
  { name: 'model', key: 'cmd.model' },
  { name: 'effort', key: 'cmd.effort' },
  { name: 'resume', key: 'cmd.resume' },
  { name: 'new', key: 'cmd.new' },
  { name: 'clear', key: 'cmd.clear' },
  { name: 'quit', key: 'cmd.quit' },
]

/**
 * Everything one terminal session needs.
 */
export class App {
  /**
   * @param {{
   *   env: Record<string, string | undefined>,
   *   stdout: import('node:stream').Writable,
   *   stdin: import('node:stream').Readable,
   *   cwd: string,
   *   harness: {command: string[], source: string, profile: string},
   *   resume: string | null,
   *   turnSettleMs?: number,
   * }} options
   */
  constructor(options) {
    this.env = options.env
    this.stdout = options.stdout
    this.stdin = options.stdin
    this.cwd = options.cwd
    this.harness = options.harness
    this.resume = options.resume
    /** How long a session switch waits for the turn in flight to settle. */
    this.turnSettleMs = options.turnSettleMs ?? TURN_SETTLE_MS

    this.language = detectLanguage(this.env)
    this.i18n = createI18n(this.language)
    this.theme = createTheme({ mode: detectMode(this.env), color: this.env.NO_COLOR === undefined })
    this.screen = new Screen(this.stdout, this.stdin)

    this.blocks = []
    this.transcriptCache = new WeakMap()
    this.composer = new Composer({ maxLines: 8 })
    this.queue = []
    this.scroll = { offset: 0, follow: true }
    this.overlay = null
    this.focus = 'composer'
    this.expandTools = false
    this.status = 'connecting'
    this.activity = { tool: null, startedAt: null }
    this.usage = { used: 0, size: 0 }
    this.model = { current: null, currentLabel: null, options: [] }
    this.effort = { current: null, options: [] }
    this.lastTurnSeconds = null
    this.sessionId = null
    this.client = null
    this.clock = 0
    this.revealing = new Set()
    this.dirty = true
    this.running = false
    this.quitArmedAt = null
    this.pendingPermission = null
    this.permissionQueue = []
    /**
     * Identifies the turn that owns the screen. A switch bumps it, which is what
     * makes a still-running `session/prompt` a no-op when it finally answers.
     */
    this.turnSerial = 0
    /** The `session/prompt` in flight, or `null`; awaited by a switch, never by a cancel. */
    this.pendingTurn = null
    this.startupError = null
    this.exitCode = 0
    this.exitDetail = ''
    this.stopping = false
    this.stopped = false
    this.protocolNoticeAt = 0
    this.transcriptTotal = 0

    this.timer = null
    this.escTimer = null
    this.onInputData = null
    this.onInputEnd = null
    this.exitPromise = new Promise((resolve) => {
      this.resolveExit = resolve
    })
    this.codec = createKeyDecoder((event) => this.onKeyEvent(event))
  }

  // ── lifecycle ───────────────────────────────────────────────────────────

  /** Boots the harness, paints the first frame and starts the clock. */
  async start() {
    this.screen.enter()
    this.screen.resized = () => {
      this.dirty = true
      this.render()
    }
    this.installInput()
    this.pushBanner()
    this.render()

    this.timer = setInterval(() => this.tick(), TICK_MS)

    try {
      this.client = new AcpClient({
        command: [...this.harness.command, '--profile', this.harness.profile],
        cwd: this.cwd,
        env: this.childEnvironment(),
        log: (line) => this.log(line),
      })
      this.client.on('notification', (message) => this.onNotification(message))
      this.client.on('request', (message) => this.onRequest(message))
      this.client.on('exit', (info) => this.onExit(info))
      this.client.on('protocolError', (error) => this.onProtocolError(error))
      this.client.start()

      const handshake = await this.client.initialize({ name: 'dsh-acp-tui', version: VERSION })
      this.pushNotice(this.i18n.t('notice.attached', { version: handshake.protocolVersion ?? 1 }), 'info')
      await this.openSession()
      this.status = 'ready'
    } catch (error) {
      this.startupError = error
      this.status = 'disconnected'
      // A quit that overtook the handshake already owns the exit; reporting the
      // aborted request on top of it would print an error after a clean quit.
      if (this.stopped) return this
      // There is no terminal to be had without a harness: show the failure while
      // the alternate screen is still up, then release the terminal, the stdin
      // handle and the clock so the process can actually exit. `main` catches the
      // rejection and prints the error on the restored screen.
      this.pushNotice(this.failureText(error), 'error')
      this.render()
      this.exitCode = 1
      await this.stop('startup')
      throw error
    }
    this.dirty = true
    this.render()
    return this
  }

  /**
   * Stops the clock, restores the terminal and shuts the child down.
   *
   * Every route out of the terminal (quit, startup failure, signal, the child
   * dying) goes through here, and it runs at most once: a second call must not
   * print the resume hint twice or wait on a child that already went away.
   */
  async stop(reason = 'quit') {
    if (this.stopped) return
    this.stopped = true
    this.stopping = true
    if (this.timer !== null) clearInterval(this.timer)
    this.timer = null
    if (this.escTimer !== null) clearTimeout(this.escTimer)
    this.escTimer = null
    this.overlay = null
    this.permissionQueue = []
    try {
      this.screen.leave()
    } catch {
      // the terminal may already be gone
    }
    // Release the terminal's stdin: a resumed stdin keeps the event loop alive,
    // and the panel reads a live process as "the terminal is still open". After
    // the alternate screen is gone there is nothing left to read.
    if (typeof this.stdin.setRawMode === 'function') {
      try {
        this.stdin.setRawMode(false)
      } catch {
        // the PTY may already be gone
      }
    }
    if (this.onInputData !== null) this.stdin.removeListener('data', this.onInputData)
    if (this.onInputEnd !== null) this.stdin.removeListener('end', this.onInputEnd)
    this.onInputData = null
    this.onInputEnd = null
    this.stdin.pause()
    if (this.client !== null) {
      try {
        await this.client.close()
      } catch {
        // A child that cannot be reaped must not keep the terminal open.
      }
    }
    try {
      if (reason === 'quit' && this.sessionId !== null) {
        this.stdout.write(this.i18n.t('quit.resume', { id: this.sessionId }) + '\n')
      } else if (reason === 'exit') {
        const detail = this.exitDetail === '' ? '' : ` — ${this.exitDetail}`
        this.stdout.write(`dsh-acp-tui: ${this.i18n.t('notice.reconnect')}${detail}\n`)
      }
    } catch {
      // the terminal may already be gone
    }
    this.running = false
    this.resolveExit(this.exitCode)
  }

  /** Resolves with the exit code once the terminal has been restored. */
  waitForExit() {
    return this.exitPromise
  }

  /** @private */
  childEnvironment() {
    const env = { ...process.env, ...this.env }
    env.TERM = env.TERM ?? 'xterm-256color'
    env.COLORTERM = env.COLORTERM ?? 'truecolor'
    // Nothing is renamed or copied here. The launcher names the account's key after the
    // route it wrote (`HSDL_LAUNCH_API_KEY_<route>`), and the harness resolves that name
    // from the route itself, so the two meet without a translation table. Copying "some
    // launch key" into `DEEPSEEK_API_KEY` — which this used to do — handed a third-party
    // vendor's key to the harness's own DeepSeek entry, web search included, and picked
    // the key by object-key order when a launch carried more than one. The one variable
    // the DeepSeek path needs is set by the launcher, for DeepSeek accounts only.
    return env
  }

  /** @private */
  installInput() {
    if (typeof this.stdin.setRawMode === 'function') this.stdin.setRawMode(true)
    this.stdin.resume()
    this.stdin.setEncoding('utf8')
    this.onInputData = (chunk) => {
      this.codec.feed(chunk)
      if (this.codec.pending) {
        if (this.escTimer !== null) clearTimeout(this.escTimer)
        this.escTimer = setTimeout(() => {
          this.escTimer = null
          this.codec.flush()
        }, ESC_DELAY_MS)
      }
    }
    this.onInputEnd = () => this.quit()
    this.stdin.on('data', this.onInputData)
    this.stdin.on('end', this.onInputEnd)
  }

  // ── blocks ──────────────────────────────────────────────────────────────

  /** @private */
  makeBlock(block) {
    const created = { ...block, rev: 0 }
    this.blocks.push(created)
    this.scroll.follow = true
    this.scroll.offset = 0
    this.dirty = true
    return created
  }

  /** @private */
  bump(block) {
    block.rev += 1
    this.dirty = true
  }

  /** @private */
  pushBanner() {
    this.makeBlock({
      type: 'banner',
      tag: 'dsh-acp-tui',
      version: `v${VERSION}`,
      subtitle: this.i18n.t('banner.subtitle'),
      hint: this.i18n.t('banner.hint'),
      sessionId: this.resume ?? this.sessionId,
      cwd: this.cwd,
    })
  }

  /** @private */
  pushNotice(text, level = 'info') {
    this.makeBlock({ type: 'notice', text, level })
  }

  /** @private */
  pushUser(text) {
    this.makeBlock({ type: 'user', text })
  }

  /** @private */
  currentStreaming(kind) {
    for (let index = this.blocks.length - 1; index >= 0; index -= 1) {
      const block = this.blocks[index]
      if (block.type === kind && block.streaming) return block
      if (block.type === 'user' || block.type === 'notice') break
    }
    return null
  }

  /** @private */
  appendStream(kind, text, messageId, id) {
    let block = this.currentStreaming(kind)
    if (block === null || (messageId !== undefined && block.messageId !== undefined && block.messageId !== messageId)) {
      block = this.makeBlock({
        type: kind,
        messageId,
        id,
        full: '',
        text: '',
        streaming: true,
        expanded: false,
      })
    }
    if (text === '') return block
    block.full = (block.full ?? '') + text
    this.revealing.add(block)
    this.activity.tool = null
    return block
  }

  /** @private */
  finishStreaming() {
    for (const block of this.revealing) {
      block.text = block.full ?? block.text
      block.revealingDone = true
      this.bump(block)
    }
    this.revealing.clear()
    for (const block of this.blocks) {
      if ((block.type === 'assistant' || block.type === 'thought') && block.streaming) {
        block.streaming = false
        block.text = block.full ?? block.text
        this.bump(block)
      }
    }
  }

  // ── ACP ─────────────────────────────────────────────────────────────────

  /** @private */
  async openSession() {
    if (this.resume !== null) {
      try {
        const result = await this.client.request('session/resume', {
          sessionId: this.resume,
          cwd: this.cwd,
          mcpServers: [],
        })
        this.adoptSession(result, this.resume)
        this.resume = null
        return
      } catch (error) {
        this.pushNotice(`${this.failureText(error)} — starting a new session instead`, 'error')
      }
    }
    const result = await this.client.request('session/new', { cwd: this.cwd, mcpServers: [] })
    this.adoptSession(result, result.sessionId)
  }

  /** @private */
  adoptSession(result, sessionId) {
    this.sessionId = sessionId ?? result?.sessionId ?? null
    this.applyConfigOptions(result?.configOptions)
    this.usage = { used: 0, size: 0 }
    // The banner is what shows the session id, so it is bumped to repaint. A
    // caller that cleared the transcript first (a fresh `/new`) may have nothing
    // there yet; that is not an error, just nothing to repaint.
    const anchor = this.blocks[0]
    if (anchor !== undefined) this.bump(anchor)
    this.dirty = true
  }

  /** @private */
  applyConfigOptions(options) {
    if (!Array.isArray(options)) return
    for (const option of options) {
      if (option === null || typeof option !== 'object') continue
      if (option.id === 'model') {
        const flat = []
        for (const entry of option.options ?? []) {
          if (Array.isArray(entry.options)) {
            for (const child of entry.options) flat.push({ ...child, group: entry.name ?? entry.group })
          } else if (entry.value !== undefined) {
            flat.push(entry)
          }
        }
        this.model = {
          current: option.currentValue ?? null,
          currentLabel: labelOf(flat, option.currentValue),
          options: flat,
        }
      } else if (option.id === 'reasoning_effort' || option.category === 'thought_level') {
        const flat = (option.options ?? []).filter((entry) => entry.value !== undefined)
        this.effort = {
          current: option.currentValue ?? null,
          options: flat,
        }
      }
    }
    this.dirty = true
  }

  /** @private */
  onNotification(message) {
    if (message.method !== 'session/update') return
    const sessionId = message.params?.sessionId
    // A late update from the session that was just replaced must not land in
    // the new transcript: the switch is a different conversation, not a
    // continuation, and a stray chunk reads as the new session answering.
    if (typeof sessionId === 'string' && sessionId !== this.sessionId) return
    this.applyUpdate(message.params?.update)
  }

  /** @private */
  applyUpdate(update) {
    if (update === null || typeof update !== 'object') return
    switch (update.sessionUpdate) {
      case 'agent_message_chunk': {
        const text = textOf(update.content)
        if (text === '') return
        const block = this.appendStream('assistant', text, update.messageId, update.messageId)
        this.bump(block)
        return
      }
      case 'agent_thought_chunk': {
        const text = textOf(update.content)
        if (text === '') return
        const block = this.appendStream('thought', text, update.messageId, update.messageId)
        this.bump(block)
        return
      }
      case 'user_message_chunk':
        return
      case 'tool_call': {
        // Some bridges repeat the opening update; treat it as an update, not a
        // second card, so the transcript does not grow a twin per call.
        const existing = this.findTool(update.toolCallId)
        if (existing !== null) {
          if (update.status !== undefined) existing.status = update.status
          if (update.rawInput !== undefined) existing.input = update.rawInput
          this.bump(existing)
          return
        }
        // No `expanded` field: the global toggle (ctrl+o / shift+tab) decides,
        // and a card that pinned itself closed would ignore it.
        const block = this.makeBlock({
          type: 'tool',
          callId: update.toolCallId,
          name: update.title ?? update.kind ?? 'tool',
          kind: update.kind ?? 'other',
          input: update.rawInput,
          status: update.status ?? 'in_progress',
          startedAt: this.clock,
          output: null,
        })
        this.activity.tool = block.name
        this.activity.startedAt = this.activity.startedAt ?? this.clock
        return
      }
      case 'tool_call_update': {
        const block = this.findTool(update.toolCallId)
        if (block === null) return
        if (update.status !== undefined) block.status = update.status
        if (Array.isArray(update.content)) {
          const parts = update.content
            .map((item) => (item?.type === 'content' ? textOf(item.content) : ''))
            .filter((value) => value !== '')
          if (parts.length > 0) block.output = parts.join('\n')
        }
        if (update.rawInput !== undefined) block.input = update.rawInput
        if (block.status === 'completed' || block.status === 'failed' || block.status === 'cancelled') {
          block.endedAt = this.clock
          this.activity.tool = null
        }
        this.bump(block)
        return
      }
      case 'usage_update': {
        this.usage = { used: update.used ?? 0, size: update.size ?? 0 }
        this.dirty = true
        return
      }
      case 'config_option_update':
      case 'config_options_update': {
        this.applyConfigOptions(update.configOptions ?? update.options)
        return
      }
      default:
        this.log(`unhandled update: ${update.sessionUpdate}`)
    }
  }

  /** @private */
  findTool(callId) {
    if (callId === undefined) return null
    for (let index = this.blocks.length - 1; index >= 0; index -= 1) {
      const block = this.blocks[index]
      if (block.type === 'tool' && block.callId === callId) return block
    }
    return null
  }

  /** @private */
  onRequest(message) {
    if (message.method === 'session/request_permission') {
      // A second approval while one is on screen must not replace the first: the
      // harness is waiting on both, and overwriting the pending entry would leave
      // the earlier request unanswered forever. Queue it; the first dialog is
      // shown until the person answers it, then the next one takes its place.
      if (this.pendingPermission !== null || this.permissionQueue.length > 0) {
        this.permissionQueue.push(message)
        this.dirty = true
        return
      }
      this.showPermission(message)
      return
    }
    // Any other request is one this client cannot serve; answer "cancelled" so
    // the harness never waits on a promise nobody will settle.
    this.client?.respond(message.id, { error: { code: -32601, message: `unsupported request: ${message.method}` } })
  }

  /** @private */
  showPermission(message) {
    const params = message.params ?? {}
    this.pendingPermission = { id: message.id, params }
    const options = (params.options ?? []).map((option) => ({
      label: permissionLabel(option, this.i18n),
      value: option.optionId,
      kind: option.kind,
    }))
    this.overlay = {
      kind: 'permission',
      toolName: params.toolCall?.title ?? params.toolCall?.kind ?? params.toolCall?.name,
      detail: permissionDetail(params.toolCall),
      options: options.length > 0 ? options : [{ label: this.i18n.t('permission.reject'), value: null, kind: 'reject_once' }],
      index: 0,
    }
    this.dirty = true
    this.render()
  }

  /** @private */
  answerPermission(choice) {
    const pending = this.pendingPermission
    if (pending === null) return
    const value = choice?.value ?? null
    if (value === null) {
      this.client?.respond(pending.id, { result: { outcome: { outcome: 'cancelled' } } })
    } else {
      this.client?.respond(pending.id, { result: { outcome: { outcome: 'selected', optionId: value } } })
    }
    this.pendingPermission = null
    this.overlay = null
    this.dirty = true
    const next = this.permissionQueue.shift()
    if (next !== undefined) this.showPermission(next)
  }

  /** @private */
  onExit(info) {
    if (this.stopping) return
    const connecting = this.status === 'connecting'
    this.status = 'disconnected'
    const detail = info?.error?.message ?? (this.client?.stderrLines?.slice(-3).join(' ') ?? '')
    this.exitDetail = detail
    this.dirty = true
    if (connecting) {
      // `start` is still awaiting the handshake; its catch turns this into one
      // notice and one stop, so do not race it with a second exit path.
      return
    }
    this.pushNotice(`${this.i18n.t('notice.reconnect')}${detail === '' ? '' : ` — ${detail}`}`, 'error')
    this.render()
    // Nothing is left to drive once the harness is gone: restore the terminal so
    // the process can exit instead of sitting in raw mode on a dead pipe.
    const code = Number.isInteger(info?.code) && info.code !== 0 ? info.code : 1
    this.quit(code, 'exit').catch(() => {})
  }

  /**
   * Shows a line the protocol reader had to drop.
   *
   * A stray non-JSON line is usually the harness logging to stdout; one notice
   * tells the person their session is still alive without letting a chatty
   * process flood the transcript.
   *
   * @private
   */
  onProtocolError(error) {
    this.log(`protocol: ${error?.message ?? error}`)
    const now = Date.now()
    if (now - this.protocolNoticeAt < 2000) return
    this.protocolNoticeAt = now
    this.pushNotice(`ACP: ${error?.message ?? error}`, 'error')
  }

  // ── keys ────────────────────────────────────────────────────────────────

  /** @private */
  onKeyEvent(event) {
    if (this.overlay !== null) {
      // The command palette floats over the text being typed, so the keys that
      // edit text keep editing it and the palette merely re-filters; a dialog
      // that owns the keyboard (permission, picker) is untouched by this.
      if (isLiveOverlay(this.overlay) && this.editLivePalette(event)) return
      this.handleOverlayKey(event)
      return
    }
    const { name } = event
    if (name === 'paste') {
      // The terminal sends the whole clipboard as one event; it is the user's
      // text, so it goes in at the caret with CRLF normalised by the composer.
      if (this.composer.paste(event.text ?? '')) {
        this.syncCommandPalette()
        this.scrollToBottom()
        this.dirty = true
      }
      return
    }
    if (name === 'char') {
      this.composer.insert(event.text ?? '')
      this.syncCommandPalette()
      this.scrollToBottom()
      return
    }
    if (name === 'enter') {
      // `ctrl+j` arrives as enter+ctrl from the decoder (a raw newline is a
      // control byte, not a key name every terminal agrees on), and it means
      // the same thing shift+enter and alt+enter do: a line break, not a send.
      if (event.shift || event.alt || event.ctrl) {
        this.composer.newline()
        this.dirty = true
        return
      }
      this.submitComposer()
      return
    }
    if (name === 'backspace') {
      this.composer.backspace()
      this.dirty = true
      return
    }
    if (name === 'delete') {
      this.composer.deleteForward()
      this.dirty = true
      return
    }
    if (name === 'left' || name === 'right' || name === 'up' || name === 'down') {
      if (name === 'up' || name === 'down') {
        const single = !this.composer.text.includes('\n')
        if (single && (name === 'up' || this.composer.historyIndex !== -1)) {
          this.composer.recall(name)
          this.dirty = true
          return
        }
      }
      this.composer.move(name, Math.max(1, this.screen.columns - 4))
      this.dirty = true
      return
    }
    if (name === 'home' || name === 'end') {
      this.composer.moveLineEdge(name, Math.max(1, this.screen.columns - 4))
      this.dirty = true
      return
    }
    if (name === 'page-up' || name === 'page-down') {
      this.scrollBy(name === 'page-up' ? this.screen.rows - 4 : -(this.screen.rows - 4))
      return
    }
    if (name === 'escape') {
      if (this.status === 'working') this.cancelTurn()
      else this.scrollToBottom()
      return
    }
    if (name === 'ctrl+c') {
      this.handleInterrupt()
      return
    }
    if (name === 'ctrl+d') {
      if (this.composer.empty) this.quit()
      else this.composer.deleteForward()
      this.dirty = true
      return
    }
    if (name === 'ctrl+j') {
      this.composer.newline()
      this.dirty = true
      return
    }
    if (name === 'ctrl+a') {
      this.composer.moveLineEdge('home', Math.max(1, this.screen.columns - 4))
      this.dirty = true
      return
    }
    if (name === 'ctrl+e') {
      this.composer.moveLineEdge('end', Math.max(1, this.screen.columns - 4))
      this.dirty = true
      return
    }
    if (name === 'ctrl+u') {
      this.composer.deleteToLineStart(Math.max(1, this.screen.columns - 4))
      this.dirty = true
      return
    }
    if (name === 'ctrl+k') {
      this.composer.deleteToEnd()
      this.dirty = true
      return
    }
    if (name === 'ctrl+w') {
      this.composer.deleteWord()
      this.dirty = true
      return
    }
    if (name === 'ctrl+o') {
      this.expandTools = !this.expandTools
      this.transcriptCache = new WeakMap()
      this.dirty = true
      return
    }
    if (name === 'ctrl+r') {
      this.openResumePicker()
      return
    }
    if (name === 'ctrl+l') {
      this.blocks = []
      this.transcriptCache = new WeakMap()
      this.screen.clear()
      this.pushBanner()
      this.dirty = true
      return
    }
    if (name === 'tab') {
      this.completeCommand()
      return
    }
    if (name === 'shift+tab') {
      this.expandTools = !this.expandTools
      this.transcriptCache = new WeakMap()
      this.dirty = true
    }
  }

  /** @private */
  handleInterrupt() {
    if (this.status === 'working') {
      this.cancelTurn()
      return
    }
    if (!this.composer.empty) {
      this.composer.setText('')
      this.dirty = true
      return
    }
    const now = Date.now()
    if (this.quitArmedAt !== null && now - this.quitArmedAt < 2000) {
      this.quit()
      return
    }
    this.quitArmedAt = now
    this.pushNotice(this.i18n.t('quit.confirm'), 'info')
    this.dirty = true
  }

  /**
   * Edits the composer behind a live palette, then re-filters the palette.
   *
   * Returns `false` for the keys the palette itself owns (arrows, page keys,
   * enter, escape), which is what lets one overlay both follow the text and
   * still be navigable.
   *
   * @private
   * @param {{name: string, text?: string}} event the decoded key
   * @returns {boolean} whether the key was consumed as text editing
   */
  editLivePalette(event) {
    const width = Math.max(1, this.screen.columns - 4)
    if (event.name === 'left' || event.name === 'right') {
      this.composer.move(event.name, width)
      this.syncCommandPalette()
      this.dirty = true
      return true
    }
    if (event.name === 'tab') {
      this.completeCommand()
      return true
    }
    if (!applyEditKey(this.composer, event, width)) return false
    this.syncCommandPalette()
    this.scrollToBottom()
    this.dirty = true
    return true
  }

  /** @private */
  handleOverlayKey(event) {
    const overlay = this.overlay
    if (overlay === null) return
    const { name } = event
    if (name === 'paste') {
      // A dialog owns the keyboard while it is up; the composer behind it is not
      // visible, so inserting there would edit text the person cannot see (and
      // could replace the very palette this key came through). Dropping the
      // paste is the only outcome that does not surprise.
      return
    }
    if (name === 'escape' || name === 'ctrl+c') {
      if (overlay.kind === 'permission') this.answerPermission(null)
      else this.overlay = null
      this.dirty = true
      return
    }
    if (name === 'up' || name === 'down') {
      const length = overlay.items?.length ?? overlay.options?.length ?? 0
      if (length === 0) return
      overlay.index = (overlay.index ?? 0) + (name === 'up' ? -1 : 1)
      if (overlay.index < 0) overlay.index = length - 1
      if (overlay.index >= length) overlay.index = 0
      this.dirty = true
      return
    }
    if (name === 'page-up' || name === 'page-down') {
      const length = overlay.items?.length ?? 0
      if (length === 0) return
      overlay.index = Math.max(0, Math.min(length - 1, (overlay.index ?? 0) + (name === 'page-up' ? -6 : 6)))
      this.dirty = true
      return
    }
    if (name === 'char' && /^[1-9]$/u.test(event.text ?? '')) {
      const index = Number(event.text) - 1
      if (overlay.kind === 'permission') {
        const option = overlay.options?.[index]
        if (option !== undefined) this.answerPermission(option)
      } else if (overlay.kind === 'picker') {
        const item = overlay.items?.[index]
        if (item !== undefined) this.chooseOverlayItem(item)
      }
      return
    }
    if (name === 'enter') {
      if (overlay.kind === 'permission') {
        const option = overlay.options?.[overlay.index ?? 0]
        this.answerPermission(option ?? null)
        return
      }
      if (overlay.kind === 'picker') {
        const item = overlay.items?.[overlay.index ?? 0]
        if (item !== undefined) this.chooseOverlayItem(item)
        return
      }
      this.overlay = null
      this.dirty = true
    }
  }

  /** @private */
  chooseOverlayItem(item) {
    if (typeof item.onSelect === 'function') {
      item.onSelect()
      return
    }
    this.overlay = null
    this.dirty = true
  }

  // ── commands and turns ──────────────────────────────────────────────────

  /** @private */
  syncCommandPalette() {
    const text = this.composer.text
    if (!text.startsWith('/') || text.includes(' ') || text.includes('\n')) {
      // The text stopped being a command prefix (`/help args`, a newline, a
      // paste that is not a command): the palette goes away instead of staying
      // up over a line it no longer describes. Only ours is cleared this way.
      if (isLiveOverlay(this.overlay)) {
        this.overlay = null
        this.dirty = true
      }
      return
    }
    const prefix = text.slice(1).toLowerCase()
    const items = COMMANDS
      .filter((command) => command.name.startsWith(prefix))
      .map((command) => ({
        label: `/${command.name}`,
        description: this.i18n.t(command.key),
        onSelect: () => {
          this.overlay = null
          this.composer.setText('')
          this.runCommand(command.name, '')
        },
      }))
    if (items.length === 0) {
      this.overlay = null
      return
    }
    this.overlay = {
      // `live` is what keeps the letters typed after `/` going into the line:
      // the palette follows the text rather than taking the keyboard for itself.
      live: true,
      kind: 'picker',
      title: 'commands',
      width: 56,
      height: Math.min(14, items.length + 4),
      items,
      index: 0,
      hint: this.i18n.t('model.hint'),
    }
    this.dirty = true
  }

  /** @private */
  completeCommand() {
    const text = this.composer.text
    if (!text.startsWith('/')) return
    const prefix = text.slice(1)
    const match = COMMANDS.find((command) => command.name.startsWith(prefix))
    if (match !== undefined) {
      this.composer.setText(`/${match.name} `)
      this.overlay = null
      this.dirty = true
    }
  }

  /** @private */
  submitComposer() {
    const value = this.composer.submit()
    this.overlay = null
    if (value.trim() === '') {
      this.dirty = true
      return
    }
    if (value.startsWith('/')) {
      const [head, ...rest] = value.trim().slice(1).split(/\s+/u)
      this.runCommand(head, rest.join(' '))
      return
    }
    this.sendPrompt(value)
  }

  /** @private */
  sendPrompt(text) {
    this.pushUser(text)
    this.scrollToBottom()
    if (this.status === 'working') {
      this.queue.push(text)
      this.dirty = true
      return
    }
    this.runPrompt(text)
  }

  /**
   * Runs one prompt to completion.
   *
   * The request is kept in `pendingTurn` so a session switch can **wait** for it:
   * cancelling only sends a notification, and the answer to a cancelled turn
   * arrives afterwards as an ordinary response. Left unguarded, that answer
   * finished the *new* session's streaming block, rewrote its status and drained
   * its queue — a late reply from a conversation that is no longer on screen.
   *
   * Every mutation below therefore checks that this turn still owns the screen
   * (`ownsTurn`); a turn that lost it does nothing at all.
   *
   * @private
   * @param {string} text the prompt
   */
  async runPrompt(text) {
    if (this.client === null || this.sessionId === null) {
      this.pushNotice(this.startupError === null ? this.i18n.t('status.disconnected') : this.failureText(this.startupError), 'error')
      return
    }
    const turn = this.turnSerial
    const sessionId = this.sessionId
    this.status = 'working'
    this.activity = { tool: null, startedAt: this.clock }
    this.lastTurnSeconds = null
    this.scrollToBottom()
    this.dirty = true
    const started = this.clock
    // One promise for the request either way, so the wait does not depend on
    // whether it succeeds — and it never rejects, which is what keeps a switch's
    // `await` from turning a failed turn into a failed switch.
    const settled = this.client.request('session/prompt', {
      sessionId,
      prompt: [{ type: 'text', text }],
    }).then((result) => ({ result }), (error) => ({ error }))
    this.pendingTurn = settled
    const { result, error } = await settled
    if (this.pendingTurn === settled) this.pendingTurn = null
    if (this.stopped || !this.ownsTurn(turn, sessionId)) {
      // The session was replaced (or the terminal is shutting down) while this
      // turn was answering. Its text belongs to the transcript that was cleared,
      // and its status to nobody.
      this.dirty = true
      return
    }
    this.finishStreaming()
    this.status = 'ready'
    this.activity = { tool: null, startedAt: null }
    if (error === undefined) {
      this.lastTurnSeconds = (this.clock - started) / 1000
      if (result?.stopReason === 'cancelled') this.pushNotice(this.i18n.t('notice.cancelled'), 'info')
    } else {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
    this.drainQueue()
  }

  /**
   * Whether a turn still owns the screen it started on.
   *
   * @private
   * @param {number} turn the serial the turn was started under
   * @param {string} sessionId the session it was started in
   * @returns {boolean}
   */
  ownsTurn(turn, sessionId) {
    return turn === this.turnSerial && sessionId === this.sessionId
  }

  /**
   * Waits for the turn in flight, but never forever.
   *
   * @private
   * @param {number} [timeoutMs]
   */
  async settleTurn(timeoutMs = this.turnSettleMs) {
    const pending = this.pendingTurn
    if (pending === null || pending === undefined) return
    let timer = null
    try {
      await Promise.race([
        pending,
        new Promise((resolve) => {
          timer = setTimeout(resolve, timeoutMs)
        }),
      ])
    } finally {
      if (timer !== null) clearTimeout(timer)
    }
  }

  /** @private */
  drainQueue() {
    if (this.queue.length === 0 || this.status === 'working') return
    const next = this.queue.shift()
    this.runPrompt(next)
  }

  /** @private */
  async cancelTurn() {
    if (this.sessionId === null || this.client === null) return
    try {
      this.client.notify('session/cancel', { sessionId: this.sessionId })
    } catch {
      // A cancelled notification is best effort; the prompt settles either way.
    }
    this.queue = []
    this.dirty = true
  }

  /** @private */
  runCommand(name, argument) {
    switch (name) {
      case 'help':
        this.openHelp()
        return
      case 'clear':
        this.blocks = []
        this.transcriptCache = new WeakMap()
        this.screen.clear()
        this.pushBanner()
        this.dirty = true
        return
      case 'model':
        this.openModelPicker()
        return
      case 'effort':
        this.openEffortPicker()
        return
      case 'resume':
        this.openResumePicker()
        return
      case 'new':
        this.startNewSession()
        return
      case 'quit':
      case 'exit':
        this.quit()
        return
      default:
        this.pushNotice(this.i18n.t('cmd.unknown', { name }), 'error')
    }
    if (argument !== undefined && argument !== '') this.log(`ignored argument for /${name}: ${argument}`)
  }

  /** @private */
  openHelp() {
    this.overlay = {
      kind: 'help',
      title: this.i18n.t('help.title'),
      width: 64,
      height: 24,
      sections: [
        { title: this.i18n.t('help.commands'), rows: COMMANDS.map((command) => [`/${command.name}`, this.i18n.t(command.key)]) },
        {
          title: this.i18n.t('help.keys'),
          rows: [
            ['enter', this.i18n.t('composer.hint.send')],
            ['ctrl+j', this.i18n.t('composer.hint.newline')],
            ['esc', this.i18n.t('hint.esc')],
            ['ctrl+o', this.i18n.t('hint.ctrl-o')],
            ['pgup/pgdn', this.i18n.t('hint.scroll')],
            ['ctrl+c', this.i18n.t('hint.ctrl-c')],
          ],
        },
      ],
    }
    this.dirty = true
  }

  /** @private */
  openModelPicker() {
    const options = this.model.options
    if (options.length === 0) {
      this.pushNotice(this.i18n.t('notice.error') + ': the harness advertised no model', 'error')
      return
    }
    const items = options.map((option) => ({
      label: option.name ?? option.value,
      description: option.description,
      group: option.group,
      tail: option.value === this.model.current ? this.i18n.t('model.current') : undefined,
      onSelect: () => this.setConfigOption('model', option.value),
    }))
    this.overlay = { kind: 'picker', title: this.i18n.t('model.title'), width: 62, height: 18, items, index: indexOfValue(options, this.model.current, 'value') }
    this.dirty = true
  }

  /** @private */
  openEffortPicker() {
    const options = this.effort.options
    if (options.length === 0) {
      this.pushNotice(this.i18n.t('notice.error') + ': no reasoning effort selector', 'error')
      return
    }
    const items = options.map((option) => ({
      label: option.name ?? option.value,
      description: option.description,
      tail: option.value === this.effort.current ? this.i18n.t('model.current') : undefined,
      onSelect: () => this.setConfigOption('reasoning_effort', option.value),
    }))
    this.overlay = { kind: 'picker', title: this.i18n.t('effort.title'), width: 56, height: 12, items, index: indexOfValue(options, this.effort.current, 'value') }
    this.dirty = true
  }

  /** @private */
  async setConfigOption(configId, value) {
    this.overlay = null
    this.dirty = true
    if (this.client === null || this.sessionId === null) return
    try {
      const result = await this.client.request('session/set_config_option', {
        sessionId: this.sessionId,
        configId,
        value,
      })
      this.applyConfigOptions(result?.configOptions ?? [])
      const label = configId === 'model' ? this.model.currentLabel : this.effort.current
      this.pushNotice(`${configId} → ${label ?? value}`, 'success')
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
  }

  /** @private */
  async openResumePicker() {
    if (this.client === null) return
    let sessions = []
    try {
      const result = await this.client.request('session/list', {})
      sessions = (result?.sessions ?? []).slice(0, 30)
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
      return
    }
    const items = sessions.map((session) => ({
      label: shortId(session.sessionId),
      tail: session.cwd === this.cwd ? this.i18n.t('resume.here') : session.cwd,
      onSelect: () => this.resumeSession(session.sessionId),
    }))
    if (items.length === 0) {
      this.pushNotice(this.i18n.t('resume.empty'), 'info')
      return
    }
    this.overlay = {
      kind: 'picker',
      title: this.i18n.t('resume.title'),
      width: 66,
      height: 20,
      items,
      index: 0,
      emptyText: this.i18n.t('resume.empty'),
      hint: this.i18n.t('resume.hint'),
    }
    this.dirty = true
  }

  /**
   * Quiets the outgoing session before another one takes the screen.
   *
   * Cancelling is only a notification, so this **waits** for the turn it just
   * cancelled to answer (bounded — a tool parked on an approval must not pin the
   * terminal), and then bumps the turn serial: whatever is still in flight is no
   * longer the turn that owns the screen, and its answer becomes a no-op instead
   * of finishing the new session's block and rewriting its status.
   *
   * @private
   */
  async detachTurn() {
    this.queue = []
    this.activity = { tool: null, startedAt: null }
    if (this.status === 'working' && this.client !== null && this.sessionId !== null) {
      try {
        this.client.notify('session/cancel', { sessionId: this.sessionId })
      } catch {
        // A cancelled notification is best effort; the wait below is what matters.
      }
      await this.settleTurn()
    }
    this.turnSerial += 1
    this.pendingTurn = null
    this.status = 'ready'
    this.dirty = true
  }

  /** @private */
  async resumeSession(sessionId) {
    this.overlay = null
    if (this.client === null) return
    try {
      await this.detachTurn()
      const result = await this.client.request('session/resume', { sessionId, cwd: this.cwd, mcpServers: [] })
      this.blocks = this.blocks.filter((block) => block.type === 'banner')
      this.transcriptCache = new WeakMap()
      this.adoptSession(result, sessionId)
      this.pushNotice(this.i18n.t('banner.resume', { id: shortId(sessionId) }), 'info')
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
  }

  /** @private */
  async startNewSession() {
    if (this.client === null) return
    try {
      await this.detachTurn()
      if (this.sessionId !== null && this.client.sessions.close !== undefined) {
        await this.client.request('session/close', { sessionId: this.sessionId }).catch(() => {})
      }
      const result = await this.client.request('session/new', { cwd: this.cwd, mcpServers: [] })
      this.blocks = []
      this.transcriptCache = new WeakMap()
      // Adopt first (it tolerates an empty transcript), so the banner this puts
      // back carries the id of the session that was just created.
      this.adoptSession(result, result.sessionId)
      this.pushBanner()
      this.pushNotice(this.i18n.t('banner.new'), 'success')
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
  }

  // ── scrolling, ticks, rendering ─────────────────────────────────────────

  /** @private */
  scrollBy(lines) {
    const total = this.transcriptTotal ?? 0
    const height = Math.max(1, this.screen.rows - 8)
    const max = Math.max(0, total - height)
    const base = this.scroll.follow ? 0 : this.scroll.offset
    this.scroll.offset = Math.max(0, Math.min(max, base + lines))
    this.scroll.follow = this.scroll.offset === 0
    this.dirty = true
  }

  /** @private */
  scrollToBottom() {
    this.scroll.follow = true
    this.scroll.offset = 0
    this.dirty = true
  }

  /** @private */
  tick() {
    this.clock = Date.now()
    if (this.revealing.size > 0) {
      for (const block of this.revealing) {
        const full = block.full ?? ''
        const shown = block.text.length
        if (shown >= full.length) {
          this.revealing.delete(block)
          continue
        }
        const remaining = full.length - shown
        const step = Math.max(REVEAL_PER_FRAME, Math.ceil(remaining / REVEAL_MAX_FRAMES))
        block.text = full.slice(0, Math.min(full.length, shown + step))
        this.bump(block)
      }
    }
    if (this.status === 'working' || this.status === 'connecting') this.dirty = true
    if (this.dirty) this.render()
  }

  /** Paints one frame if the terminal has a size worth painting. */
  render() {
    this.dirty = false
    const { columns, rows } = this.screen.measure()
    if (columns < 20 || rows < 6) {
      this.screen.paint([], null)
      return
    }
    const frame = renderFrame(this, {
      width: columns,
      height: rows,
      theme: this.theme,
      i18n: this.i18n,
      clock: this.clock || Date.now(),
    })
    this.transcriptTotal = frame.transcript.total
    this.screen.paint(frame.rows, frame.cursor)
  }

  /** @private */
  failureText(error) {
    if (error === null || error === undefined) return this.i18n.t('error.acp', { message: 'unknown' })
    const message = error.message ?? String(error)
    return this.i18n.t('error.acp', { message })
  }

  /** @private */
  log(line) {
    if (this.env.HSDL_TUI_DEBUG === undefined) return
    try {
      this.stdout.write(`\r\n[debug] ${line}\r\n`)
    } catch {
      // the terminal may already be gone
    }
  }

  /** Leaves with the given code. */
  async quit(code = 0, reason = 'quit') {
    if (this.stopping) return
    this.stopping = true
    this.exitCode = code
    await this.stop(reason)
  }
}

function textOf(content) {
  if (content === null || content === undefined) return ''
  if (typeof content === 'string') return content
  if (content.type === 'text') return String(content.text ?? '')
  if (Array.isArray(content)) return content.map((part) => textOf(part)).join('')
  return ''
}

function labelOf(options, value) {
  const found = options.find((option) => option.value === value)
  return found?.name ?? value
}

function indexOfValue(options, value, key) {
  const index = options.findIndex((option) => option[key] === value)
  return index < 0 ? 0 : index
}

function permissionLabel(option, i18n) {
  switch (option.kind) {
    case 'allow_once':
      return i18n.t('permission.allow-once')
    case 'allow_always':
      return i18n.t('permission.allow-always')
    case 'reject_once':
    case 'reject_always':
      return i18n.t('permission.reject')
    default:
      return option.name ?? option.optionId
  }
}

function permissionDetail(toolCall) {
  if (toolCall === undefined || toolCall === null) return ''
  const input = toolCall.rawInput
  if (typeof input === 'string') return input
  if (input !== null && typeof input === 'object') {
    const command = input.command ?? input.path ?? input.file_path
    if (typeof command === 'string') return command
    try {
      return JSON.stringify(input)
    } catch {
      return ''
    }
  }
  return ''
}

/** Creates the state object the tests render without a harness. */
export function createInitialState(options = {}) {
  const language = options.language ?? 'en'
  return {
    blocks: options.blocks ?? [],
    transcriptCache: new WeakMap(),
    composer: new Composer({ text: options.composerText ?? '', maxLines: 8 }),
    queue: [],
    scroll: { offset: 0, follow: true },
    overlay: null,
    focus: 'composer',
    expandTools: false,
    status: options.status ?? 'ready',
    activity: { tool: null, startedAt: null },
    usage: options.usage ?? { used: 0, size: 0 },
    model: options.model ?? { current: null, currentLabel: null, options: [] },
    effort: options.effort ?? { current: null, options: [] },
    lastTurnSeconds: null,
    sessionId: options.sessionId ?? null,
    cwd: options.cwd ?? process.cwd(),
    clock: options.clock ?? 0,
    language,
    i18n: createI18n(language),
  }
}
