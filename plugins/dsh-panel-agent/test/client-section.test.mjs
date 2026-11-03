import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Loads the browser bundle the way the client loader does — one
 * `__ModuleLoader__.load({id, factory})` call — and returns its exports.
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
    if (id === 'react') return fakeReact()
    throw new Error(`unexpected require(${id})`)
  }
  return captured[0].factory(require)
}

/**
 * The smallest React that can run one mount: `createElement` records a tree, the
 * hooks hand back their initial value, effects do not run.
 *
 * `react/jsx-runtime` is **not** offered on purpose. Its `jsx(type, props, key)`
 * reads the third argument as the key, so a component written against it drops
 * every text child and renders an empty card — that shipped once. If the bundle
 * reaches for it again, the require throws and the test says why.
 */
function fakeReact() {
  return {
    createElement(type, props, ...children) {
      return {
        type,
        props: { ...(props ?? {}), children: children.length > 0 ? children : props?.children },
      }
    },
    useState(initial) {
      return [initial, () => {}]
    },
    useCallback(fn) {
      return fn
    },
    useEffect() {},
    useLayoutEffect() {},
    useRef(value) {
      return { current: value }
    },
  }
}

/** Calls function components until only host elements and strings are left. */
function render(node) {
  if (node === null || node === undefined || node === false || node === true) return null
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(render).filter(Boolean).join('')
  if (typeof node.type === 'function') return render(node.type(node.props))
  const children = node.props?.children
  if (children === undefined) return ''
  return Array.isArray(children) ? children.map(render).filter(Boolean).join('') : render(children)
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

test('the page actually renders its fields, not an empty card', () => {
  const module = loadBundle()
  let component = null
  module.apply({
    slots: {
      inject: (_slot, register) => register(),
      register: (_descriptor, render_) => {
        component = render_
        return () => {}
      },
    },
    connection: { rpc: { call: async () => ({ ok: true, value: {} }) } },
  })

  const text = render(component({}))
  // The card that shipped rendered nothing: every string below was passed as a
  // third argument to `jsx`, which treated it as a key.
  assert.match(text, /hdsl-web 面板管理/, text.slice(0, 200))
  assert.match(text, /agent 密码/)
  assert.match(text, /面板地址/)
  assert.match(text, /获取授权/)
  assert.match(text, /读取面板/)
})
