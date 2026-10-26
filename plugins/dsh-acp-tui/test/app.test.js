/**
 * Application-state tests.
 *
 * The app is driven by its own event methods rather than by a terminal: paste
 * goes through `onKeyEvent`, permissions through `onRequest`, child death through
 * `onExit`, and `/new` through `startNewSession` with a stub client. One test
 * spawns a real (exiting) child to prove the startup-failure route releases the
 * terminal.
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough, Writable } from 'node:stream'

import { App } from '../src/ui/app.js'

/** Rejects if `promise` has not settled within `ms`; a hung regression must fail, not hang. */
function deadline(promise, ms = 1500, label = 'timed out') {
  let timer = null
  const guard = new Promise((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error(label)), ms)
  })
  return Promise.race([promise, guard]).finally(() => clearTimeout(timer))
}

/** A terminal-width stdout that keeps what was written. */
function outputSink() {
  const chunks = []
  const out = new Writable({
    write(chunk, _encoding, callback) {
      chunks.push(chunk.toString())
      callback()
    },
  })
  out.columns = 80
  out.rows = 24
  out.text = () => chunks.join('')
  return out
}

/** A stdin stand-in that records raw-mode changes instead of touching a TTY. */
function inputSource() {
  const stdin = new PassThrough()
  stdin.isTTY = true
  stdin.rawModes = []
  stdin.setRawMode = (value) => {
    stdin.rawModes.push(value)
  }
  return stdin
}

/** An `App` that has not been started; the test decides what `client` and `harness` are. */
function makeApp() {
  const stdout = outputSink()
  const stdin = inputSource()
  const app = new App({
    env: { HSDL_TUI_LANG: 'en' },
    stdout,
    stdin,
    cwd: process.cwd(),
    harness: { command: ['dsh'], source: 'test', profile: 'acp' },
    resume: null,
  })
  return { app, stdout, stdin }
}

test('paste inserts at the caret and normalises CRLF', () => {
  const { app } = makeApp()
  app.composer.setText('hello world')
  app.composer.caret = 5
  app.onKeyEvent({ name: 'paste', text: 'a\r\nb\rc' })
  assert.equal(app.composer.text, 'helloa\nb\nc world')
  assert.equal(app.composer.caret, 'helloa\nb\nc'.length)
  assert.equal(app.dirty, true)
  assert.equal(app.scroll.follow, true)
})

test('a paste while a dialog is open leaves the composer untouched', () => {
  const { app } = makeApp()
  app.composer.setText('/he')
  const overlay = { kind: 'picker', items: [{ label: '/help' }], index: 0 }
  app.overlay = overlay
  app.onKeyEvent({ name: 'paste', text: 'lp\r\nme' })
  assert.equal(app.composer.text, '/he')
  assert.equal(app.composer.caret, 3)
  assert.equal(app.overlay, overlay)
})

test('the command palette follows the text instead of swallowing it', () => {
  const { app } = makeApp()
  app.onKeyEvent({ name: 'char', text: '/' })
  assert.equal(app.overlay?.live, true, 'the palette says it leaves the line editable')
  app.onKeyEvent({ name: 'char', text: 'h' })
  app.onKeyEvent({ name: 'char', text: 'e' })
  assert.equal(app.composer.text, '/he', 'the letters typed after / reach the composer')
  assert.deepEqual(app.overlay.items.map((item) => item.label), ['/help'])

  // The same palette still navigates: the arrows move the selection, they do
  // not move the caret.
  app.overlay.items = [{ label: '/help' }, { label: '/hello' }]
  app.onKeyEvent({ name: 'down' })
  assert.equal(app.overlay.index, 1)
  assert.equal(app.composer.caret, 3)

  app.onKeyEvent({ name: 'backspace' })
  assert.equal(app.composer.text, '/h')

  // A paste is text too, and a space ends the command prefix.
  app.onKeyEvent({ name: 'paste', text: 'elp ' })
  assert.equal(app.composer.text, '/help ')
  assert.equal(app.overlay, null, 'the palette closes once the line is no longer a command')

  // Nothing matches `/hx`, so the palette closes and typing simply continues.
  app.composer.setText('/hx')
  app.onKeyEvent({ name: 'char', text: 'y' })
  assert.equal(app.overlay, null)
  assert.equal(app.composer.text, '/hxy')
})

