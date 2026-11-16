#!/usr/bin/env bun
// PTP/IP 假相机。用途：在没有真机的情况下验证客户端的分帧/握手/数据段实现。
// 用法: bun run ptp_camera.ts
// 监听 0.0.0.0:15740，实现 InitCommandRequest/InitEventRequest/OpenSession/
// GetDeviceInfo/GetDevicePropDesc/GetDevicePropValue/SetDevicePropValue/Capture。

const PORT = Number(process.env.PTP_PORT || 15740);

// ---- PTP/IP 包类型（PIMA 15740 Annex F）: EndData=12, CancelTransaction=11 ----
const INIT_COMMAND_REQUEST = 1;
const INIT_COMMAND_ACK = 2;
const INIT_EVENT_REQUEST = 3;
const INIT_EVENT_ACK = 4;
const INIT_FAIL = 5;
const OPERATION_REQUEST = 6;
const OPERATION_RESPONSE = 7;
const EVENT = 8;
const START_DATA = 9;
const DATA = 10;
const CANCEL_TRANSACTION = 11;
const END_DATA = 12;

const DT_UINT16 = 0x0004;
const DT_UINT32 = 0x0006;
const DT_STRING = 0xffff;

const RC_OK = 0x2001;
const RC_OPERATION_NOT_SUPPORTED = 0x2005;
const RC_SESSION_NOT_OPEN = 0x2003;
const RC_DEVICE_PROP_NOT_SUPPORTED = 0x200a;

const log: string[] = [];
function note(s: string) {
  log.push(s);
  console.log(s);
}

// ---------------------------------------------------------------------------
// 属性表：故意同时放"字符串型快门"和"数值型快门"两种形态，
// 用来验证客户端的"按值的形态识别"是否真的有效。
// ---------------------------------------------------------------------------
const shutterStrings: string[] = [];
for (const den of [1, 2, 4, 8, 15, 30, 60, 125, 250, 500, 1000, 2000, 4000, 8000]) {
  shutterStrings.push(`1/${den}`);
}
shutterStrings.push('0.5"', '1"', '2"', '4"', '8"', '15"', '30"');

const isoValues = [100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600];
const fNumbers = [14, 18, 20, 28, 35, 40, 56, 80]; // ×100

type Prop = {
  code: number;
  type: number;
  getSet: number; // 1 = 可读写
  current: number | string;
  values?: (number | string)[];
};

const props = new Map<number, Prop>();
props.set(0x5007, { code: 0x5007, type: DT_UINT16, getSet: 1, current: 28, values: fNumbers });
props.set(0x500d, {
  code: 0x500d, type: DT_UINT32, getSet: 1, current: 400, // 400 × 10µs = 4ms = 1/250
  values: [10, 20, 40, 80, 125, 250, 400, 500, 1000, 2000, 4000, 8000, 15000, 30000],
});
props.set(0x500f, { code: 0x500f, type: DT_UINT16, getSet: 1, current: 400, values: isoValues });
props.set(0x5010, { code: 0x5010, type: DT_UINT16, getSet: 1, current: 0, values: [-2000, -1000, 0, 1000, 2000].map(v => v & 0xffff) });
// 尼康私有 ShutterSpeed（字符串型）
props.set(0xd100, { code: 0xd100, type: DT_STRING, getSet: 1, current: '1/250', values: shutterStrings });

function u8(v: number) { return Buffer.from([v & 0xff]); }
function u16(v: number) { const b = Buffer.alloc(2); b.writeUInt16LE(v & 0xffff); return b; }
function u32(v: number) { const b = Buffer.alloc(4); b.writeUInt32LE(v >>> 0); return b; }
function u64(v: number) { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(v)); return b; }
function ptpStr(s: string) {
  const body = Buffer.from(s + '\u0000', 'utf16le');
  return Buffer.concat([u8(body.length / 2), body]);
}
function arr16(list: number[]) { return Buffer.concat([u32(list.length), ...list.map(u16)]); }

function encodeValue(type: number, v: number | string): Buffer {
  if (type === DT_STRING) return ptpStr(String(v));
  if (type === DT_UINT16) return u16(Number(v));
  if (type === DT_UINT32) return u32(Number(v));
  return Buffer.alloc(0);
}

