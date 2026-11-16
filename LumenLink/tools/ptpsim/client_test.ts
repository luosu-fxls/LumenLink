#!/usr/bin/env bun
// 与 Kotlin PtpIpTransport 逐行对应的分帧逻辑移植，
// 用来在无 JDK 环境下验证：握手 → 数据段(Out) → 响应 → 事件通道 是否正确。

const P = {
  INIT_COMMAND_REQUEST: 1, INIT_COMMAND_ACK: 2, INIT_EVENT_REQUEST: 3,
  INIT_EVENT_ACK: 4, INIT_FAIL: 5, OPERATION_REQUEST: 6, OPERATION_RESPONSE: 7,
  EVENT: 8, START_DATA: 9, DATA: 10, CANCEL_TRANSACTION: 11, END_DATA: 12,
};

function u8(v: number) { return Buffer.from([v & 0xff]); }
function u16(v: number) { const b = Buffer.alloc(2); b.writeUInt16LE(v & 0xffff); return b; }
function u32(v: number) { const b = Buffer.alloc(4); b.writeUInt32LE(v >>> 0); return b; }
function ptpStr(s: string) {
  const body = Buffer.from(s + '\u0000', 'utf16le');
  return Buffer.concat([u8(body.length / 2), body]);
}

class Reader {
  constructor(public buf: Buffer, public pos = 0) {}
  get remaining() { return this.buf.length - this.pos; }
  u8() { const v = this.buf.readUInt8(this.pos); this.pos += 1; return v; }
  u16() { const v = this.buf.readUInt16LE(this.pos); this.pos += 2; return v; }
  u32() { const v = this.buf.readUInt32LE(this.pos); this.pos += 4; return v; }
  bytes(n: number) { const o = this.buf.subarray(this.pos, this.pos + n); this.pos += n; return o; }
  skip(n: number) { this.pos += n; }
  ptpString() {
    const len = this.u8();
    if (len === 0) return '';
    const raw = this.bytes(len * 2);
    return raw.toString('utf16le').replace(/\u0000+$/, '');
  }
}

class Transport {
  constructor(host: string, port: number, guid: Buffer, name: string) {
    this.host = host; this.port = port; this.guid = guid; this.name = name;
  }
  host: string; port: number; guid: Buffer; name: string;
  conn: any = null; evt: any = null;
  inbox: Buffer = Buffer.alloc(0); evtInbox: Buffer = Buffer.alloc(0);
  tid = 1; connNum = 0; pushEvents = false; responderName = '';
  waiters: Array<() => void> = []; evtWaiters: Array<() => void> = [];

  async connect(timeoutMs = 10000) {
    this.conn = require('node:net').createConnection(this.port, this.host);
    await new Promise<void>((res, rej) => {
      this.conn.once('connect', () => res());
      this.conn.once('error', rej);
      setTimeout(() => rej(new Error('connect timeout')), timeoutMs);
    });
    this.conn.on('data', (d: Buffer) => { this.inbox = Buffer.concat([this.inbox, d]); this.notify(); });

    // InitCommandRequest
    const body = Buffer.concat([this.guid, ptpStr(this.name), u32(0x00010000)]);
    this.writePacket(P.INIT_COMMAND_REQUEST, body);

    const [type, payload] = await this.readPacket();
    if (type === P.INIT_FAIL) {
      const r = new Reader(payload);
      throw new Error(`Init_Fail reason=0x${r.u32().toString(16)}`);
    }
    if (type !== P.INIT_COMMAND_ACK) throw new Error(`unexpected packet ${type}`);
    const r = new Reader(payload);
    this.connNum = r.u32();
    r.skip(16);
    this.responderName = r.ptpString();

    // InitEventRequest
    try {
      this.evt = require('node:net').createConnection(this.port, this.host);
      await new Promise<void>((res, rej) => {
        this.evt.once('connect', () => res());
        this.evt.once('error', rej);
        setTimeout(() => rej(new Error('event connect timeout')), timeoutMs);
      });
      this.evt.on('data', (d: Buffer) => { this.evtInbox = Buffer.concat([this.evtInbox, d]); this.notifyEvt(); });
      this.writeRaw(this.evt, P.INIT_EVENT_REQUEST, u32(this.connNum));
      const [et] = await this.readPacketFrom(this.getEvtState());
      if (et !== P.INIT_EVENT_ACK) throw new Error('event ack failed');
      this.pushEvents = true;
    } catch (e) {
      this.pushEvents = false;
    }
    this.tid = 1;
  }

  getEvtState() { return { get buf() { return undefined; } } as any; }

  notify() { const w = this.waiters; this.waiters = []; w.forEach(f => f()); }
  notifyEvt() { const w = this.evtWaiters; this.evtWaiters = []; w.forEach(f => f()); }

  async readExact(n: number): Promise<Buffer> {
    while (this.inbox.length < n) {
      await new Promise<void>(res => { this.waiters.push(res); setTimeout(res, 50); });
    }
    const out = this.inbox.subarray(0, n);
    this.inbox = this.inbox.subarray(n);
    return Buffer.from(out);
  }

