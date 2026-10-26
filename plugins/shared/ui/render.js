/**
 * Frame composition.
 *
 * The screen is laid out from the bottom: a status row, the composer box, an
 * activity row, and the transcript taking whatever is left. Every row is exactly
 * the terminal width, so the painter can diff and anchor rows without guessing.
 * Dialogs are composited over the transcript — their rows replace the middle of
 * the covered lines — which keeps the conversation visible while a picker is
 * open.
 *
 * @module ui/render
 */

import { padStyled, spread, truncateStyled, visibleWidth } from '../term/text.js'
import { renderBlock, formatDuration } from './blocks.js'
import { center, renderOverlay } from './dialogs.js'

const SPINNER = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']
const MIN_TRANSCRIPT_ROWS = 2

/** The spinner frame for a moment in time. */
export function spinnerFrame(clock) {
  return SPINNER[Math.floor(clock / 80) % SPINNER.length]
}

/**
 * Renders the whole frame.
 *
 * @param {object} state
 * @param {{width: number, height: number, theme: object, i18n: object, clock: number}} context
 * @returns {{rows: string[], cursor: {row: number, column: number} | null, transcript: {total: number}}}
 */
export function renderFrame(state, context) {
  const { width, height, theme, i18n, clock } = context
  const spinner = spinnerFrame(clock)
  const scope = { theme, i18n, spinner }

  const statusRow = renderStatus(state, width, theme, i18n)
  const inner = Math.max(1, width - 4)
  const composerLayout = layoutComposer(state, inner)
  const composerRows = paintComposer(state, width, composerLayout, theme, i18n)
  const activityRow = renderActivity(state, width, theme, i18n, clock, spinner)
  const bottomRows = composerRows.length + 2 + (activityRow === null ? 0 : 1)
  const transcriptHeight = Math.max(MIN_TRANSCRIPT_ROWS, height - bottomRows)
  const transcript = renderTranscript(state, width, transcriptHeight, scope)

  const rows = new Array(height).fill('')
  for (let index = 0; index < transcript.rows.length; index += 1) rows[index] = transcript.rows[index]

  // A short terminal (or a composer that has grown to several lines) can make the bottom
  // block taller than the screen: `top` went negative and the rows were written at negative
  // indices, which shift the frame and pull the cursor back to row 1. Clamp where the block
  // starts and refuse writes outside the screen — the composer is what the user is typing
  // into, so it wins over the transcript that is being clipped anyway.
  const top = Math.max(0, height - bottomRows)
  let row = top
  const put = (value) => {
    if (row >= 0 && row < height) rows[row] = value
    row += 1
  }
  put('')
  if (activityRow !== null) put(activityRow)
  for (const composerRow of composerRows) put(composerRow)
  rows[height - 1] = statusRow

  let cursor = null
  if (state.overlay !== null) {
    paintOverlay(rows, state, { width, height, theme, i18n })
  } else if (state.focus === 'composer') {
    cursor = {
      row: top + 1 + (activityRow === null ? 0 : 1) + 1 + composerLayout.caretRow,
      column: 3 + composerLayout.caretColumn,
    }
  }

  return {
    rows: rows.map((line) => padStyled(line, width)),
    cursor,
    transcript: { total: transcript.total },
  }
}

function layoutComposer(state, inner) {
  const view = state.composer.view(inner)
  const maxLines = state.composer.maxLines
  const rows = Math.min(maxLines, Math.max(1, view.total))
  // Scroll the visible window so the caret line stays on screen.
  const first = Math.max(0, Math.min(view.caret.row - rows + 1, view.total - rows))
  return {
    lines: view.lines.slice(first, first + rows),
    caretRow: view.caret.row - first,
    caretColumn: view.caret.column,
    rows,
  }
}

function paintComposer(state, width, layout, theme, i18n) {
  const inner = Math.max(1, width - 4)
  const borderColor = state.overlay !== null ? 'border' : 'primary'
  const rows = [theme.fg(borderColor, '╭' + '─'.repeat(Math.max(0, width - 2)) + '╮')]
  const placeholder = state.composer.empty
    ? (state.status === 'working' ? i18n.t('composer.placeholder.busy') : i18n.t('composer.placeholder'))
    : null
  for (let index = 0; index < layout.rows; index += 1) {
    const content = layout.lines[index] ?? ''
    const bullet = index === 0 ? theme.fg('primary', '❯ ') : '  '
    const painted = index === 0 && placeholder !== null
      ? bullet + theme.fg('textMuted', placeholder)
      : bullet + theme.fg('text', content)
    rows.push(theme.fg(borderColor, '│') + ' ' + padStyled(painted, inner) + ' ' + theme.fg(borderColor, '│'))
  }
  rows.push(theme.fg(borderColor, '╰' + '─'.repeat(Math.max(0, width - 2)) + '╯'))
  return rows
}

function paintOverlay(rows, state, context) {
  const { width, height, theme, i18n } = context
  const dialogRows = renderOverlay(state.overlay, context)
  if (dialogRows === null) return
  const placed = center(dialogRows, width, height)
  for (let index = 0; index < placed.rows.length; index += 1) {
    const target = placed.top + index
    if (target < 0 || target >= height) continue
    const base = rows[target] ?? ''
    const left = padStyled(truncateStyled(base, placed.left), placed.left)
    const dialogWidth = visibleWidth(placed.rows[index])
    const right = padStyled('', Math.max(0, width - placed.left - dialogWidth))
    rows[target] = left + placed.rows[index] + right
  }
}

