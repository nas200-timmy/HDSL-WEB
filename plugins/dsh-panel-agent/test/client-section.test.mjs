import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Loads the browser bundle the way the client loader does — one
 * `__ModuleLoader__.load({id, factory})` call — and returns its exports.
 *
 * React is stubbed down to the two hooks the page uses at mount time, because
 * this test is about **registration**, not about rendering: the thing that can
 * silently break is `slots.register` never being reached, or the section being
 * registered under the wrong slot name. A full render belongs to the browser.
 */
function loadBundle() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..')
  const source = readFileSync(join(root, 'lib', 'client.js'), 'utf8')
  const captured = []
  globalThis.window = { __ModuleLoader__: { load: (registration) => captured.push(registration) } }
  // eslint-disable-next-line no-new-func
  new Function(source)()
  assert.equal(captured.length, 1, 'the bundle must register exactly one module')
  const require = (id) => {
    if (id === 'react') {
      return {
        createElement: () => null,
        useState: (value) => [value, () => {}],
        useCallback: (fn) => fn,
        useEffect: () => {},
      }
    }
    if (id === 'react/jsx-runtime') {
      return { jsx: () => null, jsxs: () => null }
    }
    throw new Error(`unexpected require(${id})`)
  }
  return captured[0].factory(require)
}

test('the bundle registers a settings section under a stable id', () => {
  const module = loadBundle()
  assert.equal(module.name, 'dsh-panel-agent-client')
  assert.deepEqual(module.inject, ['connection', 'slots'])

  const registered = []
  const ctx = {
    slots: {
      inject: (slot, register) => {
        assert.equal(slot, 'settings.section')
        register()
      },
      register: (descriptor, component) => {
        registered.push({ descriptor, component })
        return () => {}
      },
    },
    connection: { rpc: { call: async () => ({ ok: true, value: {} }) } },
  }
  module.apply(ctx)

  assert.equal(registered.length, 1)
  assert.equal(registered[0].descriptor.name, 'settings.section')
  assert.equal(registered[0].descriptor.id, 'hdsl-panel-agent')
  assert.equal(typeof registered[0].component, 'function')
  assert.equal(registered[0].descriptor.label(), 'hdsl-web 面板')
})
