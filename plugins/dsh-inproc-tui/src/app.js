/**
 * The interactive terminal, running inside the harness process.
 *
 * This is the 0.2.x counterpart of the ACP terminal: the bundle patch mounts this
 * plugin over `dsh-base`, so instead of a protocol it gets the agent registry,
 * the live session log and the streaming assistant feed directly. That is what
 * makes it the fuller interface — token-level text, live reasoning, approval
 * prompts, questions, real compaction — and it is also why it is pinned to the
 * 0.2.x line: those services are internal seams, not a contract.
 *
 * Rendering, key decoding, the composer and the dialogs come from
 * `plugins/shared`, the same toolkit the ACP terminal uses.
 *
 * @module app
 */

import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

import { UserQuestionError } from '@deepseek-ai/dsh-user-questions'

import { createI18n, detectLanguage } from '../../shared/i18n.js'
import { createTheme, detectMode } from '../../shared/term/theme.js'
import { Screen } from '../../shared/term/screen.js'
import { createKeyDecoder } from '../../shared/term/keys.js'
import { Composer } from '../../shared/ui/composer.js'
import { renderFrame, spinnerFrame } from '../../shared/ui/render.js'
import { formatDuration, shortId } from '../../shared/ui/blocks.js'
import { ChatSession } from './agent.js'
import { VERSION } from './version.js'

const TICK_MS = 33
const ESC_DELAY_MS = 25
const REVEAL_LIMIT = 200_000

const EFFORTS = [
  { value: 'off', name: 'off' },
  { value: 'low', name: 'low' },
  { value: 'high', name: 'high' },
  { value: 'max', name: 'max' },
]

const COMMANDS = [
  { name: 'help', key: 'cmd.help' },
  { name: 'model', key: 'cmd.model' },
  { name: 'effort', key: 'cmd.effort' },
  { name: 'resume', key: 'cmd.resume' },
  { name: 'new', key: 'cmd.new' },
  { name: 'compact', key: 'cmd.compact' },
  { name: 'clear', key: 'cmd.clear' },
  { name: 'quit', key: 'cmd.quit' },
]

/** The terminal application. */
export class TerminalApp {
  /**
   * @param {object} ctx the Cordis context of this plugin
   */
  constructor(ctx) {
    this.ctx = ctx
    this.env = process.env
    this.language = detectLanguage(this.env)
    this.i18n = createI18n(this.language)
    this.theme = createTheme({ mode: detectMode(this.env), color: this.env.NO_COLOR === undefined })
    this.screen = new Screen(process.stdout, process.stdin)

    this.cwd = process.cwd()
    this.session = new ChatSession(ctx, this.cwd)
    this.blocks = []
    this.transcriptCache = new WeakMap()
    this.composer = new Composer({ maxLines: 8 })
    this.queue = []
    this.scroll = { offset: 0, follow: true }
    this.overlay = null
    this.interactions = []
    this.activeInteraction = null
    this.focus = 'composer'
    this.expandTools = false
    this.status = 'connecting'
    this.activity = { tool: null, startedAt: null }
    this.usage = { used: 0, size: 0 }
    this.model = { current: null, currentLabel: null, options: [] }
    this.effort = { current: null, options: [] }
    this.lastTurnSeconds = null
    this.turnEndedAt = undefined
    this.turnStartedAt = null
    this.gitBranch = detectGitBranch(this.cwd)
    this.clock = 0
    this.dirty = true
    this.timer = null
    this.escTimer = null
    this.quitArmedAt = null
    this.stopping = false
    this.transcriptTotal = 0
    this.disposers = []
    this.streaming = { text: null, reasoning: null }
    this.deliveredStreams = 0
    this.exitCode = 0
    this.exitPromise = new Promise((resolve) => {
      this.resolveExit = resolve
    })
    this.codec = createKeyDecoder((event) => this.onKeyEvent(event))
  }

  // ── lifecycle ───────────────────────────────────────────────────────────