function propDesc(p: Prop): Buffer {
  const parts: Buffer[] = [u16(p.code), u16(p.type), u8(p.getSet)];
  parts.push(encodeValue(p.type, p.current)); // factory default
  parts.push(encodeValue(p.type, p.current)); // current
  if (p.values && p.values.length) {
    parts.push(u8(2)); // enum
    parts.push(u16(p.values.length));
    for (const v of p.values) parts.push(encodeValue(p.type, v));
  } else {
    parts.push(u8(0)); // none
  }
  return Buffer.concat(parts);
}

function deviceInfo(): Buffer {
  const parts: Buffer[] = [];
  parts.push(u16(100)); // standard version 1.00
  parts.push(u32(0x0000000a)); // Nikon
  parts.push(u16(100));
  parts.push(ptpStr('Nikon PTP Simulator'));
  parts.push(u16(0)); // functional mode
  parts.push(arr16([0x1001, 0x1002, 0x1003, 0x100e, 0x1014, 0x1015, 0x1016, 0x90c0]));
  parts.push(arr16([0x4002, 0x400d]));
  parts.push(arr16([...props.keys()]));
  parts.push(arr16([0x3801])); // capture formats
  parts.push(arr16([0x3801])); // image formats
  parts.push(ptpStr('Nikon'));
  parts.push(ptpStr('Z 6II SIM'));
  parts.push(ptpStr('1.00'));
  parts.push(ptpStr('SIM0000001'));
  return Buffer.concat(parts);
}

// ---------------------------------------------------------------------------
// 连接状态
// ---------------------------------------------------------------------------
let connectionNumber = 0x12345678;
let sessionOpen = false;
let eventSocket: any = null;
let eventSocketReady = false;

function packet(type: number, body: Buffer): Buffer {
  return Buffer.concat([u32(body.length + 8), u32(type), body]);
}

function send(sock: any, type: number, body: Buffer) {
  try { sock.write(packet(type, body)); } catch (e) { note(`write failed: ${e}`); }
}

function emitEvent(code: number, params: number[] = []) {
  if (!eventSocket || !eventSocketReady) { note(`event 0x${code.toString(16)} dropped (no event channel)`); return; }
  const body = Buffer.concat([u16(code), u32(0x0), ...params.map(u32)]);
  try { eventSocket.write(packet(EVENT, body)); note(`-> EVENT 0x${code.toString(16)}`); }
  catch (e) { note(`event write failed: ${e}`); }
}

// ---------------------------------------------------------------------------
// 读缓冲
// ---------------------------------------------------------------------------
function makeReader(sock: any) {
  let buf = Buffer.alloc(0);
  let waiter: (() => void) | null = null;
  sock.on('data', (d: Buffer) => { buf = Buffer.concat([buf, d]); if (waiter) { const w = waiter; waiter = null; w(); } });
  sock.on('close', () => { buf = Buffer.alloc(0); if (waiter) { const w = waiter; waiter = null; w(); } });
  return async function readExact(n: number): Promise<Buffer | null> {
    while (buf.length < n) {
      if (sock.destroyed) return null;
      await new Promise<void>(r => { waiter = r; setTimeout(r, 50); });
    }
    const out = buf.subarray(0, n);
    buf = buf.subarray(n);
    return Buffer.from(out);
  };
}

