/**
 * dump-indexes.js - indeks otomatis dari berkas yang disertakan.
 * Semua angka dihitung dari isi berkas nyata (bukan hardcode).
 */
const fs = require('fs');
const path = require('path');

const CTRL = new Set([
  'if', 'for', 'while', 'switch', 'catch', 'return', 'do', 'else', 'try',
  'synchronized', 'new', 'throw', 'case', 'super', 'this',
]);

function lineNumbersOf(content) {
  return content.split('\n');
}

function jsSymbols(content) {
  const lines = lineNumbersOf(content);
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i];
    let m;
    if ((m = /^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/.exec(t))) {
      out.push({ ln: i + 1, kind: 'fn', name: m[1] });
    } else if ((m = /^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?function\b/.exec(t))) {
      out.push({ ln: i + 1, kind: 'fn', name: m[1] });
    } else if ((m = /^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?\(/.exec(t))) {
      out.push({ ln: i + 1, kind: 'fn', name: m[1] });
    } else if ((m = /^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*\{/.exec(t))) {
      out.push({ ln: i + 1, kind: 'obj', name: m[1] });
    } else if ((m = /^class\s+([A-Za-z_$][\w$]*)/.exec(t))) {
      out.push({ ln: i + 1, kind: 'class', name: m[1] });
    } else if ((m = /^(?:const|let|var)\s+([A-Z0-9_]{2,})\s*=/.exec(t))) {
      out.push({ ln: i + 1, kind: 'const', name: m[1] });
    }
  }
  return out;
}

function javaSymbols(content) {
  const lines = lineNumbersOf(content);
  const classes = [];
  const methods = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    let m;
    if ((m = /^(?:(?:public|private|protected|static|final|abstract)\s+)*(?:class|interface|enum)\s+(\w+)/.exec(t))) {
      classes.push({ ln: i + 1, name: m[1] });
      continue;
    }
    if (/^(?:public|private|protected|static|final|synchronized|abstract|native|default)\b/.test(t) && t.includes('(') && !t.includes('=')) {
      const mm = /([A-Za-z_$][\w$]*)\s*\([^;]*\)\s*(?:throws [\w., ]+)?\s*\{?\s*$/.exec(t);
      if (mm && !CTRL.has(mm[1])) {
        methods.push({ ln: i + 1, name: mm[1] });
      }
    }
  }
  return { classes, methods };
}

