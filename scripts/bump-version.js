const fs = require('node:fs');
const path = require('node:path');

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+(-beta\.\d+)?$/.test(version || '')) {
  console.error('Usage: npm run version:bump -- <major.minor.patch> or <major.minor.patch-beta.N>');
  process.exit(1);
}

const root = path.resolve(__dirname, '..');
const packagePath = path.join(root, 'package.json');
const packageData = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
const previous = packageData.version;
packageData.version = version;
fs.writeFileSync(packagePath, `${JSON.stringify(packageData, null, 2)}\n`);

const lockPath = path.join(root, 'package-lock.json');
const lockData = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
lockData.version = version;
if (lockData.packages?.['']) lockData.packages[''].version = version;
fs.writeFileSync(lockPath, `${JSON.stringify(lockData, null, 2)}\n`);

// delegate injection to single source of truth script
require('./inject-version.js');
