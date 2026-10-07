'use strict'
const assert = require('node:assert/strict')
const { test } = require('node:test')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const { SocketBootstrap } = require('../dist/bootstrap.js')
const TOKEN = 'b'.repeat(64)
let seq = 0

async function host(t, receive) {
  const socketPath = path.join(os.tmpdir(), `as-id-${process.pid}-${++seq}.sock`)
  const peers = new Set()
  const server = net.createServer(sock => {
    peers.add(sock)
    sock.on('error', () => {})
    sock.on('close', () => peers.delete(sock))
    let data = ''
    sock.on('data', chunk => {
      data += chunk
      let nl
      while ((nl = data.indexOf('\n')) >= 0) {
        const frame = JSON.parse(data.slice(0, nl)); data = data.slice(nl + 1)
        receive(sock, frame)
      }
    })
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(socketPath, resolve) })
  t.after(async () => {
    for (const sock of peers) sock.destroy()
    await new Promise(resolve => server.close(resolve))
  })
  return socketPath
}

test('helloAck 前 install/业务拒绝，并发 connect 同一 Promise，分片 ACK 才 READY', async t => {
  let ack
  let seenHello
  const helloSeen = new Promise(r => { seenHello = r })
  const frames = []
  const socketPath = await host(t, (sock, frame) => {
    frames.push(frame)
    if (frame.t === 'hello') {
      assert.equal(frame.token, TOKEN)
      ack = () => { sock.write('{"t":"hello'); setImmediate(() => sock.write('Ack","v":1}\n')) }
      seenHello()
    }
  })
  const b = new SocketBootstrap({ socketPath, token: TOKEN })
  t.after(() => b.close())
  const pending = b.connect()
  assert.strictEqual(pending, b.connect())
  await helloSeen
  assert.equal(b.connected, false)
  assert.throws(() => b.install(), e => e.code === 'ERR_ENGINE_STOPPED')
  assert.throws(() => b.handler('probe', 'm', null, 1, 2000))
  assert.deepEqual(frames.map(f => f.t), ['hello'])
  ack()
  await pending
  assert.equal(b.connected, true)
})

for (const scenario of ['reject', 'bad-version', 'eof', 'timeout', 'close', 'oversize']) {
  test(`握手 ${scenario} 一定拒绝 pending connect，不悬挂`, { timeout: 3000 }, async t => {
    let ready
    const received = new Promise(r => { ready = r })
    const socketPath = await host(t, (sock, frame) => {
      assert.equal(frame.t, 'hello'); ready()
      if (scenario === 'reject') sock.write('{"t":"helloErr","v":1,"code":"ERR_PERMISSION_DENIED"}\n')
      if (scenario === 'bad-version') sock.write('{"t":"helloAck","v":2}\n')
      if (scenario === 'eof') sock.end()
      if (scenario === 'oversize') sock.write('x'.repeat(1025))
    })
    const b = new SocketBootstrap({ socketPath, token: TOKEN, connectTimeout: scenario === 'timeout' ? 50 : 1000 })
    t.after(() => b.close())
    const rejected = assert.rejects(b.connect())
    await received
    if (scenario === 'close') b.close()
    await rejected
    assert.equal(b.connected, false)
    await assert.rejects(b.connect())
  })
}

test('缺凭据/连接错误立即拒绝', async () => {
  await assert.rejects(new SocketBootstrap({ socketPath: '/no-such-as.sock', token: '' }).connect(), e => e.code === 'ERR_PERMISSION_DENIED')
  await assert.rejects(new SocketBootstrap({ socketPath: '/no-such-as.sock', token: TOKEN }).connect())
})

test('连续请求及 write(false) 后续写：每帧只写一次', { timeout: 10000 }, async t => {
  let done
  const all = new Promise(r => { done = r })
  const seen = []
  const socketPath = await host(t, (sock, frame) => {
    if (frame.t === 'hello') { sock.write('{"t":"helloAck","v":1}\n'); return }
    seen.push(frame.id)
    if (seen.length === 12) done()
  })
  const b = new SocketBootstrap({ socketPath, token: TOKEN })
  t.after(() => b.close())
  await b.connect()
  // > highWaterMark 的帧逼出真实背压路径，后续小帧不得停在 draining 或重发第一帧。
  for (let id = 1; id <= 12; id++) b.handler('probe', 'm', 'x'.repeat(id === 1 ? 2 * 1024 * 1024 : 8), id, 5000)
  await all
  assert.deepEqual(seen, Array.from({ length: 12 }, (_, i) => i + 1))
})
