/**
 * Interface strings.
 *
 * Two languages ship: Chinese and English. The launch picks one from
 * `HSDL_TUI_LANG` when set, otherwise from the locale variables the terminal
 * exports. Adding a string means adding it to both tables — the fallback is the
 * English entry, so a missing translation degrades to text rather than to a key.
 *
 * @module i18n
 */

export const TABLES = {
  en: {
    'banner.tag': 'dsh-acp-tui',
    'banner.subtitle': 'DeepSeek Harness in your terminal',
    'banner.hint': 'Type a message to start · /help for commands',
    'banner.resume': 'Resuming session {id}',
    'banner.new': 'New session',
    'composer.placeholder': 'Ask anything — / for commands',
    'composer.placeholder.busy': 'Queue a message… (enter queues, esc interrupts)',
    'composer.placeholder.plan': 'Describe what to plan…',
    'composer.hint.send': 'enter send',
    'composer.hint.newline': 'ctrl+j newline',
    'composer.hint.commands': '/ commands',
    'status.context': 'context',
    'status.tokens': '{used}/{size}',
    'status.polling': 'connecting…',
    'status.connected': 'ready',
    'status.disconnected': 'disconnected',
    'mode.ask': 'ask',
    'mode.auto': 'auto',
    'effort.off': 'off',
    'effort.low': 'low',
    'effort.high': 'high',
    'effort.max': 'max',
    'thinking.live': 'Thinking',
    'thinking.done': 'Thought',
    'thinking.characters': '{count} chars',
    'working': 'Working',
    'working.tool': 'Working · {tool}',
    'tool.queued': 'queued',
    'tool.running': 'running',
    'tool.done': 'done',
    'tool.failed': 'failed',
    'tool.output': 'output',
    'tool.input': 'input',
    'tool.no-output': '(no output)',
    'tool.expand': 'ctrl+o expand',
    'tool.collapse': 'ctrl+o collapse',
    'tool.more-lines': '… {count} more lines',
    'permission.title': 'Permission needed',
    'permission.body': 'The agent wants to run a tool in this workspace.',
    'permission.allow-once': 'Allow once',
    'permission.allow-always': 'Allow for this session',
    'permission.reject': 'Reject',
    'permission.hint': '↑↓ choose · enter confirm · esc reject',
    'model.title': 'Choose a model',
    'model.current': 'current',
    'model.hint': '↑↓ choose · enter confirm · esc cancel',
    'effort.title': 'Reasoning effort',
    'resume.title': 'Resume a session',
    'resume.empty': 'No resumable session in this workspace yet.',
    'resume.hint': '↑↓ choose · enter resume · esc cancel',
    'resume.here': 'this workspace',
    'resume.other': 'other cwd',
    'help.title': 'Commands and keys',
    'help.commands': 'Commands',
    'help.keys': 'Keys',
    'help.hint': 'esc close',
    'quit.confirm': 'Press ctrl+c again to quit',
    'quit.resume': 'session {id} — resume it from the terminal with /resume',
    'notice.cancelled': 'Cancelled',
    'notice.turn-end': 'done in {seconds}s',
    'notice.error': 'Error',
    'notice.reconnect': 'Lost the harness connection',
    'notice.attached': 'Connected to the harness (ACP v{version})',
    'notice.thinking-unavailable': 'this harness sends no reasoning, so thinking stays empty',
    'notice.ready': 'Connected to the harness {version}',
    'notice.compacted': 'Context compacted',
    'question.title': 'A question for you',
    'question.no-options': '(no options — answer from the panel)',
    'cmd.unknown': 'Unknown command: /{name}',
    'cmd.help': 'List commands and keys',
    'cmd.clear': 'Clear the transcript',
    'cmd.model': 'Choose the model for this session',
    'cmd.effort': 'Choose the reasoning effort',
    'cmd.resume': 'Resume an earlier session',
    'cmd.new': 'Start a fresh session',
    'cmd.quit': 'Leave the terminal (the harness stops with it)',
    'cmd.compact': 'Ask the agent to compact the context',
    'arg.missing': 'Missing argument',
    'error.spawn': 'Could not start the harness: {message}',
    'error.no-entry': 'Cannot find the dsh entry point. Set DSH_ENTRY to the harness bin.js.',
    'error.acp': 'The harness answered with an error: {message}',
    'error.version': 'This harness speaks ACP version {got}, the terminal needs 1.',
    'hint.session': 'session {id}',
    'hint.scroll': 'pgup/pgdn scroll',
    'hint.esc': 'esc interrupt',
    'hint.ctrl-c': 'ctrl+c quit',
    'hint.ctrl-o': 'ctrl+o expand',
    'unit.second': 's',
    'unit.minute': 'm',
  },
  zh: {
    'banner.tag': 'dsh-acp-tui',
    'banner.subtitle': '终端里的 DeepSeek Harness',
    'banner.hint': '输入问题开始 · /help 查看命令',
    'banner.resume': '恢复会话 {id}',
    'banner.new': '新会话',
    'composer.placeholder': '输入问题 — / 查看命令',
    'composer.placeholder.busy': '排队一条消息…（enter 排队，esc 中断）',
    'composer.placeholder.plan': '描述要规划的事情…',
    'composer.hint.send': 'enter 发送',
    'composer.hint.newline': 'ctrl+j 换行',
    'composer.hint.commands': '/ 命令',
    'status.context': '上下文',
    'status.tokens': '{used}/{size}',
    'status.polling': '连接中…',
    'status.connected': '就绪',
    'status.disconnected': '已断开',
    'mode.ask': '询问',
    'mode.auto': '自动',
    'effort.off': '关',
    'effort.low': '低',
    'effort.high': '高',
    'effort.max': '最高',
    'thinking.live': '思考中',
    'thinking.done': '思考',
    'thinking.characters': '{count} 字',
    'working': '处理中',
    'working.tool': '处理中 · {tool}',
    'tool.queued': '排队',
    'tool.running': '运行中',
    'tool.done': '完成',
    'tool.failed': '失败',
    'tool.output': '输出',
    'tool.input': '参数',
    'tool.no-output': '（无输出）',
    'tool.expand': 'ctrl+o 展开',
    'tool.collapse': 'ctrl+o 收起',
    'tool.more-lines': '… 另有 {count} 行',
    'permission.title': '需要确认',
    'permission.body': 'agent 想在这个工作区运行一个工具。',
    'permission.allow-once': '允许一次',
    'permission.allow-always': '本会话内总是允许',
    'permission.reject': '拒绝',
    'permission.hint': '↑↓ 选择 · enter 确认 · esc 拒绝',
    'model.title': '选择模型',
    'model.current': '当前',
    'model.hint': '↑↓ 选择 · enter 确认 · esc 取消',
    'effort.title': '推理强度',
    'resume.title': '恢复会话',
    'resume.empty': '这个工作区还没有可恢复的会话。',
    'resume.hint': '↑↓ 选择 · enter 恢复 · esc 取消',
    'resume.here': '此工作区',
    'resume.other': '其他目录',
    'help.title': '命令与按键',
    'help.commands': '命令',
    'help.keys': '按键',
    'help.hint': 'esc 关闭',
    'quit.confirm': '再按一次 ctrl+c 退出',
    'quit.resume': '会话 {id} —— 下次进终端用 /resume 恢复',
    'notice.cancelled': '已取消',
    'notice.turn-end': '用时 {seconds}s',
    'notice.error': '出错',
    'notice.reconnect': '与 harness 的连接断开了',
    'notice.attached': '已连接 harness（ACP v{version}）',
    'notice.thinking-unavailable': '这个 harness 不发送思考内容，思考区会保持为空',
    'notice.ready': '已连接 harness（{version}）',
    'notice.compacted': '上下文已压缩',
    'question.title': '需要你回答',
    'question.no-options': '（没有选项，无法在这里回答）',
    'cmd.unknown': '未知命令：/{name}',
    'cmd.help': '列出命令与按键',
    'cmd.clear': '清空记录',
    'cmd.model': '选择本会话的模型',
    'cmd.effort': '选择推理强度',
    'cmd.resume': '恢复一个早先的会话',
    'cmd.new': '开一个新会话',
    'cmd.quit': '离开终端（harness 随之停止）',
    'cmd.compact': '让 agent 压缩上下文',
    'arg.missing': '缺少参数',
    'error.spawn': '无法启动 harness：{message}',
    'error.no-entry': '找不到 dsh 入口。请把 DSH_ENTRY 指向 harness 的 bin.js。',
    'error.acp': 'harness 返回了错误：{message}',
    'error.version': '这个 harness 说 ACP 版本 {got}，终端需要 1。',
    'hint.session': '会话 {id}',
    'hint.scroll': 'pgup/pgdn 滚动',
    'hint.esc': 'esc 中断',
    'hint.ctrl-c': 'ctrl+c 退出',
    'hint.ctrl-o': 'ctrl+o 展开',
    'unit.second': '秒',
    'unit.minute': '分',
  },
}

