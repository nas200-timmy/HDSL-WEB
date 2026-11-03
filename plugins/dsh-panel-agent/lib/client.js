/**
 * The browser half of dsh-panel-agent: the settings page where the operator
 * hands the panel's agent password to this instance.
 *
 * Written as a hand-rolled module in the client loader's format rather than
 * compiled from JSX, because this repository builds plugins with Gradle and no
 * npm — a build step here would break the "offline, no dependencies" promise
 * the other first-party plugins keep.
 *
 * The password never goes to the model and never touches disk: it is posted to
 * the host half over Connection RPC, exchanged there for a scoped grant, and
 * dropped. What the page shows afterwards is only the grant's expiry.
 *
 * @module client
 */

window.__ModuleLoader__.load({
  id: '@nas200-timmy/dsh-panel-agent',
  factory: (require) => {
    const React = require('react')
    const module = { exports: {} }
    const exports = module.exports

    // `react/jsx-runtime`'s `jsx(type, props, key)` takes its third argument as the
    // **key**, not as children — every text node passed that way vanishes silently, and
    // the page renders as an empty card (this is exactly what shipped once).
    // `createElement` takes varargs children and has no such trap.
    const h = React.createElement
    const hf = React.createElement

    const SCOPES = [
      ['read', '读取面板', '实例、日志、任务列表'],
      ['mc', 'Minecraft', '启停服务器、控制台命令'],
      ['instances', '实例', '创建、删除、启停其它实例'],
      ['config', '配置', '账号、模型、TLS、网络'],
      ['plugins', '插件', '装/卸插件与整合包'],
    ]

    const card = {
      border: '1px solid var(--dsh-outline, rgba(128,128,128,.3))',
      borderRadius: 10,
      padding: 16,
      display: 'flex',
      flexDirection: 'column',
      gap: 10,
      maxWidth: 720,
    }
    const label = { fontSize: 12, opacity: 0.7, minWidth: 96 }
    const row = { display: 'flex', alignItems: 'center', gap: 12 }
    const input = {
      flex: 1,
      padding: '6px 10px',
      borderRadius: 8,
      border: '1px solid var(--dsh-outline, rgba(128,128,128,.4))',
      background: 'transparent',
      color: 'inherit',
      font: 'inherit',
    }

    /**
     * The page. `call` is the host half's RPC endpoint, bound at mount time.
     *
     * @param {{call: (endpoint: string, payload?: unknown) => Promise<any>}} props
     */
    function PanelAgentSection({ call }) {
      const [state, setState] = React.useState({ loading: true })
      const [panelUrl, setPanelUrl] = React.useState('https://127.0.0.1:3080')
      const [password, setPassword] = React.useState('')
      const [scopes, setScopes] = React.useState(['read'])
      const [busy, setBusy] = React.useState(false)
      const [error, setError] = React.useState(null)

      const refresh = React.useCallback(async () => {
        try {
          const answer = await call('status')
          setState(answer.ok ? answer.value : { loading: false })
          if (answer.ok && answer.value.panelUrl) setPanelUrl(answer.value.panelUrl)
          if (answer.ok && answer.value.scopes) setScopes(answer.value.scopes)
        } catch (e) {
          setState({ loading: false })
        }
      }, [call])

      React.useEffect(() => {
        void refresh()
      }, [refresh])

      const toggle = (scope) => {
        setScopes((previous) =>
          previous.includes(scope) ? previous.filter((item) => item !== scope) : [...previous, scope],
        )
      }

      const mint = async () => {
        if (!password) return setError('请输入面板的 agent 密码')
        setBusy(true)
        setError(null)
        try {
          const answer = await call('mint', { panelUrl, password, scopes })
          setPassword('')
          if (!answer.ok) {
            setError(answer.error?.message ?? '授权失败')
          }
          await refresh()
        } finally {
          setBusy(false)
        }
      }

      const clear = async () => {
        setBusy(true)
        setError(null)
        try {
          await call('clear')
          await refresh()
        } finally {
          setBusy(false)
        }
      }

      const remaining = state.expiresAt
        ? Math.max(0, Math.floor((Date.parse(state.expiresAt) - Date.now()) / 1000))
        : 0
      const authorized = Boolean(state.expiresAt) && remaining > 0

      return hf('div', {
        style: card,
        children: [
          h('div', { key: 'title', style: { fontSize: 15, fontWeight: 600 } }, 'hdsl-web 面板管理'),
          h(
            'div',
            { key: 'desc', style: { fontSize: 12, opacity: 0.75, lineHeight: 1.7 } },
            '这里的 agent 密码属于面板，不是开发者密码。它换到的票只能带动下面勾选的范围，'
              + '而且必须有人在面板上提过权才换得到——没人提权时它什么都打不开。',
          ),
          h(
            'div',
            { key: 'status', style: { ...row, fontSize: 13 } },
            h('span', { style: label }, '当前状态'),
            h(
              'span',
              { style: { color: authorized ? 'var(--dsh-success, #2e7d32)' : 'inherit' } },
              state.loading
                ? '读取中…'
                : authorized
                  ? `已授权，剩 ${Math.floor(remaining / 60)} 分 ${remaining % 60} 秒`
                  : '未授权',
            ),
          ),
          h(
            'div',
            { key: 'url', style: row },
            h('span', { style: label }, '面板地址'),
            h('input', {
              style: input,
              value: panelUrl,
              onChange: (e) => setPanelUrl(e.target.value),
              placeholder: 'https://127.0.0.1:3080',
            }),
          ),
          h(
            'div',
            { key: 'pw', style: row },
            h('span', { style: label }, 'agent 密码'),
            h('input', {
              style: input,
              type: 'password',
              autoComplete: 'off',
              value: password,
              onChange: (e) => setPassword(e.target.value),
              onKeyDown: (e) => {
                if (e.key === 'Enter') void mint()
              },
            }),
          ),
          h(
            'div',
            { key: 'scopes', style: { display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 } },
            h('span', { key: 'l', style: label }, '申请的范围'),
            ...SCOPES.map(([scope, name, hint]) =>
              h(
                'label',
                { key: scope, style: { display: 'flex', alignItems: 'center', gap: 8 } },
                h('input', {
                  type: 'checkbox',
                  checked: scopes.includes(scope),
                  onChange: () => toggle(scope),
                }),
                h('span', null, name),
                h('span', { style: { fontSize: 12, opacity: 0.6 } }, hint),
              ),
            ),
            h(
              'span',
              { key: 'note', style: { fontSize: 12, opacity: 0.6 } },
              '面板只会给你它允许的更少范围；要得更多不会让范围变大。',
            ),
          ),
          error ? h('div', { key: 'err', style: { color: 'var(--dsh-error, #c62828)', fontSize: 13 } }, error) : null,
          hf('div', {
            key: 'actions',
            style: { display: 'flex', gap: 8, justifyContent: 'flex-end' },
            children: [
              h(
                'button',
                { key: 'clear', type: 'button', disabled: busy, onClick: () => void clear() },
                '清除授权',
              ),
              h(
                'button',
                { key: 'mint', type: 'button', disabled: busy, onClick: () => void mint() },
                busy ? '处理中…' : '获取授权',
              ),
            ],
          }),
        ],
      })
    }

    /**
     * Mounts the page into the settings shell.
     *
     * @param {object} ctx the client plugin context
     */
    function apply(ctx) {
      /**
       * Calls the host half and unwraps Connection's result envelope.
       *
       * @param {string} endpoint
       * @param {unknown} [payload]
       */
      const call = (endpoint, payload) => ctx.connection.rpc.call('/panel-agent', endpoint, payload ?? {})

      ctx.slots.inject('settings.section', () =>
        ctx.slots.register(
          {
            name: 'settings.section',
            id: 'hdsl-panel-agent',
            order: 25,
            // `locale` is omitted on purpose: a first-party panel page speaks
            // the language of the panel it configures, not the harness's.
            label: () => 'hdsl-web 面板',
          },
          (props) => h(PanelAgentSection, { ...props, call }),
        ),
      )
    }

    const inject = ['connection', 'slots']

    exports.name = 'dsh-panel-agent-client'
    exports.inject = inject
    exports.apply = apply
    return module.exports
  },
})
