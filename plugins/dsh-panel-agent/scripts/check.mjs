#!/usr/bin/env node
/**
 * Offline check: the repository builds this package with Gradle, not npm, so
 * there is no install step to fail loudly. This script is what `npm run check`
 * and the Gradle `check` task run instead: it proves the manifest still names
 * real files, and that every source file parses on the Node this repository
 * supports.
 *
 * It must stay dependency-free — the whole point is that a machine with no
 * registry access can still prove the package is well formed.
 */
import { readFileSync, existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const problems = []

const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
for (const field of ['name', 'version', 'license', 'type', 'main']) {
  if (!manifest[field]) problems.push(`package.json is missing "${field}"`)
}
if (manifest.type !== 'module') problems.push('package.json must declare "type": "module"')
if (!manifest.dsh?.bundle?.patch) problems.push('package.json must declare dsh.bundle.patch')

for (const file of manifest.files ?? []) {
  if (!existsSync(join(root, file))) problems.push(`files[] names a missing path: ${file}`)
}

// A binary is what makes a package an *app* plugin; this one must stay a plain
// bundle, because installing it next to a terminal app is the combination that
// cannot boot.
if (manifest.bin) problems.push('a panel bridge must not ship a bin: it belongs in the instance profile')

const sources = ['src/index.js', 'src/client.js', 'src/tools.js', 'lib/client.js']
for (const source of sources) {
  const path = join(root, source)
  if (!existsSync(path)) {
    problems.push(`missing source: ${source}`)
    continue
  }
  try {
    execFileSync(process.execPath, ['--check', path], { stdio: 'pipe' })
  } catch (error) {
    problems.push(`${source} does not parse: ${String(error?.stderr ?? error).trim()}`)
  }
}

// The dependency promise: one runtime dependency would break the offline build,
// so the manifest may name only the harness packages it imports.
for (const dependency of Object.keys(manifest.dependencies ?? {})) {
  problems.push(`runtime dependency "${dependency}" breaks the offline build`)
}

if (problems.length > 0) {
  for (const problem of problems) console.error(`dsh-panel-agent: ${problem}`)
  process.exit(1)
}
console.log(`dsh-panel-agent: ok (${sources.length} sources, ${manifest.peerDependencies ? Object.keys(manifest.peerDependencies).length : 0} peers)`)