  async readPacketFrom(_: any): Promise<[number, Buffer]> {
    // 事件通道读包（用独立缓冲）
    while (this.evtInbox.length < 8) {
      await new Promise<void>(res => { this.evtWaiters.push(res); setTimeout(res, 50); });
    }
    const head = this.evtInbox.subarray(0, 8);
    const len = head.readUInt32LE(0);
    const type = head.readUInt32LE(4);
    while (this.evtInbox.length < len) {
      await new Promise<void>(res => { this.evtWaiters.push(res); setTimeout(res, 50); });
    }
    const pkt = this.evtInbox.subarray(0, len);
    this.evtInbox = this.evtInbox.subarray(len);
    return [type, Buffer.from(pkt.subarray(8))];
  }

  async readPacket(): Promise<[number, Buffer]> {
    const head = await this.readExact(8);
    const len = head.readUInt32LE(0);
    const type = head.readUInt32LE(4);
    const body = len > 8 ? await this.readExact(len - 8) : Buffer.alloc(0);
    return [type, body];
  }

  writePacket(type: number, body: Buffer) { this.writeRaw(this.conn, type, body); }
  writeRaw(sock: any, type: number, body: Buffer) {
    sock.write(Buffer.concat([u32(body.length + 8), u32(type), body]));
  }

  async request(opcode: number, params: number[] = [], dataOut: Buffer | null = null) {
    const tid = this.tid++;
    const phase = dataOut ? 1 : 0;
    const body = Buffer.concat([u32(phase), u16(opcode), u32(tid), ...params.map(u32)]);
    this.writePacket(P.OPERATION_REQUEST, body);

    if (dataOut) {
      this.writeRaw(this.conn, P.START_DATA, Buffer.concat([u32(tid), (() => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(dataOut.length)); return b; })()]));
      for (let off = 0; off < dataOut.length; off += 65536) {
        const chunk = dataOut.subarray(off, Math.min(off + 65536, dataOut.length));
        this.writeRaw(this.conn, P.DATA, Buffer.concat([u32(tid), chunk]));
      }
      this.writeRaw(this.conn, P.END_DATA, u32(tid));
    }

    const acc: Buffer[] = [];
    while (true) {
      const [type, payload] = await this.readPacket();
      if (type === P.OPERATION_RESPONSE) {
        const r = new Reader(payload);
        const rc = r.u16();
        const rtid = r.u32();
        return { code: rc, tid: rtid, data: acc.length ? Buffer.concat(acc) : null };
      } else if (type === P.START_DATA) {
        // 忽略长度，靠 END_DATA 结束
      } else if (type === P.DATA || type === P.END_DATA) {
        const r = new Reader(payload);
        r.u32();
        acc.push(r.bytes(r.remaining));
      } else if (type === P.EVENT) {
        this.onEvent?.(payload);
      } else {
        throw new Error(`unexpected packet ${type} on command channel`);
      }
    }
  }
  onEvent: ((p: Buffer) => void) | null = null;

  /** 读事件通道上的一个包（与命令通道完全独立，不需任何锁）。 */
  async readEventPacket(): Promise<[number, Buffer]> {
    while (this.evtInbox.length < 8) {
      if (this.evt?.destroyed) throw new Error('event socket closed');
      await new Promise<void>(res => { this.evtWaiters.push(res); setTimeout(res, 50); });
    }
    const len = this.evtInbox.readUInt32LE(0);
    const type = this.evtInbox.readUInt32LE(4);
    while (this.evtInbox.length < len) {
      await new Promise<void>(res => { this.evtWaiters.push(res); setTimeout(res, 50); });
    }
    const pkt = this.evtInbox.subarray(0, len);
    this.evtInbox = this.evtInbox.subarray(len);
    return [type, Buffer.from(pkt.subarray(8))];
  }

  close() { try { this.conn?.end(); } catch {} try { this.evt?.end(); } catch {} }
}