function renderStatus(state, width, theme, i18n) {
  const busy = state.status === 'working'
  const dot = state.status === 'ready' || busy
    ? theme.fg('success', '●')
    : state.status === 'connecting'
      ? theme.fg('warning', '◌')
      : theme.fg('error', '✕')
  const model = theme.strong('text', state.model.currentLabel ?? state.model.current ?? 'dsh')
  const effortLabel = state.effort.current
    ? (i18n.has(`effort.${state.effort.current}`) ? i18n.t(`effort.${state.effort.current}`) : state.effort.current)
    : ''
  const effort = effortLabel === '' ? '' : theme.fg('textMuted', effortLabel)
  const git = state.gitBranch ? theme.fg('textMuted', ` ${state.gitBranch}`) : ''
  const cwd = theme.fg('textDim', compactPath(state.cwd, Math.max(10, Math.floor(width / 4)))) + git
  const usage = state.usage.size > 0
    ? theme.fg('textMuted', width >= 72
      ? `${i18n.t('status.context')} ${percent(state.usage)}% · ${formatTokens(state.usage.used)}/${formatTokens(state.usage.size)}`
      : `${i18n.t('status.context')} ${percent(state.usage)}%`)
    : ''
  const scroll = state.scroll.offset > 0 ? theme.fg('warning', `↑ ${state.scroll.offset}`) : ''
  const right = [scroll, usage].filter(Boolean).join('  ')
  const left = [dot, model, effort, cwd].filter((part) => part !== '').join(' ')
  return spread(left, right, width, 2)
}

function percent(usage) {
  if (!Number.isFinite(usage.used) || !Number.isFinite(usage.size) || usage.size <= 0) return 0
  return Math.min(100, Math.round((usage.used / usage.size) * 100))
}

function compactPath(path, budget) {
  const text = String(path ?? '')
  if (visibleWidth(text) <= budget) return text
  const parts = text.split(/[\\/]/).filter(Boolean)
  const tail = []
  let used = 0
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    const part = parts[index]
    const width = visibleWidth(part) + 1
    if (used + width > budget - 2) break
    tail.unshift(part)
    used += width
  }
  return tail.length === 0 ? text.slice(-budget) : `…/${tail.join('/')}`
}

function renderActivity(state, width, theme, i18n, clock, spinner) {
  if (state.status === 'working') {
    const label = state.activity.tool
      ? i18n.t('working.tool', { tool: state.activity.tool })
      : i18n.t('working')
    const elapsed = state.activity.startedAt === null ? 0 : clock - state.activity.startedAt
    const left = ` ${theme.fg('primary', spinner)} ${theme.fg('textDim', label)}${theme.fg('textMuted', `  ${formatDuration(elapsed)}`)}`
    const hint = state.queue.length > 0
      ? theme.fg('textMuted', `${state.queue.length} queued`)
      : theme.fg('textMuted', i18n.t('hint.esc'))
    return spread(left, hint, width, 2)
  }
  if (state.status === 'connecting') {
    return ` ${theme.fg('warning', spinner)} ${theme.fg('textDim', i18n.t('status.polling'))}`
  }
  const recent = state.lastTurnSeconds !== null && state.turnEndedAt !== undefined
    ? clock - state.turnEndedAt < 6000
    : false
  if (recent) {
    return ` ${theme.fg('textMuted', '·')} ${theme.fg('textMuted', i18n.t('notice.turn-end', { seconds: state.lastTurnSeconds.toFixed(1) }))}`
  }
  const tip = tipFor(clock, i18n)
  return tip === '' ? null : ` ${theme.fg('textMuted', '·')} ${theme.fg('textMuted', tip)}`
}

/** Idle hints rotate slowly, the way a toolbar tip does. */
function tipFor(clock, i18n) {
  const tips = ['hint.esc', 'hint.ctrl-o', 'hint.scroll', 'composer.hint.newline']
  const index = Math.floor(clock / 9000) % tips.length
  return i18n.t(tips[index])
}

function renderTranscript(state, width, height, scope) {
  const cache = state.transcriptCache
  const lines = []
  for (const block of state.blocks) {
    const cached = cache.get(block)
    if (cached !== undefined && cached.width === width && cached.rev === block.rev) {
      lines.push(...cached.lines)
      continue
    }
    const rendered = renderBlock(block, width, scope.theme, scope.i18n, { spinner: scope.spinner, expandTools: state.expandTools })
    cache.set(block, { width, rev: block.rev, lines: rendered })
    lines.push(...rendered)
    if (cache.size > 400) {
      const first = cache.keys().next()
      if (!first.done) cache.delete(first.value)
    }
  }
  const total = lines.length
  const maxOffset = Math.max(0, total - height)
  const offset = state.scroll.follow ? 0 : Math.min(state.scroll.offset, maxOffset)
  const start = Math.max(0, total - height - offset)
  const visible = lines.slice(start, start + height)
  while (visible.length < height) visible.push('')
  return { rows: visible, total, maxOffset }
}

/** Formats a token count for the status row (kept exported for tests). */
export function formatTokens(value) {
  if (!Number.isFinite(value)) return '0'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}m`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return String(value)
}
