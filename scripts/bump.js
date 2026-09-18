const fs = require('node:fs');
const path = require('node:path');

// npm run bump — increment patch version from package.json, then delegate to version:bump.
// 2.2.4 -> 2.2.5 ; 2.2.4-beta.1 -> 2.2.4-beta.2
const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const m = /^(\d+)\.(\d+)\.(\d+)(-beta\.(\d+))?$/.exec(pkg.version || '');
if (!m) {
  console.error(`Cannot bump: invalid version "${pkg.version}" in package.json`);
  process.exit(1);
}
const next = m[4] != null
  ? `${m[1]}.${m[2]}.${m[3]}-beta.${Number(m[5]) + 1}`
  : `${m[1]}.${m[2]}.${Number(m[3]) + 1}`;
process.argv[2] = next;
require('./bump-version.js');
console.log(`${pkg.version} -> ${next}`);