  /** Boots the terminal and the agent. */
  async start() {
    const args = this.readArgs()
    if (args.help) {
      process.stdout.write(usage())
      this.resolveExit(0)
      return
    }
    this.screen.enter()
    this.screen.resized = () => {
      this.dirty = true
      this.render()
    }
    this.installInput()
    this.installSignalHandlers()
    this.pushBanner()
    this.render()
    this.timer = setInterval(() => this.tick(), TICK_MS)

    this.wireHarnessEvents()
    try {
      const { resumed } = await this.session.open({ resume: args.resume })
      if (this.session.resumeFailure !== undefined) {
        this.pushNotice(`${this.failureText(this.session.resumeFailure)} — starting a new session`, 'error')
      }
      this.syncSelection()
      this.pushNotice(resumed
        ? this.i18n.t('banner.resume', { id: shortId(this.session.sessionId) })
        : this.i18n.t('notice.ready', { version: process.env.HDSL_INSTANCE_VERSION ?? '0.2.x' }), 'info')
      this.status = 'ready'
      this.pumpQueue()
    } catch (error) {
      this.status = 'disconnected'
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
    this.render()
    return this
  }

  /** @private */
  readArgs() {
    let list = []
    try {
      list = this.ctx.get('cmdlineArgs')?.get() ?? []
    } catch {
      list = []
    }
    if (list.length === 0) {
      // Fall back to the process arguments after the profile name; a launcher
      // that did not provide cmdlineArgs still gets --resume working.
      const index = process.argv.indexOf('--profile')
      list = index >= 0 ? process.argv.slice(index + 2) : process.argv.slice(2)
    }
    const result = { resume: null, help: false }
    for (let index = 0; index < list.length; index += 1) {
      const argument = list[index]
      if (argument === '--help' || argument === '-h') result.help = true
      else if (argument === '--resume' || argument === '-r') {
        result.resume = list[index + 1] ?? null
        index += 1
      } else if (argument.startsWith('--resume=')) {
        result.resume = argument.slice('--resume='.length)
      }
    }
    return result
  }

  /** @private */
  installInput() {
    if (typeof process.stdin.setRawMode === 'function') process.stdin.setRawMode(true)
    process.stdin.resume()
    process.stdin.setEncoding('utf8')
    process.stdin.on('data', (chunk) => {
      this.codec.feed(chunk)
      if (this.codec.pending) {
        if (this.escTimer !== null) clearTimeout(this.escTimer)
        this.escTimer = setTimeout(() => {
          this.escTimer = null
          this.codec.flush()
        }, ESC_DELAY_MS)
      }
    })
    process.stdin.on('end', () => this.quit())
  }

  /** @private */
  installSignalHandlers() {
    this.signalHandlers = ['SIGTERM', 'SIGHUP'].map((signal) => {
      const handler = () => {
        void this.quit()
      }
      process.on(signal, handler)
      return [signal, handler]
    })
  }

  /** @private */
  wireHarnessEvents() {
    const ctx = this.ctx
    const mine = (agent) => agent === this.session.live
    this.disposers.push(
      ctx.on('agent/assistant-stream', (payload) => {
        if (mine(payload.agent)) this.onStreamFrame(payload.frame)
      }),
      ctx.on('session/event', (session, event) => {
        if (session === this.session.live?.session) this.onSessionEvent(event)
      }),
      ctx.on('agent/status', (payload) => {
        if (!mine(payload.agent)) return
        this.status = payload.status === 'running' ? 'working' : 'ready'
        if (payload.status === 'running' && this.activity.startedAt === null) {
          this.activity.startedAt = this.clock
        }
        this.dirty = true
      }),
      ctx.on('agent/error', (payload) => {
        if (!mine(payload.agent)) return
        this.debug(payload.error)
        this.pushNotice(this.failureText(payload.error), 'error')
      }),
      ctx.on('approval/request', (request) => this.requestApproval(request)),
      ctx.on('user-questions/request', (request) => this.requestAnswers(request)),
    )
  }

  // ── harness events ──────────────────────────────────────────────────────

  /** @private */
  onStreamFrame(frame) {
    if (frame === null || frame === undefined) return
    if (frame.type === 'end') {
      this.finishStreaming()
      return
    }
    if (frame.type !== 'chunk') return
    const chunk = frame.chunk
    if (chunk === undefined) return
    switch (chunk.type) {
      case 'block-start':
        if (chunk.blockType === 'reasoning') this.startBlock('reasoning')
        else if (chunk.blockType === 'text') this.startBlock('text')
        return
      case 'text-delta': {
        if (chunk.text === '') return
        const block = this.streamBlock('text')
        block.full = `${block.full ?? ''}${chunk.text}`
        block.text = block.full
        this.bump(block)
        return
      }
      case 'reasoning-delta': {
        if (chunk.text === '') return
        const block = this.streamBlock('reasoning')
        block.full = `${block.full ?? ''}${chunk.text}`
        block.text = block.full
        this.bump(block)
        return
      }
      case 'block-end':
        if (chunk.block?.type === 'reasoning') this.finishOne('reasoning')
        else if (chunk.block?.type === 'text') {
          this.finishOne('text')
          this.deliveredStreams += 1
        }
        return
      case 'usage':
        return
      case 'finish':
        this.finishStreaming()
        return
      default:
        // `tool-call-delta` is presentation the durable tool events already give.
        return
    }
  }

  /** @private */
  startBlock(kind) {
    this.streamBlock(kind)
  }

  /** @private */
  streamBlock(kind) {
    const existing = this.streaming[kind]
    if (existing !== null) return existing
    const block = this.makeBlock({
      type: kind === 'reasoning' ? 'thought' : 'assistant',
      full: '',
      text: '',
      streaming: true,
      expanded: false,
    })
    this.streaming[kind] = block
    return block
  }

  /** @private */
  finishOne(kind) {
    const block = this.streaming[kind]
    if (block === null || block === undefined) return
    block.streaming = false
    block.text = block.full ?? block.text
    this.streaming[kind] = null
    this.bump(block)
  }

  /** @private */
  finishStreaming() {
    this.finishOne('text')
    this.finishOne('reasoning')
  }

  /** @private */
  onSessionEvent(event) {
    switch (event.type) {
      case 'turn/start':
        this.status = 'working'
        this.activity = { tool: null, startedAt: this.clock }
        this.turnStartedAt = this.clock
        this.lastTurnSeconds = null
        this.turnEndedAt = undefined
        this.deliveredStreams = 0
        this.dirty = true
        return
      case 'turn/end': {
        this.finishStreaming()
        this.deliveredStreams = 0
        this.status = this.session.busy ? 'working' : 'ready'
        if (this.turnStartedAt !== null) {
          this.lastTurnSeconds = (this.clock - this.turnStartedAt) / 1000
          this.turnEndedAt = this.clock
          this.turnStartedAt = null
        }
        this.activity = { tool: null, startedAt: null }
        this.usage = this.session.usage()
        void this.session.flush()
        this.dirty = true
        this.pumpQueue()
        return
      }
      case 'tool/call': {
        const input = parseArguments(event.data.arguments)
        const block = this.makeBlock({
          type: 'tool',
          callId: event.data.callId,
          name: event.data.name ?? 'tool',
          input,
          status: 'in_progress',
          startedAt: this.clock,
          output: null,
          expanded: false,
        })
        this.activity.tool = block.name
        this.dirty = true
        return
      }
      case 'tool/result': {
        const block = this.findTool(event.data.message?.toolCallId)
        if (block === null) return
        block.output = textOfMessage(event.data.message)
        block.status = event.data.message?.isError === true ? 'failed' : 'completed'
        block.endedAt = this.clock
        this.activity.tool = null
        this.bump(block)
        return
      }
      case 'assistant/message': {
        // A provider that emits no deltas still has to show its answer: one
        // committed message per streamed block means "already on screen".
        if (this.deliveredStreams > 0) {
          this.deliveredStreams -= 1
          return
        }
        const text = textOfMessage(event.data.message)
        if (text.trim() === '') return
        const block = this.makeBlock({ type: 'assistant', text, streaming: false, expanded: false })
        this.bump(block)
        return
      }
      default:
        return
    }
  }

  // ── interactions ────────────────────────────────────────────────────────

  /** @private */
  requestApproval(request) {
    return new Promise((resolve) => {
      if (request.signal?.aborted === true) {
        resolve('cancelled')
        return
      }
      const item = {
        kind: 'approval',
        request,
        settle: (outcome) => {
          request.signal?.removeEventListener('abort', onAbort)
          retire(this, item)
          resolve(outcome)
        },
        render: () => ({
          kind: 'permission',
          title: this.i18n.t('permission.title'),
          toolName: request.toolName,
          detail: request.displayReason ?? request.reason ?? '',
          options: [
            { label: this.i18n.t('permission.allow-once'), value: 'allowed-once' },
            { label: this.i18n.t('permission.reject'), value: 'rejected' },
          ],
          index: 0,
        }),
      }
      const onAbort = () => item.settle('cancelled')
      request.signal?.addEventListener('abort', onAbort, { once: true })
      enqueue(this, item)
    })
  }

  /** @private */
  requestAnswers(request) {
    return new Promise((resolve, reject) => {
      if (request.signal?.aborted === true) {
        reject(new UserQuestionError('ask_user_question was interrupted', 'ASK_ABORTED'))
        return
      }
      const answers = []
      let index = 0
      let active = null
      const fail = () => {
        request.signal?.removeEventListener('abort', fail)
        if (active !== null) retire(this, active)
        reject(new UserQuestionError('ask_user_question was interrupted', 'ASK_ABORTED'))
      }
      request.signal?.addEventListener('abort', fail, { once: true })
      const askNext = () => {
        const question = request.questions[index]
        if (question === undefined) {
          request.signal?.removeEventListener('abort', fail)
          resolve({ answers })
          return
        }
        const options = (question.options ?? []).map((option) => ({
          label: option.label,
          value: option.label,
          description: option.description,
        }))
        const item = {
          kind: 'question',
          settle: (selected) => {
            active = null
            retire(this, item)
            answers.push({ id: question.id, selected: selected === null ? [] : [selected] })
            index += 1
            askNext()
          },
          render: () => ({
            kind: 'permission',
            title: question.header ?? this.i18n.t('question.title'),
            detail: [question.question, question.detail].filter(Boolean).join('\n'),
            options: options.length > 0
              ? options
              : [{ label: this.i18n.t('question.no-options'), value: null }],
            index: 0,
          }),
        }
        active = item
        enqueue(this, item)
      }
      askNext()
    })
  }

  /** @private */
  showNextInteraction() {
    if (this.activeInteraction !== null) return
    const next = this.interactions.shift()
    this.activeInteraction = next ?? null
    if (next === undefined) {
      if (this.overlay?.kind === 'permission') this.overlay = null
      this.dirty = true
      return
    }
    this.overlay = next.render()
    this.dirty = true
    this.render()
  }

  /** @private */
  resolveInteraction(value) {
    const active = this.activeInteraction
    this.activeInteraction = null
    this.overlay = null
    active?.settle(value)
    this.dirty = true
    this.showNextInteraction()
    this.render()
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
      tag: 'dsh-inproc-tui',
      version: `v${VERSION}`,
      subtitle: this.i18n.t('banner.subtitle'),
      hint: this.i18n.t('banner.hint'),
      sessionId: null,
      cwd: this.cwd,
    })
  }

  /** @private */
  pushNotice(text, level = 'info') {
    this.makeBlock({ type: 'notice', text, level })
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

  // ── keys ────────────────────────────────────────────────────────────────

  /** @private */
  onKeyEvent(event) {
    try {
      this.dispatchKey(event)
    } catch (error) {
      this.debug(error)
      this.pushNotice(this.failureText(error), 'error')
      this.render()
    }
  }

  /** @private */
  dispatchKey(event) {
    if (this.overlay !== null) {
      this.handleOverlayKey(event)
      return
    }
    const { name } = event
    if (name === 'char') {
      this.composer.insert(event.text ?? '')
      this.syncCommandPalette()
      this.scrollToBottom()
      return
    }
    if (name === 'enter') {
      if (event.shift || event.alt) {
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
      const width = Math.max(1, this.screen.columns - 4)
      if ((name === 'up' || name === 'down') && !this.composer.text.includes('\n')
        && (name === 'up' || this.composer.historyIndex !== -1)) {
        this.composer.recall(name)
        this.dirty = true
        return
      }
      this.composer.move(name, width)
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
      if (this.composer.empty) void this.quit()
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
      void this.openResumePicker()
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
      void this.quit()
      return
    }
    this.quitArmedAt = now
    this.pushNotice(this.i18n.t('quit.confirm'), 'info')
    this.dirty = true
  }

  /** @private */
  handleOverlayKey(event) {
    const overlay = this.overlay
    if (overlay === null) return
    const { name } = event
    if (name === 'escape') {
      if (overlay.kind === 'permission') this.resolveInteraction(null)
      else this.overlay = null
      this.dirty = true
      return
    }
    if (name === 'ctrl+c') {
      if (overlay.kind === 'permission') this.resolveInteraction(null)
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
      const length = overlay.items?.length ?? overlay.options?.length ?? 0
      if (length === 0) return
      overlay.index = Math.max(0, Math.min(length - 1, (overlay.index ?? 0) + (name === 'page-up' ? -6 : 6)))
      this.dirty = true
      return
    }
    if (name === 'char' && /^[1-9]$/u.test(event.text ?? '')) {
      const index = Number(event.text) - 1
      if (overlay.kind === 'permission') {
        const option = overlay.options?.[index]
        if (option !== undefined) this.resolveInteraction(option.value)
      } else if (overlay.kind === 'picker') {
        const item = overlay.items?.[index]
        if (item !== undefined) this.chooseOverlayItem(item)
      }
      return
    }
    if (name === 'enter') {
      if (overlay.kind === 'permission') {
        const option = overlay.options?.[overlay.index ?? 0]
        this.resolveInteraction(option?.value ?? null)
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
    this.overlay = null
    this.dirty = true
    if (typeof item.onSelect === 'function') item.onSelect()
  }

  // ── commands and turns ──────────────────────────────────────────────────

  /** @private */
  syncCommandPalette() {
    const text = this.composer.text
    if (!text.startsWith('/') || text.includes(' ') || text.includes('\n')) return
    const prefix = text.slice(1).toLowerCase()
    const items = COMMANDS
      .filter((command) => command.name.startsWith(prefix))
      .map((command) => ({
        label: `/${command.name}`,
        description: this.i18n.t(command.key),
        onSelect: () => {
          this.composer.setText('')
          this.runCommand(command.name)
        },
      }))
    if (items.length === 0) {
      this.overlay = null
      return
    }
    this.overlay = {
      kind: 'picker',
      title: 'commands',
      width: 56,
      maxHeight: Math.min(14, items.length + 4),
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
      const [head] = value.trim().slice(1).split(/\s+/u)
      this.runCommand(head)
      return
    }
    this.sendPrompt(value)
  }

  /** @private */
  sendPrompt(text) {
    this.makeBlock({ type: 'user', text })
    this.scrollToBottom()
    if (this.status === 'working' || !this.session.ready || this.session.busy) {
      this.queue.push(text)
      this.dirty = true
      return
    }
    this.deliver(text)
  }

  /** @private */
  deliver(text) {
    try {
      this.session.send(text)
      this.status = 'working'
      this.activity = { tool: null, startedAt: this.clock }
      this.turnStartedAt = this.clock
      this.lastTurnSeconds = null
      this.turnEndedAt = undefined
      this.dirty = true
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
  }

  /** @private */
  pumpQueue() {
    if (this.queue.length === 0 || this.status === 'working' || !this.session.ready) return
    const next = this.queue.shift()
    this.deliver(next)
  }

  /** @private */
  cancelTurn() {
    this.session.cancel()
    this.queue = []
    this.dirty = true
  }

  /** @private */
  runCommand(name) {
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
        void this.openModelPicker()
        return
      case 'effort':
        this.openEffortPicker()
        return
      case 'resume':
        void this.openResumePicker()
        return
      case 'new':
        void this.startNew()
        return
      case 'compact':
        void this.compact()
        return
      case 'quit':
      case 'exit':
        void this.quit()
        return
      default:
        this.pushNotice(this.i18n.t('cmd.unknown', { name }), 'error')
    }
  }

  /** @private */
  openHelp() {
    this.overlay = {
      kind: 'help',
      title: this.i18n.t('help.title'),
      width: 64,
      maxHeight: 24,
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
  async openModelPicker() {
    const catalog = await this.session.modelCatalog()
    const current = this.session.currentModel()
    const items = []
    for (const provider of catalog) {
      for (const model of provider.models) {
        items.push({
          label: model.name,
          group: provider.displayName,
          description: model.name === provider.displayName ? undefined : undefined,
          tail: current?.provider === provider.provider && current?.model === model.id
            ? this.i18n.t('model.current')
            : provider.provider,
          onSelect: () => this.selectModel({ provider: provider.provider, model: model.id }),
        })
      }
    }
    if (items.length === 0) {
      this.pushNotice(this.i18n.t('notice.error') + ': the profile lists no model', 'error')
      return
    }
    this.overlay = { kind: 'picker', title: this.i18n.t('model.title'), width: 62, maxHeight: 20, items, index: 0 }
    this.dirty = true
  }

  /** @private */
  selectModel(selection) {
    this.session.select(selection)
    this.syncSelection()
    this.pushNotice(`${this.i18n.t('model.title')} → ${selection.model}`, 'success')
  }

  /** @private */
  openEffortPicker() {
    const current = this.session.currentModel()?.reasoningEffort
    const items = EFFORTS.map((effort) => ({
      label: this.i18n.t(`effort.${effort.value}`) === `effort.${effort.value}` ? effort.value : this.i18n.t(`effort.${effort.value}`),
      tail: effort.value === current ? this.i18n.t('model.current') : undefined,
      onSelect: () => {
        this.session.select({ reasoningEffort: effort.value })
        this.syncSelection()
        this.pushNotice(`${this.i18n.t('effort.title')} → ${effort.value}`, 'success')
      },
    }))
    this.overlay = { kind: 'picker', title: this.i18n.t('effort.title'), width: 52, maxHeight: 12, items, index: 0 }
    this.dirty = true
  }

  /** @private */
  async openResumePicker() {
    const sessions = await this.session.listSessions()
    const items = sessions.slice(0, 40).map((session) => ({
      label: shortId(session.id),
      tail: session.cwd === this.cwd ? this.i18n.t('resume.here') : session.cwd,
      onSelect: () => void this.resumeSession(session.id),
    }))
    if (items.length === 0) {
      this.pushNotice(this.i18n.t('resume.empty'), 'info')
      return
    }
    this.overlay = {
      kind: 'picker',
      title: this.i18n.t('resume.title'),
      width: 66,
      maxHeight: 20,
      items,
      index: 0,
      emptyText: this.i18n.t('resume.empty'),
      hint: this.i18n.t('resume.hint'),
    }
    this.dirty = true
  }

  /** @private */
  async resumeSession(sessionId) {
    try {
      this.blocks = this.blocks.filter((block) => block.type === 'banner')
      this.transcriptCache = new WeakMap()
      await this.session.resume(sessionId)
      this.syncSelection()
      this.pushNotice(this.i18n.t('banner.resume', { id: shortId(sessionId) }), 'info')
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
  }

  /** @private */
  async startNew() {
    try {
      this.blocks = []
      this.transcriptCache = new WeakMap()
      await this.session.close()
      await this.session.open({})
      this.syncSelection()
      this.pushNotice(this.i18n.t('banner.new'), 'success')
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
    this.dirty = true
  }

  /** @private */
  async compact() {
    try {
      const available = await this.session.compact()
      if (!available) {
        this.pushNotice(this.i18n.t('cmd.unknown', { name: 'compact' }), 'error')
        return
      }
      this.pushNotice(this.i18n.t('notice.compacted'), 'success')
      this.usage = this.session.usage()
      this.dirty = true
    } catch (error) {
      this.pushNotice(this.failureText(error), 'error')
    }
  }

  /** @private */
  syncSelection() {
    const current = this.session.currentModel()
    this.model = {
      current: current?.model ?? null,
      currentLabel: current?.model ?? null,
      options: [],
    }
    this.effort = { current: current?.reasoningEffort ?? null, options: [] }
    this.usage = this.session.usage()
    this.dirty = true
  }

  // ── ticks, scrolling, painting ──────────────────────────────────────────

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
    if (this.status === 'working') this.dirty = true
    // The harness shares this process and may write a log line to stderr at any
    // moment (a provider catalogue refresh does exactly that). Repaint every row
    // once a second so such a line cannot sit on top of the interface: a paint
    // that only diffs against the previous frame would keep it there.
    if (this.clock - (this.lastFullPaint ?? 0) > 1000) {
      this.lastFullPaint = this.clock
      this.screen.previous = []
      this.dirty = true
    }
    if (this.dirty) this.render()
  }

  /** Paints one frame. */
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
  debug(error) {
    if (this.env.HSDL_UI_DEBUG === undefined) return
    process.stderr.write(`dsh-inproc-tui: ${error?.stack ?? error}\n`)
  }

  /** @private */
  failureText(error) {
    if (error === null || error === undefined) return this.i18n.t('error.acp', { message: 'unknown' })
    return this.i18n.t('error.acp', { message: error.message ?? String(error) })
  }

  // ── exit ────────────────────────────────────────────────────────────────

  /** Restores the terminal and asks the launcher to end this process. */
  async quit() {
    if (this.stopping) return
    this.stopping = true
    if (this.timer !== null) clearInterval(this.timer)
    this.timer = null
    if (this.escTimer !== null) clearTimeout(this.escTimer)
    this.escTimer = null
    for (const dispose of this.disposers) {
      try {
        dispose()
      } catch {
        // A disposed plugin scope may already be gone.
      }
    }
    this.screen.leave()
    try {
      await this.session.close()
    } catch {
      // Leaving matters more than a tidy detach.
    }
    if (typeof process.stdin.setRawMode === 'function') {
      try {
        process.stdin.setRawMode(false)
      } catch {
        // the PTY may already be gone
      }
    }
    process.stdin.removeAllListeners('data')
    process.stdin.pause()
    for (const [signal, handler] of this.signalHandlers ?? []) process.removeListener(signal, handler)
    this.resolveExit(this.exitCode)
    const exit = this.ctx.get('appExit')
    if (typeof exit === 'function') exit(this.exitCode)
    else process.exitCode = this.exitCode
  }

  /** Resolves after the terminal has been restored. */
  waitForExit() {
    return this.exitPromise
  }
}

/** Queues one interaction and shows it when the overlay is free. */
function enqueue(app, item) {
  app.interactions.push(item)
  app.showNextInteraction()
}

/** Drops an interaction that is still queued, or advances past the active one. */
function retire(app, item) {
  const index = app.interactions.indexOf(item)
  if (index !== -1) {
    app.interactions.splice(index, 1)
    return
  }
  if (app.activeInteraction === item) {
    app.activeInteraction = null
    app.showNextInteraction()
  }
}

function parseArguments(value) {
  if (typeof value !== 'string') return value ?? null
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

function textOfMessage(message) {
  if (message === undefined || message === null) return ''
  const content = message.content
  if (!Array.isArray(content)) return ''
  return content
    .map((block) => {
      if (block?.type === 'text') return String(block.text ?? '')
      if (block?.type === 'reasoning') return ''
      return ''
    })
    .join('')
}

/** Reads the checked-out branch name, for the status row. */
function detectGitBranch(cwd) {
  let directory = cwd
  for (let depth = 0; depth < 12; depth += 1) {
    try {
      const text = readFileSync(`${directory}/.git/HEAD`, 'utf8').trim()
      const match = /^ref:\s*refs\/heads\/(.+)$/u.exec(text)
      return match === null ? text.slice(0, 7) : match[1]
    } catch {
      const parent = directory.replace(/\/[^/]*$/u, '')
      if (parent === directory || parent === '') return ''
      directory = parent
    }
  }
  return ''
}

/** The `--help` text this profile prints. */
export function usage() {
  return `dsh-inproc-tui ${VERSION} — HDSL-web's terminal interface for DeepSeek Harness 0.2.x

Usage:
  dsh --profile dsh-inproc-tui                 start a new session in the current directory
  dsh --profile dsh-inproc-tui --resume <id>   resume a stored session
  dsh --profile dsh-inproc-tui --help          print this help

The panel starts this for you (instance terminal). It runs in-process over
dsh-base, so it needs a 0.2.x harness: the agent, streaming, approval and
question services are the harness's own.
`
}
