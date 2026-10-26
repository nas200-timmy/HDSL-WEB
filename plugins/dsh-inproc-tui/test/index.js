/**
 * Directory entry for `node --test test/`.
 *
 * The Gradle hook runs bare `node --test`, which discovers `unit.test.js` on its
 * own; some Node builds treat a positional directory as a module instead of
 * walking it, and this file is what those resolve. It imports the suite so
 * both invocations run the same tests exactly once.
 */

import './unit.test.js'
