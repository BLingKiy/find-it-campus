// Package secret scan for G1. Patterns from ZHYYKFX-62:
//   P1: sk-[A-Za-z0-9_-]{16,}
//   P2: DEEPSEEK_API_KEY= immediately followed by a non-whitespace character
// Reads every regular file as latin1 so binary payloads (jar/exe) are scanned byte-for-byte
// for ASCII patterns. Never prints a full match; only file, line, and a length/prefix hint.
import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];
if (!root) {
  console.error('usage: node g1-scan.mjs <extracted-package-root>');
  process.exit(2);
}

const patterns = [
  { id: 'P1', label: 'sk-[A-Za-z0-9_-]{16,}', re: /sk-[A-Za-z0-9_-]{16,}/g },
  { id: 'P2', label: 'DEEPSEEK_API_KEY= followed by non-whitespace', re: /DEEPSEEK_API_KEY=[^\s]/g },
];

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile()) out.push(full);
  }
  return out;
}

const files = walk(root).sort();
const counts = Object.fromEntries(patterns.map((p) => [p.id, 0]));
const hits = [];

for (const file of files) {
  const buf = fs.readFileSync(file);
  const text = buf.toString('latin1');
  const rel = path.relative(root, file).replace(/\\/g, '/');
  for (const p of patterns) {
    p.re.lastIndex = 0;
    let m;
    while ((m = p.re.exec(text)) !== null) {
      counts[p.id] += 1;
      const line = text.slice(0, m.index).split('\n').length;
      const prefix = m[0].slice(0, 4);
      hits.push({ pattern: p.id, file: rel, line, length: m[0].length, prefix });
    }
  }
}

console.log(`ROOT=${root}`);
console.log(`FILES_SCANNED=${files.length}`);
for (const p of patterns) {
  console.log(`PATTERN_${p.id}=${p.label}`);
  console.log(`HITS_${p.id}=${counts[p.id]}`);
}
if (hits.length === 0) {
  console.log('RESULT=0_HITS');
} else {
  console.log('RESULT=HITS_FOUND');
  for (const h of hits) {
    console.log(`HIT pattern=${h.pattern} file=${h.file} line=${h.line} length=${h.length} prefix=${h.prefix}`);
  }
}
process.exit(hits.length === 0 ? 0 : 1);