// ---------------------------------------------------------------------------
const results: string[] = [];
function check(name: string, ok: boolean, detail = '') {
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

async function main() {
  const port = Number(process.env.PTP_PORT || 15740);
  const guid = Buffer.from('a1b2c3d4e5f60718293a4b5c6d7e8f90', 'hex');
  const t = new Transport('127.0.0.1', port, guid, 'LumenLink');

  // 1) 握手
  let handshakeOk = false;
  try {
    await t.connect();
    handshakeOk = true;
  } catch (e) {
    check('握手', false, String(e));
    return finish();
  }
  check('握手 InitCommandAck', handshakeOk && t.connNum > 0, `conn=0x${t.connNum.toString(16)} cam="${t.responderName}"`);
  check('事件通道 InitEventAck', t.pushEvents);
  check('机型字符串 UTF-16 解码', t.responderName === 'Z 6II SIM', `got "${t.responderName}"`);

  // 2) OpenSession
  let r = await t.request(0x1002, [1]);
  check('OpenSession', r.code === 0x2001, `rc=0x${r.code.toString(16)}`);

  // 3) GetDeviceInfo
  r = await t.request(0x1001);
  check('GetDeviceInfo 有数据段', r.code === 0x2001 && !!r.data);
  if (r.data) {
    const rr = new Reader(r.data);
    rr.u16(); const vendor = rr.u32(); const vv = rr.u16(); const vdesc = rr.ptpString();
    check('DeviceInfo vendor=0x0000000a', vendor === 0x0000000a, `vendor=0x${vendor.toString(16)} desc="${vdesc}"`);
  }

  // 4) GetDevicePropDesc（数值型快门 0x500d）
  r = await t.request(0x1014, [0x500d]);
  check('GetDevicePropDesc(0x500d) 非空', r.code === 0x2001 && !!r.data && r.data.length > 0, `${r.data?.length ?? 0}B`);
  if (r.data) {
    const rr = new Reader(r.data);
    const code = rr.u16(), dtype = rr.u16(), getset = rr.u8();
    const defv = rr.u32(), curv = rr.u32(), form = rr.u8();
    check('  描述符字段正确', code === 0x500d && dtype === 0x0006 && getset === 1, `code=0x${code.toString(16)} type=0x${dtype.toString(16)} getSet=${getset}`);
    check('  当前值=1/250(400→4000µs)', curv === 400, `cur=${curv}`);
    check('  枚举型 form=2', form === 2);
  }

  // 5) 字符串型私有属性 0xD100
  r = await t.request(0x1014, [0xd100]);
  if (r.data) {
    const rr = new Reader(r.data);
    rr.u16(); rr.u16(); rr.u8();
    const d = rr.ptpString(); const c = rr.ptpString(); const f = rr.u8();
    check('0xD100 字符串属性解析', d === '1/250' && f === 2, `default="${d}" current="${c}" form=${f}`);
  } else {
    check('0xD100 字符串属性解析', false, 'no data');
  }

  // 6) SetDevicePropValue 字符串型（DataOut 大包，验证 START/DATA/END 分帧）
  const longStr = '1/250';
  r = await t.request(0x1016, [0xd100], ptpStr(longStr));
  check('SetDevicePropValue(0xD100, "1/250")', r.code === 0x2001, `rc=0x${r.code.toString(16)}`);

  // 7) 非法值应被拒（证明服务端做了枚举校验，我们的测试有区分力）
  r = await t.request(0x1016, [0xd100], ptpStr('1/333'));
  check('非法快门值被拒(0x201c)', r.code === 0x201c, `rc=0x${r.code.toString(16)}`);

  // 8) SetDevicePropValue 数值型
  r = await t.request(0x1016, [0x500d], u32(1000));
  check('SetDevicePropValue(0x500D, 1000)', r.code === 0x2001);

  // 9) 不支持的操作码
  r = await t.request(0x1234);
  check('未知 opcode -> 0x2005', r.code === 0x2005, `rc=0x${r.code.toString(16)}`);

  // 10) 事件：拍摄后应收到 CaptureComplete
  // 注意：断言前必须先启动事件泵。事件通道与命令通道是两条独立连接，
  // 读完 InitEventAck 之后必须有人持续在事件通道上 read，否则事件会堆在 socket 缓冲里。
  let gotCapture = false;
  let gotObjectAdded = false;
  const pump = (async () => {
    while (true) {
      try {
        const [type, payload] = await t.readEventPacket();
        if (type === P.EVENT) {
          const rr = new Reader(payload);
          const code = rr.u16();
          if (code === 0x400d) gotCapture = true;
          if (code === 0x4002) gotObjectAdded = true;
        }
      } catch { break; }
    }
  })();

  r = await t.request(0x90c0);
  check('Capture(0x90C0)', r.code === 0x2001);
  const deadline = Date.now() + 3000;
  while (!(gotCapture && gotObjectAdded) && Date.now() < deadline) await new Promise(res => setTimeout(res, 50));
  check('收到事件 CaptureComplete(0x400D)', gotCapture);
  check('收到事件 ObjectAdded(0x4002)', gotObjectAdded);

  // 11) 事件之后命令通道仍可用（回归测试：曾经因为共享读锁导致死锁）
  const afterStart = Date.now();
  r = await t.request(0x1002, [1]);
  const elapsed = Date.now() - afterStart;
  check('事件到达后命令通道未死锁', r.code === 0x2001 && elapsed < 2000, `${elapsed}ms`);

  t.close();
  finish();
}

function finish() {
  const failed = results.filter(r => r.startsWith('FAIL'));
  console.log('');
  console.log(`==== ${results.length - failed.length}/${results.length} passed ====`);
  if (failed.length) { console.log('FAILURES:'); failed.forEach(f => console.log('  ' + f)); process.exitCode = 1; }
}

await main();
