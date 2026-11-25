#!/usr/bin/env node
// Forwards JSON-RPC from the tabs to the analyst_ask server over http on 127.0.0.1:8791.
// POST /  -> one request, one response. GET /sse -> event stream with the endpoint. GET /health -> ok.
import http from 'node:http'
import { spawn } from 'node:child_process'

const PORT = Number(process.env.ANALYST_PORT || 8791)
const SERVER = '/root/.arin/analyst-mcp.mjs'

const child = spawn('node', [SERVER], { stdio: ['pipe', 'pipe', 'pipe'] })
const waiting = new Map()
const streams = new Set()

function send(msg) {
  try { child.stdin.write(JSON.stringify(msg) + '\n') } catch {}
}

let buf = ''
child.stdout.setEncoding('utf8')
child.stdout.on('data', (chunk) => {
  buf += chunk
  let nl
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl).trim()
    buf = buf.slice(nl + 1)
    if (!line) continue
    let msg
    try { msg = JSON.parse(line) } catch { continue }
    const entry = waiting.get(msg.id)
    if (entry) {
      waiting.delete(msg.id)
      clearTimeout(entry.timer)
      entry.resolve(msg)
    } else {
      for (const res of streams) res.write(`data: ${line}\n\n`)
    }
  }
})
child.stderr.on('data', () => {})
child.on('exit', () => process.exit(1))

function forward(msg) {
  return new Promise((resolve) => {
    if (msg.id === undefined || msg.id === null) { send(msg); resolve(null); return }
    const timer = setTimeout(() => { waiting.delete(msg.id); resolve({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: 'analyst timed out' } }) }, 330_000)
    waiting.set(msg.id, { resolve, timer })
    send(msg)
  })
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok')
    return
  }
  if (req.method === 'GET' && (req.url === '/sse' || req.url === '/')) {
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    })
    res.write(`event: endpoint\ndata: /message\n\n`)
    streams.add(res)
    const beat = setInterval(() => { try { res.write(': ping\n\n') } catch {} }, 20_000)
    req.on('close', () => { clearInterval(beat); streams.delete(res) })
    return
  }
  if (req.method === 'POST') {
    let body = ''
    req.setEncoding('utf8')
    req.on('data', (d) => { body += d })
    req.on('end', async () => {
      let msg
      try { msg = JSON.parse(body) } catch {
        res.writeHead(400, { 'content-type': 'application/json' }).end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }))
        return
      }
      const out = await forward(msg)
      if (out) res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(out))
      else res.writeHead(202).end('')
    })
    return
  }
  res.writeHead(404).end('not found')
})

server.listen(PORT, '127.0.0.1')
