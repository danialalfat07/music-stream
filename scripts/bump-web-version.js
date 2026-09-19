const fs = require('node:fs');
const path = require('node:path');

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+(-beta\.\d+)?$/.test(version || '')) {
  console.error('Usage: npm run bump -- <major.minor.patch>');
  process.exit(1);
}

const root = path.resolve(__dirname, '..');
const packagePath = path.join(root, 'package.json');
const packageData = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
packageData.webVersion = version;
fs.writeFileSync(packagePath, `${JSON.stringify(packageData, null, 2)}\n`);

process.argv[2] = version;
require('./inject-version.js');
