/**
 * Behaviour tests for the in-process terminal's state machine.
 *
 * The terminal loop needs a 0.2.x harness *in the process*, so it cannot be
 * driven end to end here. What can be driven is everything above that: the
 * composer behind the command palette, the keys that edit it, the transcript a
 * resume rebuilds, and the order a session switch does its work in. That is
 * exactly the layer the defects were in — the harness is only where the events
 * come from, and the tests feed those in directly.
 *
 * `Screen` is replaced so nothing is written to the test runner's stdout.
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { ChatSession } from '../src/agent.js'
import { TerminalApp } from '../src/app.js'

/** A terminal app with a screen that writes nowhere and a session the test supplies. */
function makeApp() {
  const app = new TerminalApp({ get: () => undefined })
  app.screen = { out: null, columns: 80, rows: 24, clear() {}, enter() {}, leave() {} }
  app.render = () => {}
  return app
}

/** The session stand-in the terminal talks to; `calls` is the order it was used in. */
function fakeSession(overrides = {}) {
  const calls = []
  const session = {
    calls,
    ready: true,
    busy: false,
    resumeFailure: undefined,
    cancel() {
      calls.push('cancel')
    },
    async settle() {
      calls.push('settle')
      return true
    },
    async close() {
      calls.push('close')
    },
    async open() {
      calls.push('open')
    },
    async resume(id) {
      calls.push(`resume:${id}`)
      return { resumed: true }
    },
    currentModel: () => null,
    usage: () => ({ used: 0, size: 0 }),
    history: () => [],
    ...overrides,
  }
  return session
}

const text = (value) => ({ type: 'text', text: value })

test('a bracketed paste reaches the composer, one event and CRLF normalised', () => {
  const app = makeApp()
  app.onKeyEvent({ name: 'paste', text: '第一行\r\n第二行' })
  assert.equal(app.composer.text, '第一行\n第二行')
  assert.equal(app.blocks.filter((block) => block.type === 'notice' && block.level === 'error').length, 0)
})

test('typing after a slash keeps the line, and the palette follows it', () => {
  const app = makeApp()
  for (const letter of ['/', 'h', 'e']) app.onKeyEvent({ name: 'char', text: letter })
  assert.equal(app.composer.text, '/he')
  assert.equal(app.overlay?.live, true)
  assert.deepEqual(app.overlay.items.map((item) => item.label), ['/help'])

  app.onKeyEvent({ name: 'backspace' })
  assert.equal(app.composer.text, '/h')
  app.onKeyEvent({ name: 'char', text: 'x' })
  assert.equal(app.composer.text, '/hx', 'a prefix nothing matches still reaches the line')
  assert.equal(app.overlay, null, 'and the palette gets out of the way')
})

test('ctrl+enter is a line break rather than a send', () => {
  const app = makeApp()
  app.session = fakeSession()
  app.composer.setText('first')
  // A raw newline is decoded as enter+ctrl: `\n` is a control byte, not a key
  // name every terminal agrees on.
  app.onKeyEvent({ name: 'enter', ctrl: true })
  assert.equal(app.composer.text, 'first\n')
  assert.equal(app.blocks.filter((block) => block.type === 'user').length, 0)
})

test('a tool card leaves the expansion decision to the global toggle', () => {
  const app = makeApp()
  app.onSessionEvent({ type: 'tool/call', data: { callId: 'c1', name: 'read', arguments: '{"path":"a"}' } })
  const block = app.blocks.find((candidate) => candidate.type === 'tool')
  assert.equal(block.expanded, undefined, 'a card that pinned itself closed would ignore ctrl+o')
  assert.equal(app.expandTools, false)
  app.onKeyEvent({ name: 'ctrl+o' })
  assert.equal(app.expandTools, true)
})

test('a resumed conversation is rebuilt from its session log', () => {
  const app = makeApp()
  app.replayHistory([
    { type: 'user/message', data: { source: { kind: 'user' }, content: [text('what is here')] } },
    { type: 'assistant/message', data: { message: { content: [text('one file')] } } },
    { type: 'tool/call', data: { callId: 'c1', name: 'read', arguments: '{"path":"a.txt"}' } },
    { type: 'tool/result', data: { message: { toolCallId: 'c1', content: [text('file body')], isError: false } } },
  ])
  assert.deepEqual(app.blocks.map((block) => block.type), ['user', 'assistant', 'tool'])
  assert.equal(app.blocks[0].text, 'what is here')
  assert.equal(app.blocks[1].text, 'one file')
  const tool = app.blocks[2]
  assert.equal(tool.output, 'file body')
  assert.equal(tool.status, 'completed')
})

test('/resume reports a refused id instead of claiming it resumed', async () => {
  const app = makeApp()
  app.session = fakeSession({
    async resume(id) {
      app.session.resumeFailure = new Error('no such session')
      return { resumed: false }
    },
  })
  await app.resumeSession('nope')
  const errors = app.blocks.filter((block) => block.type === 'notice' && block.level === 'error')
  assert.equal(errors.length, 1)
  assert.match(errors[0].text, /no such session/u)
  assert.equal(app.blocks.filter((block) => block.type === 'banner').length, 1, 'a fresh banner, not a resumed one')
})

test('/resume puts the resumed transcript on screen', async () => {
  const app = makeApp()
  app.session = fakeSession({
    history: () => [
      { type: 'user/message', data: { source: { kind: 'user' }, content: [text('earlier question')] } },
      { type: 'assistant/message', data: { message: { content: [text('earlier answer')] } } },
    ],
  })
  await app.resumeSession('session-old')
  assert.deepEqual(app.session.calls, ['resume:session-old'])
  assert.deepEqual(app.blocks.filter((block) => block.type === 'user').map((block) => block.text), ['earlier question'])
  assert.deepEqual(app.blocks.filter((block) => block.type === 'assistant').map((block) => block.text), ['earlier answer'])
})

test('/new ends the turn in flight before it swaps the agent', async () => {
  const app = makeApp()
  app.session = fakeSession({ busy: true })
  app.status = 'working'
  app.queue = ['a queued prompt']
  await app.startNew()
  assert.deepEqual(app.session.calls, ['cancel', 'settle', 'close', 'open'])
  assert.deepEqual(app.queue, [], 'the queue belonged to the session that was left')
  assert.equal(app.status, 'ready')
})

test('a quiet session is not cancelled on the way to a new one', async () => {
  const app = makeApp()
  app.session = fakeSession()
  app.status = 'ready'
  await app.startNew()
  assert.deepEqual(app.session.calls, ['close', 'open'], 'nothing to cancel means nothing to wait for')
})

test('/model writes what the next step assembles with, not just the log', () => {
  const session = new ChatSession({ get: () => undefined }, '/workspace')
  const appended = []
  session.agent = {
    session: {
      id: 'session-x',
      append(type, data) {
        appended.push([type, data])
      },
      snapshotEvents: () => [
        { type: 'model/selection', data: { provider: 'acme', model: 'one', reasoningEffort: 'high' } },
      ],
    },
  }
  session.modelState = { current: { provider: 'acme', model: 'one', reasoningEffort: 'high' }, assembled: undefined }

  session.select({ model: 'two' })

  const expected = { provider: 'acme', model: 'two', reasoningEffort: 'high' }
  assert.deepEqual(session.modelState.current, expected, 'the harness reads this on its next step')
  assert.deepEqual(appended, [['model/selection', expected]], 'and the log still says the same thing')
})