async function handleCommandConnection(sock: any, peer: string) {
  note(`[${peer}] command connection opened`);
  const readExact = makeReader(sock);

  // ---- 握手 ----
  const head = await readExact(8);
  if (!head) return;
  const len = head.readUInt32LE(0);
  const type = head.readUInt32LE(4);
  const body = await readExact(len - 8);
  if (!body) return;
  if (type !== INIT_COMMAND_REQUEST) {
    note(`[${peer}] expected InitCommandRequest, got ${type}`);
    return;
  }
  const guid = body.subarray(0, 16).toString('hex');
  const nameLen = body.readUInt8(16);
  const name = body.subarray(17, 17 + nameLen * 2).toString('utf16le').replace(/\u0000+$/, '');
  const ver = body.readUInt32LE(17 + nameLen * 2);
  note(`[${peer}] InitCommandRequest guid=${guid} name="${name}" ver=0x${ver.toString(16)}`);

  const ackBody = Buffer.concat([
    u32(connectionNumber),
    Buffer.from('00112233445566778899aabbccddeeff', 'hex'),
    ptpStr('Z 6II SIM'),
    u32(0x00010000),
  ]);
  send(sock, INIT_COMMAND_ACK, ackBody);
  note(`[${peer}] <- InitCommandAck conn=0x${connectionNumber.toString(16)}`);

  // ---- 主循环 ----
  while (!sock.destroyed) {
    const h = await readExact(8);
    if (!h) break;
    const l = h.readUInt32LE(0);
    const t = h.readUInt32LE(4);
    const b = l > 8 ? await readExact(l - 8) : Buffer.alloc(0);
    if (!b) break;

    if (t === CANCEL_TRANSACTION) { note(`[${peer}] CancelTransaction`); continue; }
    if (t !== OPERATION_REQUEST) { note(`[${peer}] unexpected packet type ${t}`); continue; }

    const phase = b.readUInt32LE(0);
    const opcode = b.readUInt16LE(4);
    const tid = b.readUInt32LE(6);
    const params: number[] = [];
    for (let off = 10; off + 4 <= b.length; off += 4) params.push(b.readUInt32LE(off));
    note(`[${peer}] -> op 0x${opcode.toString(16)} tid=${tid} phase=${phase} params=[${params.map(p => '0x' + p.toString(16)).join(',')}]`);

    // DataOut 段
    let dataIn: Buffer | null = null;
    if (phase === 1) {
      const sh = await readExact(8);
      if (!sh) break;
      const st = sh.readUInt32LE(4);
      if (st !== START_DATA) { note(`[${peer}] expected StartData, got ${st}`); break; }
      const sb = await readExact(12); // tid(u32) + total(u64)
      if (!sb) break;
      const total = Number(sb.readBigUInt64LE(4));
      const chunks: Buffer[] = [];
      let got = 0;
      while (true) {
        const dh = await readExact(8);
        if (!dh) { note('EOF during data phase'); return; }
        const dl = dh.readUInt32LE(0);
        const dt = dh.readUInt32LE(4);
        const db = dl > 8 ? await readExact(dl - 8) : Buffer.alloc(0);
        if (!db) return;
        if (dt === DATA) { chunks.push(db.subarray(4)); got += db.length - 4; }
        else if (dt === END_DATA) { break; }
        else { note(`[${peer}] unexpected data packet type ${dt}`); break; }
      }
      dataIn = Buffer.concat(chunks);
      note(`[${peer}]   dataOut total=${total} got=${got} bytes=${dataIn.toString('hex')}`);
      if (total !== got) note(`[${peer}]   !! length mismatch (declared ${total}, actual ${got})`);
    }

    // 处理
    let rc = RC_OK;
    let out: Buffer | null = null;
    switch (opcode) {
      case 0x1002: sessionOpen = true; break;
      case 0x1003: sessionOpen = false; break;
      case 0x1001: out = deviceInfo(); break;
      case 0x1014: {
        const c = params[0];
        const p = props.get(c);
        if (!p) rc = RC_DEVICE_PROP_NOT_SUPPORTED; else out = propDesc(p);
        break;
      }
      case 0x1015: {
        const c = params[0];
        const p = props.get(c);
        if (!p) rc = RC_DEVICE_PROP_NOT_SUPPORTED; else out = encodeValue(p.type, p.current);
        break;
      }
      case 0x1016: {
        const c = params[0];
        const p = props.get(c);
        if (!p || !dataIn) { rc = RC_DEVICE_PROP_NOT_SUPPORTED; break; }
        // 严格按类型解析，等价于真机校验
        if (p.type === DT_STRING) {
          const n = dataIn.readUInt8(0);
          const s = dataIn.subarray(1, 1 + n * 2).toString('utf16le').replace(/\u0000+$/, '');
          if (!p.values || !p.values.includes(s)) { note(`  value "${s}" not in enum -> 0x201c`); rc = 0x201c; break; }
          p.current = s;
        } else if (p.type === DT_UINT32) {
          const v = dataIn.readUInt32LE(0);
          if (p.values && !p.values.includes(v)) { note(`  value ${v} not in enum -> 0x201c`); rc = 0x201c; break; }
          p.current = v;
        } else if (p.type === DT_UINT16) {
          const v = dataIn.readUInt16LE(0);
          if (p.values && !p.values.includes(v)) { note(`  value ${v} not in enum -> 0x201c`); rc = 0x201c; break; }
          p.current = v;
        }
        note(`  prop 0x${c.toString(16)} set ok -> ${p.current}`);
        break;
      }
      case 0x90c0:
      case 0x100e:
        note('  capture!');
        // 真机语义：曝光完成后才推事件
        setTimeout(() => {
          emitEvent(0x400d); // CaptureComplete
          emitEvent(0x4002, [0x00000001]); // ObjectAdded
        }, 60);
        break;
      default: rc = RC_OPERATION_NOT_SUPPORTED;
    }

    // 数据段（DataIn）
    if (out) {
      const total = out.length;
      for (let off = 0; off < total; off += 4096) {
        const chunk = out.subarray(off, Math.min(off + 4096, total));
        send(sock, DATA, Buffer.concat([u32(tid), chunk]));
      }
    }
    const respBody = Buffer.concat([u16(rc), u32(tid)]);
    send(sock, OPERATION_RESPONSE, respBody);
    note(`[${peer}] <- response 0x${rc.toString(16)} tid=${tid} data=${out ? out.length : 0}B`);
  }
  note(`[${peer}] command connection closed`);
}

