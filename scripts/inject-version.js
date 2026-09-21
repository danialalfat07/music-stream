const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const ver = pkg.webVersion || pkg.version;
if (!/^\d+\.\d+\.\d+(-beta\.\d+)?$/.test(ver)) {
  console.error('Invalid version in package.json:', ver);
  process.exit(1);
}
const isBeta = ver.includes('-beta');
const channel = isBeta ? 'beta' : 'stable';

// public/app.js
const appPath = path.join(root, 'public', 'app.js');
let appSrc = fs.readFileSync(appPath, 'utf8');
appSrc = appSrc.replace(/const APP_VERSION = "[^"]+";/, `const APP_VERSION = "${ver}";`);
if (!appSrc.includes('const APP_VERSION')) throw new Error('APP_VERSION not found in app.js');
fs.writeFileSync(appPath, appSrc);

// public/index.html - meta + VER var (header no longer carries version)
const htmlPath = path.join(root, 'public', 'index.html');
let html = fs.readFileSync(htmlPath, 'utf8');
html = html.replace(/<meta name="app-version" content="[^"]+" \/>/, `<meta name="app-version" content="${ver}" />`);
html = html.replace(/var VER="[^"]+";/, `var VER="${ver}";`);
fs.writeFileSync(htmlPath, html);

// public/sw.js
const swPath = path.join(root, 'public', 'sw.js');
let sw = fs.readFileSync(swPath, 'utf8');
sw = sw.replace(/const CACHE='[^']+';/, `const CACHE='dnialify-assets-v${ver}';`);
fs.writeFileSync(swPath, sw);

// public/manifest.json version field
const manifestPath = path.join(root, 'public', 'manifest.json');
if (fs.existsSync(manifestPath)) {
  const m = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  m.version = ver;
  fs.writeFileSync(manifestPath, JSON.stringify(m, null, 2) + '\n');
}

// also sync android versionName via build.gradle reading package.json (no hardcode needed), but log
console.log(`Injected version ${ver} channel=${channel} -> app.js, index.html, sw.js, manifest.json (android versionName reads package.json)`);
