const fs = require('node:fs');
const path = require('node:path');

// npm run bump [version] - bump frontend version without changing APK version.
const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const current = pkg.webVersion || pkg.version;
const requested = process.argv[2];
const m = /^(\d+)\.(\d+)\.(\d+)(-beta\.(\d+))?$/.exec(current || '');
if (!m) {
  console.error(`Cannot bump: invalid webVersion "${current}" in package.json`);
  process.exit(1);
}
const next = requested || (m[4] != null
  ? `${m[1]}.${m[2]}.${m[3]}-beta.${Number(m[5]) + 1}`
  : `${m[1]}.${m[2]}.${Number(m[3]) + 1}`);
if (!/^(\d+)\.(\d+)\.(\d+)(-beta\.\d+)?$/.test(next)) {
  console.error('Usage: npm run bump -- <major.minor.patch>');
  process.exit(1);
}
process.argv[2] = next;
require('./bump-web-version.js');
console.log(`${current} -> ${next} (APK remains ${pkg.version})`);
