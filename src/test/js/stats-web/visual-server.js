'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const repository = path.resolve(__dirname, '../../../..');
const assets = path.join(repository, 'stats-web', 'assets');
const fixtures = path.join(__dirname, 'visual', 'fixtures');
const port = Number(process.env.PORT || 4173);

const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
]);

function contained(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative);
}

function target(requestUrl) {
  const pathname = new URL(requestUrl, `http://127.0.0.1:${port}`).pathname;
  if(pathname === '/' || pathname === '/design-system.html') {
    return path.join(fixtures, 'design-system-gallery.html');
  }
  if(pathname.startsWith('/assets/')) {
    const candidate = path.resolve(assets, pathname.slice('/assets/'.length));
    return contained(assets, candidate) ? candidate : null;
  }
  if(pathname.startsWith('/visual/')) {
    const candidate = path.resolve(fixtures, pathname.slice('/visual/'.length));
    return contained(fixtures, candidate) ? candidate : null;
  }
  return null;
}

const server = http.createServer((request, response) => {
  const file = target(request.url || '/');
  if(!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found');
    return;
  }
  response.writeHead(200, {
    'Cache-Control': 'no-store',
    'Content-Type': contentTypes.get(path.extname(file)) || 'application/octet-stream',
  });
  fs.createReadStream(file).pipe(response);
});

server.listen(port, '127.0.0.1');
for(const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