module.exports = function buildIndexes(ev) {
  const L = [];
  const p = (...xs) => L.push(...xs);
  const files = ev.files;

  p('# Indeks Otomatis');
  p('');
  p('## I.1 Inventaris Berkas');
  p('');
  p('| # | Path | Byte | Baris | Bahasa | sha1 |');
  p('| ---: | --- | ---: | ---: | --- | --- |');
  files.forEach((f, i) => {
    p(`| ${i + 1} | \`${f.rel}\` | ${f.bytes} | ${f.lines} | ${f.lang || 'text'} | \`${f.sha1}\` |`);
  });
  p('');

  p('## I.2 Ringkasan per Ekstensi');
  p('');
  p('| Ekstensi | Berkas | Byte |');
  p('| --- | ---: | ---: |');
  Object.keys(ev.stats.byExt).sort().forEach((k) => {
    const v = ev.stats.byExt[k];
    p(`| ${k} | ${v.n} | ${v.bytes} |`);
  });
  p('');

  // JS symbol index for large JS files
  p('## I.3 Indeks Simbol JavaScript');
  p('');
  const jsFiles = files.filter((f) => ['.js', '.cjs', '.mjs'].includes(f.ext) && f.lines > 60);
  for (const f of jsFiles) {
    const syms = jsSymbols(f.content);
    p(`### \`${f.rel}\` - ${syms.length} simbol top-level, ${f.lines} baris`);
    p('');
    p('| Baris | Jenis | Nama |');
    p('| ---: | --- | --- |');
    for (const s of syms) p(`| ${s.ln} | ${s.kind} | \`${s.name}\` |`);
    p('');
  }

  // Java inventory
  p('## I.4 Inventaris Kelas & Method Java');
  p('');
  const javaFiles = files.filter((f) => f.ext === '.java');
  let totalMethods = 0;
  for (const f of javaFiles) {
    const { classes, methods } = javaSymbols(f.content);
    totalMethods += methods.length;
    p(`### \`${f.rel}\` - ${f.lines} baris, ${methods.length} method`);
    p('');
    p(`Kelas: ${classes.map((c) => '`' + c.name + '`').join(', ') || '(anonim/lanjutan)'}`);
    p('');
    if (methods.length) {
      p('| Baris | Method |');
      p('| ---: | --- |');
      for (const m of methods) p(`| ${m.ln} | \`${m.name}\` |`);
      p('');
    }
  }
  p(`Total method Java terdeteksi: **${totalMethods}**.`);
  p('');

  // Storage keys
  p('## I.5 Kunci Penyimpanan Klien');
  p('');
  const jsAll = files.filter((f) => f.ext === '.js').map((f) => f.content).join('\n');
  const storageKeys = [...new Set(
    [...jsAll.matchAll(/store\.(?:get|set)\(\s*['"]([\w.-]+)['"]/g)].map((m) => m[1]),
  )].sort();
  p('`store.get/set` literal: ' + (storageKeys.map((k) => '`' + k + '`').join(', ') || '(tidak terdeteksi)'));
  p('');
  const rawKeys = [...new Set(
    [...jsAll.matchAll(/['"](smw[_\w]*|dnialify_version|smw_user_data_version|max_cached_songs|chunk_window)['"]/g)].map((m) => m[1]),
  )].sort();
  p('Kunci mentah/nama-namespace: ' + (rawKeys.map((k) => '`' + k + '`').join(', ') || '(tidak terdeteksi)'));
  p('');

  // DOM ids
  p('## I.6 ID DOM');
  p('');
  const html = files.filter((f) => f.ext === '.html').map((f) => f.content).join('\n');
  const idsHtml = [...new Set([...html.matchAll(/\bid="([\w-]+)"/g)].map((m) => m[1]))].sort();
  const idsJs = [...new Set([
    ...[...jsAll.matchAll(/\$\(\s*['"]#([\w-]+)['"]/g)].map((m) => m[1]),
    ...[...jsAll.matchAll(/getElementById\(\s*['"]([\w-]+)['"]/g)].map((m) => m[1]),
  ])].sort();
  p(`Didefinisikan di HTML (${idsHtml.length}): ${idsHtml.map((i) => '`#' + i + '`').join(', ')}`);
  p('');
  p(`Dirujuk dari JS (${idsJs.length}): ${idsJs.map((i) => '`#' + i + '`').join(', ')}`);
  p('');

  // Log tags
  p('## I.7 Tag Log');
  p('');
  const tags = {};
  for (const m of jsAll.matchAll(/\[([A-Z][A-Z0-9_]{2,})\]/g)) tags[m[1]] = (tags[m[1]] || 0) + 1;
  const tagList = Object.entries(tags).sort((a, b) => b[1] - a[1]);
  p('| Tag | Kemunculan |');
  p('| --- | ---: |');
  for (const [t, n] of tagList) p(`| \`${t}\` | ${n} |`);
  p('');

  // CSS inventory
  p('## I.8 Inventaris CSS');
  p('');
  for (const f of files.filter((x) => x.ext === '.css')) {
    const rules = (f.content.match(/\{/g) || []).length;
    const media = (f.content.match(/@media/g) || []).length;
    const keyframes = (f.content.match(/@keyframes/g) || []).length;
    const vars = [...new Set([...f.content.matchAll(/--([\w-]+)\s*:/g)].map((m) => m[1]))];
    p(`- \`${f.rel}\`: ${f.lines} baris, ~${rules} blok, ${media} @media, ${keyframes} @keyframes, ${vars.length} CSS variable.`);
    if (vars.length) p(`  - Variabel: ${vars.map((v) => '`--' + v + '`').join(', ')}`);
  }
  p('');

  // Bare patch index
  p('## I.9 Indeks Patch Bare (Chromium)');
  p('');
  const bareDir = path.join(path.dirname(ev.files[0] ? ev.files[0].abs : '.'), '..', 'bare-browser', 'patches');
  const barePatches = path.resolve(__dirname, '..', 'bare-browser', 'patches');
  if (fs.existsSync(barePatches)) {
    const list = fs.readdirSync(barePatches).filter((n) => n.endsWith('.patch')).sort();
    p(`Jumlah patch: ${list.length}.`);
    p('');
    p('| # | Berkas | Subject |');
    p('| ---: | --- | --- |');
    let i = 0;
    for (const n of list) {
      let subject = '';
      try {
        const txt = fs.readFileSync(path.join(barePatches, n), 'utf8').slice(0, 4000);
        const m = /^Subject:\s*(.+)$/m.exec(txt);
        subject = m ? m[1].replace(/\s*\[PATCH[^\]]*\]\s*/g, '').trim() : '';
      } catch { subject = ''; }
      p(`| ${++i} | \`${n}\` | ${subject.replace(/\|/g, '\\|')} |`);
    }
    p('');
  } else {
    p('(direktori `bare-browser/patches` tidak ada)');
    p('');
  }

  // Workflow matrix
  p('## I.10 Matriks Workflow');
  p('');
  const wfDir = path.resolve(__dirname, '..', '.github', 'workflows');
  if (fs.existsSync(wfDir)) {
    for (const n of fs.readdirSync(wfDir).filter((x) => /\.ya?ml$/.test(x)).sort()) {
      const txt = fs.readFileSync(path.join(wfDir, n), 'utf8');
      const name = (/^name:\s*(.+)$/m.exec(txt) || [])[1] || n;
      const on = (/^on:\s*(.+)$/m.exec(txt) || [])[1] || '(lihat isi)';
      const lines = txt.split('\n');
      let inJobs = false;
      const jobs = [];
      for (const line of lines) {
        if (/^jobs:\s*$/.test(line)) { inJobs = true; continue; }
        if (inJobs) {
          if (/^\S/.test(line)) { inJobs = false; continue; }
          const m = /^  ([A-Za-z][\w-]*):\s*$/.exec(line);
          if (m) jobs.push(m[1]);
        }
      }
      p(`- \`${n}\` - ${name}; on: ${on}; jobs: ${jobs.join(', ') || '-'}`);
    }
    p('');
  } else {
    p('(direktori `.github/workflows` tidak ada)');
    p('');
  }

  // Per-directory summary
  p('## I.11 Ringkasan Direktori');
  p('');
  const dirs = {};
  for (const f of files) {
    const top = f.rel.includes('/') ? f.rel.split('/')[0] : '(root)';
    dirs[top] = dirs[top] || { n: 0, bytes: 0 };
    dirs[top].n += 1;
    dirs[top].bytes += f.bytes;
  }
  p('| Direktori | Berkas | Byte |');
  p('| --- | ---: | ---: |');
  Object.keys(dirs).sort().forEach((d) => p(`| \`${d}\` | ${dirs[d].n} | ${dirs[d].bytes} |`));
  p('');

  return L;
};
