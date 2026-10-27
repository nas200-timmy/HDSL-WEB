/**
 * Deciding whether an approval has to be shown.
 *
 * The harness asks the terminal to approve a tool call (or a plan) and waits for
 * one of `allowed-once` / `rejected`. It has no notion of "run everything" — its
 * own policy switch only offers "ask" and "never", and `never` **rejects** — so a
 * mode that stops asking is a decision this terminal makes, per request, before
 * the dialog is drawn. That decision lives here: one pure function, one list of
 * rules, and a test file next to the terminal that uses it.
 *
 * The bias is deliberate: anything not recognised is asked about. A wrong "ask"
 * costs one keystroke; a wrong "allow" runs `rm -rf` without one.
 *
 * @module approval/policy
 */

/** The three modes, in the order `/yolo` cycles them. */
export const APPROVAL_MODES = ['ask', 'critical', 'full']

/** The mode a fresh session starts in — see `plugins/README.md` for why. */
export const DEFAULT_APPROVAL_MODE = 'critical'

/** Shells a command may arrive through, by tool name. */
const SHELL_TOOLS = /bash|pwsh|powershell|shell|exec|terminal|run_command/iu

/** File-writing tools, by tool name. */
const WRITE_TOOLS = /write|edit|replace|patch|create|delete|remove|move|rename|copy|mkdir|touch/iu

/** Tools whose call is a decision about what to do, never an automated action. */
const PLAN_TOOLS = /plan|present|goal|question|ask_user/iu

