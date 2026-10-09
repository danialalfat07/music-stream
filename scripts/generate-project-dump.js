const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'PROJECT_DUMP.md');

const explicit = [
  'README.md', 'CHANGELOG.md', 'package.json', 'capacitor.config.json',
  'vercel.json', '.eslintrc.json', 'server.js', 'api/index.js',
  'public/app.js', 'public/index.html', 'public/styles.css', 'public/sw.js',
  'public/manifest.json', 'public/diag.html', 'public/diag-hook.js',
  'android/build.gradle', 'android/settings.gradle', 'android/variables.gradle',
  'android/capacitor.settings.gradle', 'android/app/build.gradle',
  'android/app/capacitor.build.gradle', 'android/app/src/main/AndroidManifest.xml',
  'docs/architecture.md', 'docs/background-playback-analysis.md',
  'docs/offline-scope.md', 'docs/offline-test-plan.md', 'scripts/FASE0-AUDIT.md',
  'tests/parse.test.js', 'test/background-test-plan.md',
];

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', '.git', 'build', 'release', 'cache'].includes(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else if (/\.(java|kt|xml|gradle|js|json|md|html|css|sh|ps1)$/.test(entry.name)) out.push(full);
  }
  return out;
}

const files = new Set(explicit.map((p) => path.join(root, p)));
for (const file of walk(path.join(root, 'android', 'app', 'src', 'main'))) files.add(file);
for (const file of walk(path.join(root, 'scripts'))) files.add(file);
for (const file of walk(path.join(root, 'public'))) files.add(file);
for (const file of walk(path.join(root, 'api'))) files.add(file);
for (const file of walk(path.join(root, 'docs'))) files.add(file);
for (const file of walk(path.join(root, 'tests'))) files.add(file);
for (const file of walk(path.join(root, 'test'))) files.add(file);

const extLang = {
  '.java': 'java', '.kt': 'kotlin', '.js': 'javascript', '.json': 'json',
  '.gradle': 'gradle', '.xml': 'xml', '.html': 'html', '.css': 'css',
  '.md': 'markdown', '.sh': 'bash', '.ps1': 'powershell',
};
const sorted = [...files].filter((f) => fs.existsSync(f)).sort();
const lines = [
  '# Dnialify Music Stream: Full Ecosystem Dump',
  '',
  '> Generated from repository source. Generated artifacts, dependencies, binaries, secrets, and local cache files omitted.',
  '',
  '## Project Map',
  '',
  '- `public/`: SPA frontend, service worker, styles, static assets.',
  '- `server.js`: Express API proxy, metadata, lyrics, stream URL resolution, persistent audio cache.',
  '- `android/`: Capacitor Android shell and native playback/cache implementation.',
  '- `scripts/`: diagnostics, integration tests, release/version tooling.',
  '- `docs/`: architecture, background playback, offline/cache design.',
  '',
  '## Source Files',
  '',
];

for (const file of sorted) {
  const rel = path.relative(root, file).replaceAll(path.sep, '/');
  const content = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  const ext = path.extname(file).toLowerCase();
  const lang = extLang[ext] || '';
  const count = content.split('\n').length;
  lines.push(`### Path: ${rel}`);
  if (count > 800) lines.push('> `// [FILE BESAR - FULL]` Full file included below.');
  lines.push('', `\`\`\`${lang}`, content.replace(/\n$/, ''), '\`\`\`', '');
}

fs.writeFileSync(output, lines.join('\n'));
console.log(`Wrote ${output} (${sorted.length} files)`);
