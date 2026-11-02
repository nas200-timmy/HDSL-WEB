/**
 * The three tools this plugin offers, and the schema that is their contract.
 *
 * The panel's `AgentScopes` owns the authority: these tools only name routes.
 * A tool that a grant cannot reach answers `agent_scope_denied` — which is the
 * intended experience, not a bug: the operator decides how far the agent goes,
 * and the panel enforces it where the agent cannot edit.
 *
 * @module tools
 */

/**
 * Builds the tool definitions bound to one panel client.
 *
 * @param {import('./client.js').PanelClient} panel
 * @param {import('@deepseek-ai/dsh-tools').defineTool} defineTool
 * @returns {object[]}
 */
export function buildTools(panel, defineTool) {
  return [
    defineTool({
      name: 'panel_get',
      description:
        'Read one `/api/...` path from the HDSL-web panel that hosts this instance. '
        + 'Use it for panel state: `/api/instances`, `/api/mc/instances`, `/api/tasks`. '
        + 'Read-only; needs the "read" grant. The instance\'s own access URL and the '
        + 'export routes are closed to agents on purpose.',
      parameters: {
        path: {
          type: 'string',
          required: true,
          description: 'An /api/ path, e.g. /api/mc/instances',
        },
      },
      output: {
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => [
          { type: 'text', text: `panel_get → ${JSON.stringify(value).slice(0, 8000)}` },
        ],
      },
      async execute(args) {
        return panel.call('GET', args.path)
      },
    }),
    defineTool({
      name: 'panel_mc',
      description:
        'Operate one Minecraft server the panel owns: start it, stop it, or send one '
        + 'console line. Use this instead of running `java -jar` yourself — a second '
        + 'process on the same port makes the panel\'s own state lie. Needs the "mc" grant.',
      parameters: {
        instance: { type: 'string', required: true, description: 'MC instance id' },
        action: {
          type: 'string',
          required: true,
          description: 'start | stop | console',
        },
        line: {
          type: 'string',
          description: 'Console line when action is "console", e.g. "say hi"',
        },
      },
      output: {
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => [
          { type: 'text', text: `panel_mc → ${JSON.stringify(value).slice(0, 8000)}` },
        ],
      },
      async execute(args) {
        const base = `/api/mc/instances/${encodeURIComponent(args.instance)}`
        if (args.action === 'console') {
          if (!args.line) {
            return { ok: false, error: 'missing_line', detail: 'console needs a line' }
          }
          return panel.call('POST', `${base}/console/input`, { line: args.line })
        }
        if (args.action === 'start' || args.action === 'stop') {
          return panel.call('POST', `${base}/${args.action}`)
        }
        return { ok: false, error: 'bad_action', detail: 'action must be start, stop or console' }
      },
    }),
    defineTool({
      name: 'panel_create_mc',
      description:
        'Create a Minecraft server in the panel. Prefer this over starting your own: '
        + 'the panel then owns the port, the world and the log. Needs the "instances" grant.',
      parameters: {
        name: { type: 'string', required: true, description: 'Instance id, e.g. probe-1.14.4' },
        version: { type: 'string', required: true, description: 'Minecraft version, e.g. 1.14.4' },
        memoryMb: { type: 'integer', description: 'Heap size; the panel defaults to 2048' },
      },
      output: {
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => [
          { type: 'text', text: `panel_create_mc → ${JSON.stringify(value).slice(0, 8000)}` },
        ],
      },
      async execute(args) {
        const body = { name: args.name, version: args.version }
        if (args.memoryMb) body.memoryMb = args.memoryMb
        return panel.call('POST', '/api/mc/instances', body)
      },
    }),
  ]
}
