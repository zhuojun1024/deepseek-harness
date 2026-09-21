import net from "node:net";
import tls from "node:tls";

function connect(host, port) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host: "127.0.0.1", port: 7897 }, () => {
      sock.write(`CONNECT ${host}:${port} HTTP/1.1\r\nHost: ${host}:${port}\r\n\r\n`);
    });
    let buf = "";
    sock.once("data", function onData(chunk) {
      buf += chunk.toString("latin1");
      const idx = buf.indexOf("\r\n\r\n");
      if (idx === -1) return;
      const head = buf.slice(0, idx);
      const rest = Buffer.from(buf.slice(idx + 4), "latin1");
      const status = head.split("\r\n")[0];
      if (!/HTTP\/1\.[01] 200/.test(status)) {
        sock.destroy();
        return reject(new Error("CONNECT rejected: " + status + "\n" + head));
      }
      const tlsSock = tls.connect({ host, socket: sock, servername: host }, () => {
        if (rest.length) tlsSock.unshift(rest);
        resolve(tlsSock);
      });
      tlsSock.on("error", reject);
    });
    sock.on("error", reject);
  });
}

function dechunk(buf) {
  const out = [];
  let i = 0;
  while (i < buf.length) {
    const lineEnd = buf.indexOf("\r\n", i);
    if (lineEnd === -1) break;
    const size = parseInt(buf.slice(i, lineEnd).toString().split(";")[0], 16);
    if (isNaN(size) || size === 0) break;
    const start = lineEnd + 2;
    out.push(buf.slice(start, start + size));
    i = start + size + 2;
  }
  return Buffer.concat(out);
}

async function get(host, path) {
  const sock = await connect(host, 443);
  const req = `GET ${path} HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: dsh-upstream-check\r\nAccept: application/json\r\nConnection: close\r\n\r\n`;
  await new Promise((res, rej) => { sock.write(req, () => res()); sock.on("error", rej); });
  const chunks = [];
  await new Promise((res) => {
    sock.on("data", (c) => chunks.push(c));
    sock.on("end", res);
    sock.on("error", res);
    setTimeout(res, 30000);
  });
  sock.destroy();
  const raw = Buffer.concat(chunks);
  const headEnd = raw.indexOf("\r\n\r\n");
  const head = raw.slice(0, headEnd).toString();
  const statusLine = head.split("\r\n")[0];
  const isChunked = /chunked/i.test(head);
  const body = isChunked ? dechunk(raw.slice(headEnd + 4)) : raw.slice(headEnd + 4);
  return { statusLine, body: body.toString() };
}

// try direct first, then proxy
let res;
try {
  const http = await import("node:https");
  res = await new Promise((resolve) => {
    const r = http.get("https://registry.npmjs.org/@deepseek-ai%2fdsh", { headers: { "User-Agent": "dsh-check", "Accept": "application/json" } }, (resp) => {
      let d = "";
      resp.on("data", (c) => (d += c));
      resp.on("end", () => resolve({ statusLine: resp.statusCode + " " + resp.statusMessage, body: d, via: "direct" }));
    });
    r.on("error", (e) => resolve({ statusLine: "ERR " + e.message, body: "", via: "direct" }));
    setTimeout(() => resolve({ statusLine: "TIMEOUT", body: "", via: "direct" }), 8000);
  });
} catch (e) { res = { statusLine: "ERR " + e.message, body: "", via: "direct" }; }
console.log("DIRECT:", res.via, res.statusLine, "len=" + res.body.length);
if (!/^200/.test(res.statusLine)) {
  res = await get("registry.npmjs.org", "/@deepseek-ai%2fdsh");
  console.log("PROXY:", res.statusLine, "len=" + res.body.length);
}
try {
  const j = JSON.parse(res.body);
  const versions = Object.keys(j.versions || {});
  console.log("total versions:", versions.length);
  console.log("last 12:", versions.slice(-12).join(", "));
  console.log("has 0.1.6-alpha.2:", versions.includes("0.1.6-alpha.2"));
  console.log("has 0.1.6-alpha.1:", versions.includes("0.1.6-alpha.1"));
} catch (e) {
  console.log("PARSE FAIL:", e.message, res.body.slice(0, 200));
}
