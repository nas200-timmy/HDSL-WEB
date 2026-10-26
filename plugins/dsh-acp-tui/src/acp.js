/**
 * An Agent Client Protocol client over a child process.
 *
 * The terminal does not import any harness package: it starts `dsh --profile acp`
 * as a child and speaks ACP v1 to it on newline-delimited JSON-RPC. That is the
 * one stable seam the harness offers a front end — a documented protocol rather
 * than an in-process service graph — so a new dsh release changes nothing here,
 * and the terminal keeps working while the harness's internals keep moving.
 *
 * @module acp
 */

import { spawn } from 'node:child_process'
import { EventEmitter } from 'node:events'

const PROTOCOL_VERSION = 1

/** Default cap on the stderr kept for diagnostics. */
const STDERR_LINES = 80

/** A JSON-RPC error surfaced as a rejected request. */
export class AcpError extends Error {
  constructor(message, code, data) {
    super(message)
    this.name = 'AcpError'
    this.code = code
    this.data = data
  }
}

/**
 * The ACP client.
 *
 * Events: `update` (session/update params), `request` (an agent-to-client request
 * the UI must answer through {@link respond}), `stderr`, `exit`.
 */
export class AcpClient extends EventEmitter {
  /**
   * @param {{command: string[], cwd: string, env: Record<string, string | undefined>, log?: (line: string) => void}} options
   */
  constructor(options) {
    super()
    this.command = options.command
    this.cwd = options.cwd
    this.env = options.env
    this.log = options.log ?? (() => {})
    this.child = null
    this.pending = new Map()
    this.nextId = 1
    this.buffer = ''
    this.stderrLines = []
    this.closed = false
    this.agentInfo = null
    this.capabilities = {}
  }

  /** Starts the child process and begins parsing its stdout. */
  start() {
    const [file, ...args] = this.command
    this.child = spawn(file, args, {
      cwd: this.cwd,
      env: this.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.child.stdout.setEncoding('utf8')
    this.child.stderr.setEncoding('utf8')
    this.child.stdout.on('data', (chunk) => this.onData(chunk))
    this.child.stderr.on('data', (chunk) => {
      for (const line of chunk.split('\n')) {
        const trimmed = line.trim()
        if (trimmed === '') continue
        this.stderrLines.push(trimmed)
        if (this.stderrLines.length > STDERR_LINES) this.stderrLines.shift()
        this.emit('stderr', trimmed)
      }
    })
    this.child.on('error', (error) => {
      this.emit('exit', { code: null, signal: null, error })
    })
    this.child.on('exit', (code, signal) => {
      this.closed = true
      for (const [, entry] of this.pending) {
        entry.reject(new AcpError('the harness exited before answering', -32000))
      }
      this.pending.clear()
      this.emit('exit', { code, signal })
    })
    return this
  }

  /** @private */
  onData(chunk) {
    this.buffer += chunk
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index).trim()
      this.buffer = this.buffer.slice(index + 1)
      index = this.buffer.indexOf('\n')
      if (line === '') continue
      let message
      try {
        message = JSON.parse(line)
      } catch {
        this.log(`non-json stdout: ${line.slice(0, 400)}`)
        continue
      }
      this.dispatch(message)
    }
  }

  /** @private */
  dispatch(message) {
    if (typeof message !== 'object' || message === null) return
    if (message.method !== undefined) {
      if (message.id !== undefined) {
        this.emit('request', {
          id: message.id,
          method: message.method,
          params: message.params ?? {},
        })
      } else {
        this.emit('notification', { method: message.method, params: message.params ?? {} })
      }
      return
    }
    if (message.id === undefined) return
    const entry = this.pending.get(message.id)
    if (entry === undefined) return
    this.pending.delete(message.id)
    if (message.error !== undefined) {
      entry.reject(new AcpError(message.error.message ?? 'request failed', message.error.code, message.error.data))
    } else {
      entry.resolve(message.result ?? {})
    }
  }

  /**
   * Sends a request and resolves with its result.
   * @param {string} method
   * @param {unknown} params
   * @returns {Promise<any>}
   */
  request(method, params) {
    if (this.child === null || this.closed) {
      return Promise.reject(new AcpError('the harness is not running', -32000))
    }
    const id = this.nextId
    this.nextId += 1
    const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n'
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method })
      this.child.stdin.write(payload, (error) => {
        if (error) {
          this.pending.delete(id)
          reject(new AcpError(error.message, -32000))
        }
      })
    })
  }

  /** Sends a notification (no response expected). */
  notify(method, params) {
    if (this.child === null || this.closed) return
    this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n')
  }

  /**
   * Answers an agent-to-client request.
   * @param {number} id
   * @param {{result?: unknown, error?: {code: number, message: string}}} outcome
   */
  respond(id, outcome) {
    if (this.child === null || this.closed) return
    const body = outcome.error !== undefined
      ? { jsonrpc: '2.0', id, error: outcome.error }
      : { jsonrpc: '2.0', id, result: outcome.result ?? {} }
    this.child.stdin.write(JSON.stringify(body) + '\n')
  }

  /**
   * Runs the ACP handshake.
   * @param {{name: string, version: string}} clientInfo
   * @returns {Promise<{protocolVersion: number, agentInfo: unknown, agentCapabilities: object}>}
   */
  async initialize(clientInfo) {
    const result = await this.request('initialize', {
      protocolVersion: PROTOCOL_VERSION,
      clientCapabilities: {
        fs: { readTextFile: false, writeTextFile: false },
        terminal: false,
      },
      clientInfo,
    })
    this.agentInfo = result.agentInfo ?? null
    this.capabilities = result.agentCapabilities ?? {}
    this.protocolVersion = result.protocolVersion ?? PROTOCOL_VERSION
    return result
  }

  get sessions() {
    return this.capabilities.sessionCapabilities ?? {}
  }

  /** Asks the harness to stop and waits briefly for the child to go. */
  async close() {
    if (this.child === null) return
    if (this.closed) return
    try {
      this.child.stdin.end()
    } catch {
      // stdin may already be gone.
    }
    const child = this.child
    await new Promise((resolve) => {
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve()
      }
      const timer = setTimeout(() => {
        try {
          child.kill('SIGTERM')
        } catch {
          // already exited
        }
        setTimeout(() => {
          try {
            child.kill('SIGKILL')
          } catch {
            // already exited
          }
          done()
        }, 1500)
      }, 1200)
      child.once('exit', done)
    })
  }
}
