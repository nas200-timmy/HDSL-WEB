/**
 * Cordis entry for the in-process terminal.
 *
 * The bundle patch lists this module as a profile row; mounting it takes over the
 * terminal and drives one agent. It is deliberately thin: the plugin contract is
 * `name` / `inject` / `apply`, and everything the person sees lives in `app.js`.
 *
 * @module index
 */

import { TerminalApp } from './app.js'

/** Stable Cordis plugin name. */
export const name = 'dsh-inproc-tui'

/// Core services that must be mounted before a turn can start. `sessions` is the
/// persistence service (flush on turn end), not the terminal's own bookkeeping.
export const inject = ['agents', 'agentDefaultModel', 'sessions']

/**
 * Mounts the terminal for this profile.
 *
 * @param {object} ctx the plugin context
 */
export function apply(ctx) {
  const app = new TerminalApp(ctx)
  app.start().catch((error) => {
    try {
      app.screen.leave()
    } catch {
      // the terminal may already be restored
    }
    process.stderr.write(`dsh-inproc-tui: ${error?.stack ?? error}\n`)
    ctx.get('appExit')?.(1)
  })
  // The profile tree may be disposed by the launcher while the terminal owns the
  // screen; restoring it is the plugin's own job.
  ctx.effect(() => () => {
    if (!app.stopping) void app.quit()
  })
}