test('ctrl+enter is a line break, not a send', () => {
  const { app } = makeApp()
  app.composer.setText('first')
  // A raw newline arrives as enter+ctrl: `\n` is a control byte, not a key name
  // every terminal agrees on.
  app.onKeyEvent({ name: 'enter', ctrl: true })
  assert.equal(app.composer.text, 'first\n')
  assert.equal(app.blocks.filter((block) => block.type === 'user').length, 0)
  app.onKeyEvent({ name: 'enter', shift: true })
  assert.equal(app.composer.text, 'first\n\n')
})

test('an update from the session that was replaced is ignored', () => {
  const { app } = makeApp()
  app.sessionId = 'session-new'
  app.status = 'ready'
  const chunk = (sessionId, text) => ({
    method: 'session/update',
    params: { sessionId, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text } } },
  })
  app.onNotification(chunk('session-old', 'late answer'))
  assert.equal(app.blocks.filter((block) => block.type === 'assistant').length, 0)
  app.onNotification(chunk('session-new', 'current answer'))
  assert.equal(app.blocks.filter((block) => block.type === 'assistant').length, 1)
})

test('/new cancels the running turn before it swaps the session', async () => {
  const { app } = makeApp()
  const calls = []
  app.client = {
    sessions: { close: true },
    request(method) {
      calls.push(method)
      if (method === 'session/new') return Promise.resolve({ sessionId: 'session-next', configOptions: [] })
      return Promise.resolve({})
    },
    notify(method) {
      calls.push(method)
    },
    respond() {},
  }
  app.sessionId = 'session-live'
  app.status = 'working'
  app.queue = ['a queued prompt']
  await app.startNewSession()
  assert.deepEqual(calls.slice(0, 3), ['session/cancel', 'session/close', 'session/new'])
  assert.deepEqual(app.queue, [], 'the queue belonged to the session that was left')
  assert.equal(app.sessionId, 'session-next')
})

test('adoptSession tolerates an empty transcript', () => {
  const { app } = makeApp()
  app.blocks = []
  assert.doesNotThrow(() => app.adoptSession({ sessionId: 'session-empty', configOptions: [] }, 'session-empty'))
  assert.equal(app.sessionId, 'session-empty')
  assert.equal(app.blocks.length, 0)
})

test('/new with an empty transcript creates the session without a bogus error', async () => {
  const { app } = makeApp()
  const calls = []
  app.client = {
    sessions: { close: true },
    request(method) {
      calls.push(method)
      if (method === 'session/new') {
        return Promise.resolve({ sessionId: 'session-new', configOptions: [] })
      }
      return Promise.resolve({})
    },
    respond() {},
  }
  app.sessionId = 'session-old'
  app.blocks = []
  await app.startNewSession()
  assert.equal(app.sessionId, 'session-new')
  assert.deepEqual(calls, ['session/close', 'session/new'])
  assert.equal(app.blocks[0].type, 'banner')
  assert.equal(app.blocks[0].sessionId, 'session-new')
  assert.deepEqual(
    app.blocks.filter((block) => block.type === 'notice' && block.level === 'error'),
    [],
  )
})

test('a second permission request waits its turn instead of replacing the first', () => {
  const { app } = makeApp()
  const answered = []
  app.client = {
    respond(id, outcome) {
      answered.push({ id, outcome })
    },
  }
  const request = (id) => ({
    id,
    method: 'session/request_permission',
    params: {
      toolCall: { title: `tool-${id}` },
      options: [{ optionId: `allow-${id}`, kind: 'allow_once' }],
    },
  })

  app.onRequest(request(1))
  app.onRequest(request(2))
  assert.equal(app.pendingPermission.id, 1, 'the first request keeps the dialog')
  assert.equal(app.overlay.toolName, 'tool-1')
  assert.equal(answered.length, 0)

  app.answerPermission({ value: 'allow-1' })
  assert.deepEqual(answered, [
    { id: 1, outcome: { result: { outcome: { outcome: 'selected', optionId: 'allow-1' } } } },
  ])
  assert.equal(app.pendingPermission.id, 2, 'the queued request is shown next')
  assert.equal(app.overlay.toolName, 'tool-2')

  app.answerPermission({ value: 'allow-2' })
  assert.equal(answered.length, 2)
  assert.equal(answered[1].id, 2)
  assert.equal(app.pendingPermission, null)
  assert.equal(app.overlay, null)
})

