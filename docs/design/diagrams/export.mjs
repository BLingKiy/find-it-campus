/**
 * 把 docs/design/diagrams/*.mmd 同一次导出成 .svg 与 .png。
 *
 * 为什么必须是「一次生成」：docx 嵌 SVG、PPT 用 PNG、评审看 PNG，三处若分别手工导出，
 * 就会出现「文档里是新图、PPT 里是旧图」这种无法追溯的偏差。源只有 .mmd 一份。
 *
 * 用法：
 *   node docs/design/diagrams/export.mjs            # 导出全部
 *   node docs/design/diagrams/export.mjs --check    # 只校验 PNG 宽度与三件套时间戳
 *   node docs/design/diagrams/export.mjs er-core    # 只导出指定图（调试用）
 *
 * 依赖：mermaid 与 puppeteer 装在仓库外的本机工具目录（见 README.md）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TOOLS = path.resolve(HERE, '../../../../.build-tools');
const MERMAID = path.join(TOOLS, 'node_modules/mermaid/dist/mermaid.min.js');

const MIN_PNG_WIDTH = 3000;
const TARGET_PNG_WIDTH = 3200;
const MIN_LAYOUT = 900;
const MAX_LAYOUT = 1400;
const BG = '#FFFFFF';

const args = process.argv.slice(2);
const checkOnly = args.includes('--check');
const only = args.filter((a) => !a.startsWith('--'));

/* ------------------------------------------------------------------ utils */

const mmdFiles = fs.readdirSync(HERE)
  .filter((f) => f.endsWith('.mmd'))
  .filter((f) => (only.length ? only.some((k) => f.startsWith(k)) : true))
  .sort();

function pngSize(file) {
  const b = fs.readFileSync(file);
  if (b.readUInt32BE(0) !== 0x89504e47) throw new Error(`${file} 不是 PNG`);
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
}

const pad = (n, w = 6) => String(n).padStart(w, ' ');

/* ------------------------------------------------------------- check mode */

if (checkOnly) {
  let bad = 0;
  console.log('file'.padEnd(26), 'png W×H'.padStart(14), '  svg KiB', ' png KiB', ' 同批');
  for (const f of mmdFiles) {
    const stem = f.replace(/\.mmd$/, '');
    const png = path.join(HERE, `${stem}.png`);
    const svg = path.join(HERE, `${stem}.svg`);
    const mmd = path.join(HERE, f);
    if (!fs.existsSync(png) || !fs.existsSync(svg)) {
      console.log(`${stem.padEnd(26)} MISSING svg/png`);
      bad += 1;
      continue;
    }
    const { width, height } = pngSize(png);
    const tMmd = fs.statSync(mmd).mtimeMs;
    const tSvg = fs.statSync(svg).mtimeMs;
    const tPng = fs.statSync(png).mtimeMs;
    // 判定口径：① svg 与 png 必须由同一次导出产生（相隔 < 120s）；
    //           ② 源不能比成品新 —— 改了 mmd 却没重导时，这里会报不一致。
    const sameRun = Math.abs(tSvg - tPng) < 120e3;
    const upToDate = tSvg >= tMmd - 2e3;
    const ok = sameRun && upToDate;
    if (width < MIN_PNG_WIDTH || !ok) bad += 1;
    console.log(
      stem.padEnd(26),
      `${width}×${height}`.padStart(14),
      pad((fs.statSync(svg).size / 1024).toFixed(1), 9),
      pad((fs.statSync(png).size / 1024).toFixed(1), 8),
      ok ? '  是' : sameRun ? '  源比成品新' : '  否',
    );
  }
  console.log(bad ? `\nFAIL：${bad} 项不达标（PNG 宽度 <${MIN_PNG_WIDTH}，或源比成品新，或 SVG/PNG 不同批）` : '\nOK：所有 PNG 宽度达标，SVG/PNG 同批，且成品不早于源');
  process.exit(bad ? 1 : 0);
}

/* ------------------------------------------------------------------ render */

if (!fs.existsSync(MERMAID)) {
  console.error(`找不到 mermaid：${MERMAID}\n先按 README.md 在 ${TOOLS} 下 npm install`);
  process.exit(2);
}

const requireFromTools = createRequire(path.join(TOOLS, 'package.json'));
const puppeteer = requireFromTools('puppeteer');
const mermaidSource = fs.readFileSync(MERMAID, 'utf8');

const html = `<!doctype html><html><head><meta charset="utf-8">
<style>
  html, body { margin: 0; padding: 0; background: ${BG}; }
  #host { display: inline-block; padding: 16px; background: ${BG}; }
  #host svg { display: block; max-width: none !important; height: auto !important; }
</style></head><body><div id="host"></div></body></html>`;