/** Commands that need a human even in `critical` mode. */
const DANGEROUS_COMMANDS = [
  // Privilege and account changes.
  /(^|\s)(sudo|doas|su|runas)\s/u,
  // Deletion, whatever the flags.
  /(^|\s)(rm|rmdir|shred|unlink|srm)\s/u,
  // Whole-disk and partition work.
  /(^|\s)(mkfs[.\w]*|fdisk|parted|dd|wipefs|mkswap|losetup)\s/u,
  // Power and init.
  /(^|\s)(shutdown|reboot|poweroff|halt|systemctl\s+(poweroff|reboot|halt))\b/u,
  // Killing everything the account can see.
  /(^|\s)kill\s+(-9|-KILL|-s\s*9)\s+-1\b/u,
  // Recursive permission changes.
  /(^|\s)(chmod|chown|chgrp)\s+(-\w*R|--recursive)/u,
  // History the person did not ask to lose.
  /git\s+push\b[^\n]*(--force\b|--force-with-lease\b|(^|\s)-f(\s|$))/u,
  /git\s+reset\s+--hard\b/u,
  /git\s+clean\s+[^\n]*-[a-z]*[fdx]/u,
  // Publishing to a registry.
  /(npm|pnpm|yarn)\s+publish\b/u,
  // Downloading something and running it.
  /(curl|wget)[^\n|]*\|\s*(sudo\s+)?(ba|z|da)?sh\b/u,
  // Writing to the machine's own directories.
  /(^|\s)>{1,2}\s*(\/etc|\/usr|\/bin|\/sbin|\/boot|\/dev\/(sd|nvme|hd)|\/var\/(lib|spool))\b/u,
  // The classic fork bomb shape.
  /:\s*\(\s*\)\s*\{/u,
]

/** Paths that belong to the machine rather than the workspace. */
const SYSTEM_PATHS = /^\/(etc|usr|bin|sbin|boot|dev|proc|sys|root|var\/(lib|spool))(\/|$)/u

/**
 * Reads the command text out of a tool call's arguments.
 *
 * Tools name it differently (`command`, `cmd`, `script`), and a call that
 * carries none is not a shell call at all.
 *
 * @param {object | undefined} input the call's arguments, already parsed
 * @returns {string} the command, or `''`
 */
export function commandOf(input) {
  if (input === null || input === undefined || typeof input !== 'object') return ''
  for (const key of ['command', 'cmd', 'script', 'commandLine']) {
    const value = input[key]
    if (typeof value === 'string') return value
    if (Array.isArray(value) && value.every((part) => typeof part === 'string')) return value.join(' ')
  }
  return ''
}

/**
 * Reads the file path out of a tool call's arguments.
 *
 * @param {object | undefined} input the call's arguments, already parsed
 * @returns {string} the path, or `''`
 */
export function pathOf(input) {
  if (input === null || input === undefined || typeof input !== 'object') return ''
  for (const key of ['path', 'file_path', 'filePath', 'file', 'target', 'destination']) {
    const value = input[key]
    if (typeof value === 'string' && value !== '') return value
  }
  return ''
}

/**
 * Whether a shell command does something a person should see first.
 *
 * @param {string} command the command text
 * @returns {string} why it is dangerous, or `''` when it is not
 */
export function dangerousCommand(command) {
  const text = String(command ?? '')
  if (text.trim() === '') return ''
  for (const pattern of DANGEROUS_COMMANDS) {
    if (pattern.test(text)) return `匹配危险规则 ${pattern.source}`
  }
  return ''
}

/**
 * Whether a file write leaves the workspace.
 *
 * @param {string} target the path the call names
 * @param {string} cwd the workspace root
 * @returns {string} why it is dangerous, or `''`
 */
export function dangerousPath(target, cwd) {
  const path = String(target ?? '').trim()
  if (path === '') return ''
  if (SYSTEM_PATHS.test(path)) return `写入系统路径 ${path}`
  const root = String(cwd ?? '').replace(/\/+$/u, '')
  if (root !== '' && path.startsWith('/') && !path.startsWith(`${root}/`) && path !== root) {
    return `写入工作区之外 ${path}`
  }
  return ''
}

/**
 * Decides whether one request needs a human.
 *
 * @param {{
 *   mode: string,
 *   toolName: string,
 *   input?: object,
 *   cwd?: string,
 * }} request what the harness is asking about
 * @returns {{verdict: 'allow' | 'ask', reason: string}} the decision and why
 */
export function decide(request) {
  const mode = APPROVAL_MODES.includes(request.mode) ? request.mode : DEFAULT_APPROVAL_MODE
  const toolName = String(request.toolName ?? '')
  const input = request.input
  const cwd = request.cwd ?? process.cwd()

  // A plan is a decision about what to do next, not a permission: it is asked
  // about in every mode, including `full`.
  if (PLAN_TOOLS.test(toolName)) return { verdict: 'ask', reason: '计划/提问类调用' }
  if (mode === 'ask') return { verdict: 'ask', reason: 'ask 模式' }
  if (mode === 'full') return { verdict: 'allow', reason: 'full 模式' }

  // `critical`: everything the rules do not name runs without a dialog.
  if (SHELL_TOOLS.test(toolName)) {
    const command = commandOf(input)
    if (command === '') return { verdict: 'ask', reason: '有命令但读不出内容' }
    const dangerous = dangerousCommand(command)
    if (dangerous !== '') return { verdict: 'ask', reason: dangerous }
    return { verdict: 'allow', reason: '未命中危险规则' }
  }
  if (WRITE_TOOLS.test(toolName)) {
    const dangerous = dangerousPath(pathOf(input), cwd)
    if (dangerous !== '') return { verdict: 'ask', reason: dangerous }
    return { verdict: 'allow', reason: '工作区内的写入' }
  }
  // Anything else — an unknown tool, a subagent, a web fetch — is asked about.
  return { verdict: 'ask', reason: '未知工具' }
}

/**
 * The allow-list key for "run this for the rest of the session".
 *
 * A whole tool name is too coarse for a shell (allowing `bash` once would allow
 * everything), and the full command too narrow (the next `ls` would ask again).
 * The first word is the middle: `git status` and `git log` share `git`, while
 * `git push` stays a separate decision.
 *
 * @param {string} toolName the tool
 * @param {object | undefined} input the call's arguments
 * @returns {string} the key
 */
export function allowKey(toolName, input) {
  const command = commandOf(input).trim()
  if (command === '') return String(toolName ?? '')
  const word = command.split(/\s+/u)[0] ?? ''
  return `${toolName} ${word}`
}
