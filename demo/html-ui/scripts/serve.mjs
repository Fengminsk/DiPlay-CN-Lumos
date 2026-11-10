/**
 * Zero-dependency dev server for the UI lab.
 *
 *   npm run dev
 *
 * Serves the folder on 127.0.0.1, injects a tiny live-reload client into HTML,
 * and pushes a reload whenever css/js/html changes. No install step, no bundler.
 */

import http from 'node:http';
import { createReadStream, existsSync, readFileSync, statSync, watch } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
};

const RELOAD_CLIENT = `
<script>
  /* dev-only live reload, injected by scripts/serve.mjs */
  (() => {
    const source = new EventSource('/__reload');
    source.onmessage = () => location.reload();
  })();
</script>
`;

const clients = new Set();

function broadcastReload() {
  for (const client of clients) client.write('data: reload\n\n');
}

let debounce = 0;
try {
  watch(root, { recursive: true }, (_event, file) => {
    if (!file) return;
    if (!/\.(html|css|js)$/i.test(file)) return;
    if (/(^|[\\/])(\.git|node_modules|preview)([\\/]|$)/.test(file)) return;
    clearTimeout(debounce);
    debounce = setTimeout(broadcastReload, 90);
  });
} catch (error) {
  console.warn('[lab] live reload unavailable:', error.message);
}

function send(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', 'http://localhost');

  if (url.pathname === '/__reload') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-store',
      Connection: 'keep-alive',
    });
    res.write('retry: 1000\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }

  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/' || pathname === '') pathname = '/index.html';

  const file = normalize(join(root, pathname));
  if (file !== root && !file.startsWith(root + sep)) {
    send(res, 403, 'Forbidden');
    return;
  }
  if (!existsSync(file) || !statSync(file).isFile()) {
    send(res, 404, 'Not found: ' + pathname);
    return;
  }

  const ext = extname(file).toLowerCase();
  res.setHeader('Content-Type', MIME[ext] || 'application/octet-stream');
  res.setHeader('Cache-Control', 'no-store');

  if (ext === '.html') {
    const html = readFileSync(file, 'utf8');
    res.end(html.includes('</body>') ? html.replace('</body>', RELOAD_CLIENT + '</body>') : html);
    return;
  }

  createReadStream(file).pipe(res);
});

const startPort = Number(process.env.PORT) || 5173;

server.on('error', (error) => {
  if (error.code === 'EADDRINUSE' && server.listening === false) {
    const next = Number(server.__port || startPort) + 1;
    if (next > startPort + 10) {
      console.error('[lab] no free port between ' + startPort + ' and ' + (startPort + 10));
      process.exit(1);
    }
    server.__port = next;
    server.listen(next, '127.0.0.1');
    return;
  }
  throw error;
});

server.__port = startPort;
server.on('listening', () => {
  const { port } = server.address();
  console.log('');
  console.log('  DiPlay · Lumos UI Lab');
  console.log('  →  http://127.0.0.1:' + port);
  console.log('');
  console.log('  T  tweaks   C  clean   V  compare   Esc  close');
  console.log('  live reload: css / js / html');
  console.log('');
});

server.listen(startPort, '127.0.0.1');