/** Picks the interface language from the environment. */
export function detectLanguage(env) {
  const explicit = (env.HSDL_TUI_LANG ?? '').trim().toLowerCase()
  if (explicit.startsWith('zh')) return 'zh'
  if (explicit.startsWith('en')) return 'en'
  const locale = (env.LC_ALL ?? env.LC_MESSAGES ?? env.LANG ?? '').trim().toLowerCase()
  if (locale.startsWith('zh')) return 'zh'
  if (locale.startsWith('en')) return 'en'
  // A panel PTY often carries no locale at all (or plain `C`), and the panel
  // around this terminal is Chinese-first; defaulting to Chinese there is what
  // the person who opened it expects. `HSDL_TUI_LANG=en` is the way out.
  return 'zh'
}

/**
 * Builds the translator.
 *
 * @param {'en'|'zh'} [language]
 */
export function createI18n(language = 'en') {
  const table = TABLES[language] ?? TABLES.en
  const fallback = TABLES.en
  return {
    language,
    /**
     * @param {string} key
     * @param {Record<string, string | number>} [vars]
     * @returns {string}
     */
    t(key, vars) {
      const template = table[key] ?? fallback[key] ?? key
      if (!vars) return template
      return template.replace(/\{(\w+)\}/g, (all, name) => (name in vars ? String(vars[name]) : all))
    },
    /** Whether the key exists in either table (used by tests). */
    has(key) {
      return key in table || key in fallback
    },
  }
}

/** Every key the English table defines; the check proves Chinese matches. */
export const REQUIRED_KEYS = Object.keys(TABLES.en)
