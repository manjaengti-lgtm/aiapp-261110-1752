#!/usr/bin/env node
// analyst_ask — the one tool of the analyst lane. Walks the stamp back unchanged.
// Arguments: question (required), file (absolute path, optional), lines ("120-140", optional).
// Bad input never throws at the caller: it comes back as a plain text result.
import { spawn } from 'node:child_process'

const SCRIPT = '/root/.arin/analyst.sh'
const TIMEOUT_MS = 320_000

const TOOL = {
  name: 'analyst_ask',
  description:
    'Ask Claude one headless question and get the stamped answer back unchanged. Stateless: new process per question, no memory. ' +
    'question is required; file (absolute path under /root/files) and lines ("120-140") are optional evidence. ' +
    'Show the returned text exactly as it is, stamp and all. On failure it returns "📞 Claude unreachable — carrying on without it".',
  inputSchema: {
    type: 'object',
    properties: {
      question: { type: 'string', description: 'The one question to ask.' },
      file: { type: 'string', description: 'Absolute path of a file to quote as evidence.' },
      lines: { type: 'string', description: 'Line range inside file, e.g. 120-140.' },
    },
    required: ['question'],
    additionalProperties: false,
  },
}

function text(body, isError = false) {
  return { content: [{ type: 'text', text: body }], isError }
}

function ask(args) {
  return new Promise((resolve) => {
    const clean = {}
    for (const [k, v] of Object.entries(args || {})) {
      if (typeof v === 'string' && v.trim() !== '') clean[k] = v.trim()
    }
    if (!clean.question) {
      resolve(text('analyst_ask needs a question. Example: analyst_ask({"question": "what does Foo.parse do?"})', true))
      return
    }
    if (clean.file && !clean.file.startsWith('/')) {
      resolve(text(`The file must be an absolute path, e.g. /root/files/Foo.kt (got "${clean.file}").`, true))
      return
    }
    if (clean.lines && !/^\d+(-\d+)?$/.test(clean.lines)) {
      resolve(text(`lines must look like 120-140 (got "${clean.lines}").`, true))
      return
    }
    const argv = [SCRIPT]
    if (clean.file) argv.push('-f', clean.file)
    if (clean.lines) argv.push('-l', clean.lines)
    argv.push(clean.question)
    const child = spawn('bash', argv, { stdio: ['ignore', 'pipe', 'pipe'] })
    let out = ''
    let err = ''
    const timer = setTimeout(() => { try { child.kill('SIGKILL') } catch {} }, TIMEOUT_MS)
    child.stdout.on('data', (d) => { out += d })
    child.stderr.on('data', (d) => { if (err.length < 4000) err += d })
    child.on('error', () => { clearTimeout(timer); resolve(text('📞 Claude unreachable — carrying on without it', true)) })
    child.on('close', (code) => {
      clearTimeout(timer)
      const line = out.split('\n').filter((l) => l.trim().startsWith('{')).pop()
      if (line) {
        try {
          const parsed = JSON.parse(line)
          if (parsed && typeof parsed.text === 'string') { resolve(text(parsed.text, false)); return }
        } catch {}
      }
      const plain = out.trim()
      if (code === 0 && plain) { resolve(text(plain, false)); return }
      resolve(text('📞 Claude unreachable — carrying on without it', true))
    })
  })
}

function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n')
}

let buffer = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', async (chunk) => {
  buffer += chunk
  let nl
  while ((nl = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, nl).trim()
    buffer = buffer.slice(nl + 1)
    if (!line) continue
    let msg
    try { msg = JSON.parse(line) } catch { continue }
    const { id, method, params } = msg
    if (method === 'initialize') {
      reply(id, {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'analyst', version: '1.0.0' },
      })
    } else if (method === 'tools/list') {
      reply(id, { tools: [TOOL] })
    } else if (method === 'tools/call') {
      if (params?.name !== 'analyst_ask') {
        reply(id, { content: [{ type: 'text', text: `Unknown tool ${params?.name}` }], isError: true })
      } else {
        reply(id, await ask(params?.arguments))
      }
    } else if (method === 'notifications/initialized') {
      // no reply needed
    } else if (id !== undefined && id !== null) {
      process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code: -32601, message: 'not found' } }) + '\n')
    }
  }
})
