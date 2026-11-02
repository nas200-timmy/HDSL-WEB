import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'

import { PanelClient } from '../src/client.js'

/** Starts a stub panel; `handler` sees every request and answers what it likes. */
async function stub(handler) {
  const server = createServer((req, res) => handler(req, res))
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address()
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  }
}

test('a call carries the grant and returns the JSON body', async () => {
  const seen = []
  const panel = await stub((req, res) => {
    seen.push({ method: req.method, url: req.url, auth: req.headers.authorization })
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({ instances: [] }))
  })
  try {
    const client = new PanelClient({ baseUrl: panel.baseUrl, token: () => 'GRANT' })
    const result = await client.call('GET', '/api/mc/instances')
    assert.deepEqual(result, { ok: true, value: { instances: [] } })
    assert.equal(seen[0].auth, 'Bearer GRANT')
  } finally {
    await panel.close()
  }
})

test('an empty body still carries a length, because the panel refuses chunked bodies', async () => {
  let length = null
  const panel = await stub((req, res) => {
    length = req.headers['content-length']
    res.end('{"ok":true}')
  })
  try {
    const client = new PanelClient({ baseUrl: panel.baseUrl, token: () => 'GRANT' })
    await client.call('POST', '/api/mc/instances/x/start')
    assert.equal(length, '0')
  } finally {
    await panel.close()
  }
})

test('a missing grant is an answer, not a throw', async () => {
  const client = new PanelClient({ baseUrl: 'http://127.0.0.1:1', token: () => null })
  const result = await client.call('GET', '/api/instances')
  assert.equal(result.ok, false)
  assert.equal(result.error, 'panel_not_authorized')
  // The hint is the part that keeps the model from starting its own process.
  assert.match(result.detail, /corrupts the panel's state/)
})

test('a rejection keeps its code so the model can act on it', async () => {
  const cases = [
    [409, 'self_operation_refused: an agent cannot stop its own instance', 'self_operation_refused'],
    [403, 'agent_scope_denied: mc', 'agent_scope_denied'],
    [401, 'unauthorized', 'panel_not_authorized'],
  ]
  for (const [status, body, expected] of cases) {
    const panel = await stub((_req, res) => {
      res.statusCode = status
      res.end(JSON.stringify({ error: body }))
    })
    try {
      const client = new PanelClient({ baseUrl: panel.baseUrl, token: () => 'GRANT' })
      const result = await client.call('POST', '/api/mc/instances/x/stop')
      assert.equal(result.ok, false)
      assert.equal(result.error, expected)
    } finally {
      await panel.close()
    }
  }
})

test('a non-api path is refused before it reaches the network', async () => {
  const client = new PanelClient({ baseUrl: 'http://127.0.0.1:1', token: () => 'GRANT' })
  const result = await client.call('GET', '/i/instance/')
  assert.equal(result.error, 'bad_path')
})

test('an unreachable panel does not throw into the tool loop', async () => {
  const client = new PanelClient({ baseUrl: 'http://127.0.0.1:1', token: () => 'GRANT' })
  const result = await client.call('GET', '/api/instances')
  assert.equal(result.ok, false)
  assert.equal(result.error, 'panel_unreachable')
})
