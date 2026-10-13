import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = process.env.ALERT_QR_PACKAGE_ROOT || path.join(root, 'node_modules/qrcode-generator');
const metadata = JSON.parse(await fs.readFile(path.join(packageRoot, 'package.json'), 'utf8'));
if (metadata.name !== 'qrcode-generator' || metadata.version !== '1.4.4') throw new Error('Expected qrcode-generator 1.4.4.');
const source = await fs.readFile(path.join(packageRoot, 'qrcode.js'), 'utf8');
const bundle = '/*! qrcode-generator 1.4.4; MIT; see qrcode-generator.LICENSE.txt. */\n' + source.trimEnd() + '\nexport default qrcode;\n';
const target = path.join(root, 'stats-web/assets/vendor/qrcode-generator.v1.4.4.js');
if (process.argv.includes('--check')) {
  if (await fs.readFile(target, 'utf8') !== bundle) throw new Error('Rebuild the alert QR vendor file.');
} else await fs.writeFile(target, bundle);
