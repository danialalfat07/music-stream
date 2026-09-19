const { execFileSync } = require('node:child_process');

// npm run release - bump, show changes, commit, push to main. No force, no amend, no reset.
// Aborts if the tree contains changes outside the version-file set.
const VERSION_FILES = new Set([
  'package.json',
  'package-lock.json',
  'public/app.js',
  'public/index.html',
  'public/sw.js',
  'public/manifest.json',
]);

function sh(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { encoding: 'utf8', ...opts }).trim();
}

function changedFiles() {
  const out = sh('git', ['status', '--porcelain']);
  if (!out) return [];
  return out.split('\n').map((l) => l.slice(3).trim()).filter(Boolean);
}

try {
  const before = changedFiles();
  const unrelated = before.filter((f) => !VERSION_FILES.has(f));
  if (unrelated.length) {
    console.error('Refusing release: unrelated working-tree changes present:');
    unrelated.forEach((f) => console.error(`  ${f}`));
    console.error('Commit/stash those first, then re-run.');
    process.exit(1);
  }
  console.log('--- bump ---');
  sh('node', ['scripts/bump.js'], { stdio: 'inherit' });
  const pkg = require('../package.json');
  console.log('--- diff stat ---');
  sh('git', ['diff', '--stat'], { stdio: 'inherit' });
  console.log('--- status ---');
  sh('git', ['status', '--short'], { stdio: 'inherit' });
  const after = changedFiles();
  const stillUnrelated = after.filter((f) => !VERSION_FILES.has(f));
  if (stillUnrelated.length) {
    console.error('Refusing commit: unexpected files changed:');
    stillUnrelated.forEach((f) => console.error(`  ${f}`));
    process.exit(1);
  }
  if (!after.length) {
    console.error('Nothing to commit after bump.');
    process.exit(1);
  }
  console.log('--- commit ---');
  sh('git', ['add', ...[...VERSION_FILES].filter((f) => after.includes(f))]);
  sh('git', ['commit', '-m', `v${pkg.version}`]);
  console.log('--- push origin main ---');
  sh('git', ['push', 'origin', 'main'], { stdio: 'inherit' });
  console.log(`Released v${pkg.version}`);
} catch (e) {
  console.error(`release FAILED: ${(e && e.message) || e}`);
  if (e && e.stdout) console.error(e.stdout);
  if (e && e.stderr) console.error(e.stderr);
  process.exit(1);
}
