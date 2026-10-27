/**
 * Persistent allow / deny rules for the terminal's approval seam.
 *
 * Kimi Code keeps permanent rules in its config; this is the smaller equivalent
 * for the panel's terminals: one JSON file under the instance home, keyed by the
 * same `allowKey()` string the session allow-list uses. A deny always wins, and
 * a rule is matched exactly — no regex a typo can turn into "allow everything".
 *
 * @module approval/rules
 */

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'

/** The file a terminal reads and writes; `HDSL_TUI_RULES` overrides it for tests. */
export function rulesFile() {
  const override = process.env.HDSL_TUI_RULES
  if (typeof override === 'string' && override.trim() !== '') return override
  return join(homedir(), '.hdsl-tui', 'permissions.json')
}

/** @returns {{allow: string[], deny: string[]}} */
export function emptyRules() {
  return { allow: [], deny: [] }
}

/**
 * Reads the rule file. A missing or malformed file is an empty rule set: a
 * broken file must not block the terminal, and it must not grant anything.
 *
 * @param {string} [file]
 * @returns {{allow: string[], deny: string[]}}
 */
export function loadRules(file = rulesFile()) {
  try {
    const value = JSON.parse(readFileSync(file, 'utf8'))
    return {
      allow: Array.isArray(value?.allow) ? value.allow.filter((entry) => typeof entry === 'string') : [],
      deny: Array.isArray(value?.deny) ? value.deny.filter((entry) => typeof entry === 'string') : [],
    }
  } catch {
    return emptyRules()
  }
}

/**
 * Writes the rule file atomically.
 *
 * @param {{allow: string[], deny: string[]}} rules
 * @param {string} [file]
 */
export function saveRules(rules, file = rulesFile()) {
  const value = {
    allow: [...new Set(rules.allow)].sort(),
    deny: [...new Set(rules.deny)].sort(),
  }
  mkdirSync(dirname(file), { recursive: true })
  const temporary = `${file}.${process.pid}.tmp`
  const body = `${JSON.stringify(value, null, 2)}\n`
  writeFileSync(temporary, body, { mode: 0o600 })
  try {
    renameSync(temporary, file)
  } catch {
    // Windows refuses to rename over an existing file; a direct write is the
    // fallback. The file is a rule list, not a credential store, so the loss of
    // atomicity here is acceptable.
    writeFileSync(file, body, { mode: 0o600 })
  }
  return value
}

/**
 * @param {{allow: string[], deny: string[]}} rules
 * @param {string} key
 * @returns {'allow' | 'deny' | ''}
 */
export function ruleFor(rules, key) {
  if (rules.deny.includes(key)) return 'deny'
  if (rules.allow.includes(key)) return 'allow'
  return ''
}

/**
 * Adds (or moves) one key.
 *
 * @param {{allow: string[], deny: string[]}} rules
 * @param {'allow' | 'deny'} kind
 * @param {string} key
 * @returns {{allow: string[], deny: string[]}}
 */
export function withRule(rules, kind, key) {
  const allow = rules.allow.filter((entry) => entry !== key)
  const deny = rules.deny.filter((entry) => entry !== key)
  if (kind === 'deny') deny.push(key)
  else allow.push(key)
  return { allow, deny }
}

/**
 * @param {{allow: string[], deny: string[]}} rules
 * @param {string} key
 * @returns {{allow: string[], deny: string[]}}
 */
export function withoutRule(rules, key) {
  return {
    allow: rules.allow.filter((entry) => entry !== key),
    deny: rules.deny.filter((entry) => entry !== key),
  }
}