/** 本机 puppeteer 缓存的 Chrome 版本常与 puppeteer 自报的版本号对不上，这里自己找可执行文件。 */
function findChrome() {
  if (process.env.PUPPETEER_EXECUTABLE_PATH) return process.env.PUPPETEER_EXECUTABLE_PATH;
  const cache = path.join(process.env.USERPROFILE ?? process.env.HOME ?? '', '.cache/puppeteer/chrome');
  if (fs.existsSync(cache)) {
    const hits = fs.readdirSync(cache)
      .map((d) => path.join(cache, d, 'chrome-win64/chrome.exe'))
      .filter((p) => fs.existsSync(p))
      .sort()
      .reverse();
    if (hits.length) return hits[0];
  }
  for (const p of [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  ]) if (fs.existsSync(p)) return p;
  return null;
}

const executablePath = findChrome();
const browser = await puppeteer.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {}),
  args: ['--no-sandbox', '--disable-gpu', '--font-render-hinting=none', '--force-color-profile=srgb'],
});
console.log(`浏览器：${executablePath ?? 'puppeteer 默认'}`);

const page = await browser.newPage();
await page.setViewport({ width: MAX_LAYOUT, height: 1200, deviceScaleFactor: 1 });
await page.setContent(html, { waitUntil: 'load' });
await page.addScriptTag({ content: mermaidSource });
// htmlLabels=false：标签渲染成 <text> 而不是 foreignObject —— Word 的 SVG 渲染器不认 foreignObject，
// 用默认值导出的 SVG 在 docx 里会整块空白。
await page.evaluate(() => {
  window.mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    securityLevel: 'loose',
    fontFamily: '"Microsoft YaHei", "Segoe UI", sans-serif',
    themeVariables: { fontSize: '18px', background: '#FFFFFF', primaryColor: '#E8EEF7', primaryTextColor: '#16233B' },
    flowchart: { htmlLabels: false, curve: 'basis', useMaxWidth: false, wrappingWidth: 260 },
    sequence: { useMaxWidth: false },
    er: { useMaxWidth: false },
  });
});

let failed = 0;
for (const [index, f] of mmdFiles.entries()) {
  const stem = f.replace(/\.mmd$/, '');
  const code = fs.readFileSync(path.join(HERE, f), 'utf8');
  try {
    const built = await page.evaluate(async ({ code, id }) => {
      const { svg } = await window.mermaid.render(id, code);
      const host = document.getElementById('host');
      host.innerHTML = svg;
      const el = host.querySelector('svg');
      const vb = el.viewBox.baseVal;
      const w = vb && vb.width ? vb.width : el.getBoundingClientRect().width;
      const h = vb && vb.height ? vb.height : el.getBoundingClientRect().height;
      return { svg, w, h };
    }, { code, id: `d${index}` });

    // 只做小幅归一：放大倍数越高，「字号 / 画布宽」这个比例越失真（图会被拉成又高又窄）。
    // 版面宽取图自身的自然宽度，落入 [MIN_LAYOUT, MAX_LAYOUT] 之外时才纠正。
    const layoutW = Math.round(Math.min(MAX_LAYOUT, Math.max(MIN_LAYOUT, built.w)));
    const layoutH = Math.round((built.h * layoutW) / built.w);
    const scale = Math.min(8, Math.max(1, Math.ceil(TARGET_PNG_WIDTH / layoutW)));

    await page.evaluate(({ w }) => {
      const el = document.querySelector('#host svg');
      el.style.width = `${w}px`;
      el.style.height = 'auto';
    }, { w: layoutW });

    await page.setViewport({ width: layoutW + 32, height: layoutH + 32, deviceScaleFactor: scale });

    const outSvg = path.join(HERE, `${stem}.svg`);
    const outPng = path.join(HERE, `${stem}.png`);

    // 独立可读的 SVG：补上 width/height，浏览器直接打开不会缩成 0
    const standalone = built.svg
      .replace(/<svg([^>]*?)width="[^"]*"/, '<svg$1')
      .replace(/<svg([^>]*?)height="[^"]*"/, '<svg$1')
      .replace('<svg', `<svg width="${layoutW}" height="${layoutH}"`);
    fs.writeFileSync(outSvg, standalone, 'utf8');

    const host = await page.$('#host');
    await host.screenshot({ path: outPng, omitBackground: false });

    const { width, height } = pngSize(outPng);
    if (width < MIN_PNG_WIDTH) {
      failed += 1;
      console.error(`  ✗ ${stem}：PNG 宽 ${width}px < ${MIN_PNG_WIDTH}`);
    }
    console.log(
      `${stem.padEnd(26)} ${layoutW}×${layoutH} CSS → ${width}×${height} px  `
      + `svg ${(fs.statSync(outSvg).size / 1024).toFixed(0)} KiB  png ${(fs.statSync(outPng).size / 1024).toFixed(0)} KiB`,
    );
  } catch (e) {
    failed += 1;
    console.error(`  ✗ ${stem}：${e.message.split('\n')[0]}`);
  }
}

await browser.close();
console.log(failed ? `\n${failed} 张导出失败` : `\n已导出 ${mmdFiles.length} 张图（SVG + PNG）`);
process.exit(failed ? 1 : 0);
