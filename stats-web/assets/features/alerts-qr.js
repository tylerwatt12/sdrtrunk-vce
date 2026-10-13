import qrcode from '../vendor/qrcode-generator.v1.4.4.js';

export function enrollmentQrMatrix(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.href.length > 2048) throw new Error('This connection link is unavailable.');
  const code = qrcode(0, 'M');
  code.addData(url.href, 'Byte');
  code.make();
  return Array.from({ length: code.getModuleCount() }, (_, y) =>
    Array.from({ length: code.getModuleCount() }, (_, x) => code.isDark(y, x)));
}

export function enrollmentQrCanvas(documentValue, value) {
  const matrix = enrollmentQrMatrix(value);
  const quiet = 4;
  const scale = 5;
  const canvas = documentValue.createElement('canvas');
  canvas.className = 'ui-qr-code';
  canvas.width = canvas.height = (matrix.length + quiet * 2) * scale;
  canvas.setAttribute('role', 'img');
  canvas.setAttribute('aria-label', 'Scan to connect a phone on RadioResolve');
  const context = canvas.getContext('2d');
  const colors = documentValue.defaultView.getComputedStyle(documentValue.documentElement);
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = colors.getPropertyValue('--qr-code-ink').trim();
  matrix.forEach((row, y) => row.forEach((dark, x) => {
    if (dark) context.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale);
  }));
  return canvas;
}