test('a protocol error becomes one notice, not a flood', () => {
  const { app } = makeApp()
  app.onProtocolError(new Error('the harness wrote a line that is not JSON'))
  app.onProtocolError(new Error('another bad line'))
  const notices = app.blocks.filter((block) => block.type === 'notice')
  assert.equal(notices.length, 1)
  assert.equal(notices[0].level, 'error')
  assert.match(notices[0].text, /not JSON/u)
})

test('stop restores the terminal, clears timers and runs once', async () => {
  const { app, stdout, stdin } = makeApp()
  let closes = 0
  app.client = {
    close: async () => {
      closes += 1
    },
  }
  app.screen.enter()
  app.installInput()
  app.timer = setInterval(() => {}, 1000)
  app.escTimer = setTimeout(() => {}, 1000)
  app.sessionId = 'session-1'
  const exited = app.waitForExit()

  await app.stop('quit')
  await app.stop('quit')

  assert.equal(closes, 1, 'the child is closed once')
  assert.equal(app.timer, null)
  assert.equal(app.escTimer, null)
  assert.equal(app.stopped, true)
  assert.equal(stdin.listenerCount('data'), 0)
  assert.equal(stdin.listenerCount('end'), 0)
  assert.deepEqual(stdin.rawModes, [true, false])
  assert.ok(stdout.text().includes('\x1b[?1049l'), 'the alternate screen is left')
  assert.equal(stdout.text().match(/session session-1/gu)?.length, 1, 'the resume hint is printed once')
  assert.equal(await deadline(exited), 0)
})

test('stop still resolves the exit when the child cannot be closed', async () => {
  const { app } = makeApp()
  app.client = {
    close: async () => {
      throw new Error('close failed')
    },
  }
  app.timer = setInterval(() => {}, 1000)
  const exited = app.waitForExit()

  await app.stop('quit')

  assert.equal(app.timer, null)
  assert.equal(await deadline(exited), 0)
})

test('the child dying exits the terminal instead of leaving it running', async () => {
  const { app } = makeApp()
  app.status = 'ready'
  app.client = { close: async () => {}, stderrLines: ['boom'] }
  app.screen.enter()
  app.timer = setInterval(() => {}, 1000)
  app.sessionId = 'session-live'
  const exited = app.waitForExit()

  app.onExit({ code: 7, signal: null })

  assert.equal(await deadline(exited), 7)
  assert.equal(app.timer, null)
  assert.equal(app.status, 'disconnected')
  assert.equal(app.stopped, true)
})

test('a failed handshake releases the terminal and exits with an error', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-acp-tui-'))
  const script = join(directory, 'harness.mjs')
  // The terminal appends `--profile acp`, so the stand-in harness is a file, the
  // way the real `lib/bin.js` is; it simply exits without speaking ACP.
  writeFileSync(script, 'process.exit(3)\n')
  const { app, stdout, stdin } = makeApp()
  app.harness = { command: [process.execPath, script], source: 'test', profile: 'acp' }
  try {
    await assert.rejects(deadline(app.start(), 5000), /harness|exited|running/u)
    assert.equal(app.timer, null)
    assert.equal(app.stopped, true)
    assert.equal(await deadline(app.waitForExit(), 1000), 1)
    assert.deepEqual(stdin.rawModes, [true, false])
    assert.ok(stdout.text().includes('\x1b[?1049l'), 'the alternate screen is left on failure')
  } finally {
    await app.stop('test')
    rmSync(directory, { recursive: true, force: true })
  }
})