async function handleEventConnection(sock: any, peer: string) {
  note(`[${peer}] event connection opened`);
  const readExact = makeReader(sock);
  const h = await readExact(8);
  if (!h) return;
  const l = h.readUInt32LE(0);
  const t = h.readUInt32LE(4);
  const b = l > 8 ? await readExact(l - 8) : Buffer.alloc(0);
  if (!b) return;
  if (t !== INIT_EVENT_REQUEST) { note(`[${peer}] expected InitEventRequest, got ${t}`); return; }
  const conn = b.readUInt32LE(0);
  note(`[${peer}] InitEventRequest conn=0x${conn.toString(16)} (expect 0x${connectionNumber.toString(16)})`);
  if (conn !== connectionNumber) { note('  !! connection number mismatch'); }
  send(sock, INIT_EVENT_ACK, Buffer.alloc(0));
  eventSocket = sock;
  eventSocketReady = true;
  note(`[${peer}] <- InitEventAck, event channel READY`);
}

const server = Bun.listen({
  hostname: '0.0.0.0',
  port: PORT,
  socket: {
    open() {},
    data(sock: any) {},
  },
});

// 用 node 风格 accept 需要 http/net；改用 net.Server 更直接
server.stop(true);

const net = require('node:net');
let eventConnCount = 0;
const srv = net.createServer((sock: any) => {
  const peer = `${sock.remoteAddress}:${sock.remotePort}`;
  // 第一条连接当命令通道，第二条当事件通道；用 init 包类型再确认
  sock.once('data', (first: Buffer) => {
    let type = -1;
    if (first.length >= 8) type = first.readUInt32LE(4);
    sock.pause();
    const rest = first;
    // 把已读到的数据"放回去"：用自建 reader 不方便，这里直接按类型分流并重建 reader
    if (type === INIT_EVENT_REQUEST) {
      eventConnCount++;
      const wrapped = wrapWithPeek(sock, rest);
      handleEventConnection(wrapped.sock, peer);
    } else {
      const wrapped = wrapWithPeek(sock, rest);
      handleCommandConnection(wrapped.sock, peer);
    }
  });
});

function wrapWithPeek(sock: any, peeked: Buffer) {
  // 简单做法：把 peek 到的字节重新 push 回 socket
  sock.unshift(peeked);
  sock.resume();
  return { sock };
}

srv.listen(PORT, '0.0.0.0', () => {
  note(`PTP/IP camera simulator listening on 0.0.0.0:${PORT}`);
  note(`props: ${[...props.keys()].map(k => '0x' + k.toString(16)).join(', ')}`);
});

process.on('SIGTERM', () => { note('shutting down'); srv.close(); process.exit(0); });
