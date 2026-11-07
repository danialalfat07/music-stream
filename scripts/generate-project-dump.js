/**
 * generate-project-dump.js
 * Menghasilkan PROJECT_DUMP.md: ringkasan padat + indeks otomatis + lampiran sumber penuh.
 * Tanpa padding artifisial; semua isi berasal dari repo (teks) atau narasi turunan analisis.
 *
 * Jalankan: node scripts/generate-project-dump.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'PROJECT_DUMP.md');
const MIN_BYTES = 2 * 1024 * 1024;
const PKG = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

const SKIP_DIRS = new Set([
  'node_modules', '.git', 'build', 'release', 'cache', '.vercel',
  '.gradle', 'dist', 'coverage', 'bare-browser', '.idea', '.vscode',
]);
const EXTRA_NAMES = new Set([
  'Dockerfile', 'gradlew', 'VERSION', 'LICENSE', 'NOTICE', 'builder.sh',
  '.gitignore', '.prettierrc', '.eslintrc.json',
]);
const TEXT_EXT = /\.(java|kt|xml|gradle|js|cjs|mjs|json|md|html|css|sh|ps1|patch|yml|yaml|txt|properties|pro)$/i;
const SKIP_FILES = new Set(['PROJECT_DUMP.md', 'package-lock.json']);

const EXT_LANG = {
  '.java': 'java', '.kt': 'kotlin', '.js': 'javascript', '.cjs': 'javascript',
  '.mjs': 'javascript', '.json': 'json', '.gradle': 'gradle', '.xml': 'xml',
  '.html': 'html', '.css': 'css', '.md': 'markdown', '.sh': 'bash',
  '.ps1': 'powershell', '.patch': 'diff', '.yml': 'yaml', '.yaml': 'yaml',
  '.txt': 'text', '.properties': 'properties', '.pro': 'proguard',
};

function walk(dir, out) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      walk(path.join(dir, entry.name), out);
      continue;
    }
    if (SKIP_FILES.has(entry.name)) continue;
    if (entry.name.endsWith('.log')) continue;
    if (!TEXT_EXT.test(entry.name) && !EXTRA_NAMES.has(entry.name)) continue;
    out.push(path.join(dir, entry.name));
  }
  return out;
}

const extra = [
  'README.md', 'CHANGELOG.md', 'TODO.md', 'package.json', 'capacitor.config.json',
  'vercel.json', '.eslintrc.json', '.prettierrc', '.gitignore', 'server.js',
  'api/index.js', 'public/sw.js', 'public/manifest.json', 'public/browserconfig.xml',
  'public/.well-known/assetlinks.json', 'public/test/diag-bg.html',
  'docs/architecture.md', 'docs/background-playback-analysis.md',
  'docs/offline-scope.md', 'docs/offline-test-plan.md', 'docs/beta-versioning.md',
  'docs/build.md', 'docs/KEYSTORE.md', 'docs/audit-source.md',
  'docs/bare-patch-analysis.md', 'scripts/FASE0-AUDIT.md',
  'scripts/report-stutter-analysis.md', 'tests/parse.test.js',
  'test/background-test-plan.md', 'test/background-test.html',
  'test/brave-bg-test.html', 'patches/minimal/README.md',
  'patches/minimal/001-background-media-pref.patch',
  'patches/minimal/002-blink-visibility-spoof.patch',
  'patches/minimal/003-media-permission-keep.patch',
  'patches/@capacitor+android+6.2.1.patch',
  'android/app/src/main/AndroidManifest.xml', 'android/app/src/main/assets/capacitor.config.json',
  'bare-browser/README.md', 'bare-browser/BUILDING.md', 'bare-browser/VERSION',
  'bare-browser/NOTICE', 'bare-browser/LICENSE',
  'bare-browser/docs/design.md', 'bare-browser/docs/patches.md',
  'bare-browser/docs/reproducibility.md', 'bare-browser/docs/stage-1-2.md',
  'bare-browser/patches/0043-Keep-media-playing-in-the-background-on-supported-si.patch',
  'bare-browser/patches/0050-Report-the-page-as-visible-while-background-media-pl.patch',
  'bare-browser/patches/0051-Keep-background-playback-permission-when-the-page-is.patch',
  'opencode.txt', 'report.txt', 'files.txt', 'strict_loop_result.txt',
  'frontend_loop.ps1', 'frontend_loop_strict.ps1', 'cdp_client.js', 'cdp_hooks.js',
];

const fileSet = new Set();
for (const rel of extra) {
  const abs = path.join(root, rel);
  if (fs.existsSync(abs)) fileSet.add(abs);
}
walk(root, []).forEach((f) => fileSet.add(f));

const files = [...fileSet]
  .filter((f) => fs.existsSync(f) && fs.statSync(f).size > 0)
  .sort((a, b) => a.localeCompare(b))
  .map((abs) => {
    const rel = path.relative(root, abs).replaceAll(path.sep, '/');
    const buf = fs.readFileSync(abs);
    let content = buf.toString('utf8').replace(/\r\n/g, '\n');
    if (content.includes('\uFFFD')) content = buf.toString('latin1').replace(/\r\n/g, '\n');
    const ext = path.extname(abs).toLowerCase();
    return {
      abs, rel, content,
      bytes: buf.length,
      chars: content.length,
      lines: content.split('\n').length,
      lang: EXT_LANG[ext] || (EXTRA_NAMES.has(path.basename(abs)) ? 'text' : ''),
      sha1: crypto.createHash('sha1').update(buf).digest('hex').slice(0, 12),
      ext,
    };
  });

function sh(cmd, fallback) {
  try {
    return execSync(cmd, { cwd: root, encoding: 'utf8' }).trim();
  } catch {
    return fallback;
  }
}

const git = {
  head: sh('git rev-parse --short HEAD', 'unknown'),
  subject: sh('git log -1 --pretty=%s', 'unknown'),
  branch: sh('git rev-parse --abbrev-ref HEAD', 'unknown'),
  count: sh('git rev-list --count HEAD', '0'),
  dirty: sh('git status --porcelain', '').length > 0,
};

const stats = {
  totalBytes: files.reduce((n, f) => n + f.bytes, 0),
  totalLines: files.reduce((n, f) => n + f.lines, 0),
  byExt: {},
};
for (const f of files) {
  const k = f.ext || path.basename(f.abs);
  stats.byExt[k] = stats.byExt[k] || { n: 0, bytes: 0 };
  stats.byExt[k].n += 1;
  stats.byExt[k].bytes += f.bytes;
}

const evidence = {
  version: PKG.version,
  webVersion: PKG.webVersion,
  name: PKG.name,
  git,
  stats,
  files,
  byRel: (rel) => files.find((f) => f.rel === rel),
};

const narrative = [
  require('./dump-narrative'),
  require('./dump-narrative-b'),
  require('./dump-narrative-c'),
].flatMap((part) => part(evidence));
const indexes = require('./dump-indexes')(evidence);

const out = [];
out.push('# Dnialify Music Stream - PROJECT DUMP');
out.push('');
out.push('Ringkasan proyek padat + indeks + lampiran sumber lengkap.');
out.push('');
out.push('| Field | Nilai |');
out.push('| --- | --- |');
out.push(`| Nama paket | \`${PKG.name}\` |`);
out.push(`| Versi native (APK) | \`${PKG.version}\` |`);
out.push(`| Versi web | \`${PKG.webVersion}\` |`);
out.push(`| Branch / commit | \`${git.branch}\` / \`${git.head}\` |`);
out.push(`| Commit terakhir | ${git.subject.replace(/\|/g, '\\|')} |`);
out.push(`| Total commit | ${git.count} |`);
out.push(`| Working tree | ${git.dirty ? 'ada perubahan lokal' : 'bersih'} |`);
out.push(`| Berkas sumber di dump | ${files.length} |`);
out.push(`| Total baris sumber | ${stats.totalLines.toLocaleString('en-US')} |`);
out.push(`| Ukuran sumber (byte) | ${stats.totalBytes.toLocaleString('en-US')} |`);
out.push(`| Dibuat | ${new Date().toISOString()} |`);
out.push('');
out.push('> Dokumen ini dihasilkan oleh `scripts/generate-project-dump.js`.');
out.push('> Biner (PNG/APK/DEX/JAR/CRX), `node_modules`, `.git`, hasil build, cache lokal, dan log mentah tidak disertakan.');
out.push('> `package-lock.json` juga dilewati (artefak dependency, bukan kode proyek).');
out.push('');
out.push('## Daftar Isi');
out.push('');
out.push('1. Ringkasan Padat (narasi teknis)');
out.push('2. Indeks Otomatis (berkas, API, simbol, kelas Java, storage, DOM, tag log, CSS, patch)');
out.push('3. Lampiran Sumber Penuh (semua berkas teks)');
out.push('');
out.push('---');
out.push('');
out.push(narrative.join('\n'));
out.push('');
out.push('---');
out.push('');
out.push(indexes.join('\n'));
out.push('');
out.push('---');
out.push('');
out.push('# Lampiran: Sumber Penuh');
out.push('');
out.push(`Total ${files.length} berkas, ${stats.totalBytes.toLocaleString('en-US')} byte, ${stats.totalLines.toLocaleString('en-US')} baris.`);
out.push('');
for (const f of files) {
  out.push(`### Path: ${f.rel}`);
  out.push('');
  out.push(`> ${f.bytes} byte | ${f.lines} baris | ${f.lang || 'text'} | sha1:${f.sha1}`);
  out.push('');
  out.push('```' + (f.lang || ''));
  out.push(f.content.replace(/\n+$/, ''));
  out.push('```');
  out.push('');
}

const text = out.join('\n');
fs.writeFileSync(output, text);
const bytes = Buffer.byteLength(text);
console.log(`Wrote ${output}`);
console.log(`Files: ${files.length}`);
console.log(`Bytes: ${bytes} (${(bytes / 1048576).toFixed(2)} MB) min=${MIN_BYTES}`);
if (bytes < MIN_BYTES) {
  console.error(`UKURAN KURANG: ${MIN_BYTES - bytes} byte lagi dibutuhkan.`);
  process.exitCode = 1;
}
