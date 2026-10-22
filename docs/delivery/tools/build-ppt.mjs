/**
 * 把 docs/delivery/6.2-defense-ppt.md 渲染成可提交的 PPTX。
 *
 * 输入（相对本脚本）：
 *   ../6.2-defense-ppt.md            文案事实源（唯一）
 *   ../assets/shots/*.png            演示截图（由 demo-path-check.mjs 生成）
 *   ../assets/match-sample-s1*.json  真实请求/响应（PPT 的输入输出示例）
 *   ../../design/diagrams/*.png      架构 / ER / 时序图（6.1 已有）
 * 输出：
 *   ../6.2-答辩PPT.pptx
 *
 * 为什么用 Markdown 当源而不是直接改 pptx：口径要能被评审、被 diff。
 * 直接改二进制文件的话，下一次构建就把改动冲掉，也没人能 review 改了什么。
 *
 * 只支持本稿实际用到的 Markdown 子集：`#` 页标题、`###` 副标题、`-` 列表（一层缩进）、
 * 表格、`>` 小结、以及 `<!-- layout/image/note/code-* -->` 指令。刻意不引完整的 Markdown
 * 解析器 —— 结构固定，自己解析比调解析器行为更快，也不会因为某个插件升级把排版弄坏。
 *
 * 运行：
 *   cd docs/delivery/tools && npm install && node build-ppt.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import pptxgen from 'pptxgenjs';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const DELIVERY = path.resolve(HERE, '..');
const DOCS = path.resolve(DELIVERY, '..');
const SRC = path.join(DELIVERY, '6.2-defense-ppt.md');
const OUT = path.join(DELIVERY, '6.2-答辩PPT.pptx');

/** 稿子里的相对路径可能落在 docs/delivery（截图、JSON）或 docs（6.1 的图）下：两处都试。 */
const resolveAsset = (rel) => {
  for (const base of [DELIVERY, DOCS]) {
    const p = path.resolve(base, rel);
    if (fs.existsSync(p)) return p;
  }
  throw new Error(`找不到素材：${rel}（已试 docs/delivery 与 docs 两个根）`);
};

/* ---------------------------------------------------------------- 设计常量 */

const W = 13.333;
const H = 7.5;
const M = 0.72;                 // 左右页边距
const CW = W - M * 2;           // 内容宽 11.89
const ACCENT = '2450D0';        // 品牌色（与 web 端 --color-brand 一致）
const DARK = '16233B';
const MUTED = '5B6B84';
const LINE = 'D8DEE9';
const TINT = 'F2F5FB';
const BG_DARK = '101A2E';
const CODE_RED = 'B02A37';
const FONT = 'Microsoft YaHei';
const MONO = 'Consolas';

const TITLE_Y = 0.46;
const BODY_TOP = 1.45;
const BODY_BOTTOM = 6.84;

/* ---------------------------------------------------------------- Markdown 解析 */

function parseDeck(md) {
  const blocks = md.split(/^---\s*$/m);
  return blocks
    .map((raw) => {
      const s = { layout: 'bullets', images: [], captions: [], bullets: [], table: null, quote: null, title: '', sub: '', note: '', code: {}, meta: [] };
      let fence = false;
      let tableRows = null;

      for (const line of raw.split(/\r?\n/)) {
        const dir = line.match(/^<!--\s*([a-z-]+)\s*:\s*(.+?)\s*-->$/);
        if (dir) {
          const [, key, value] = dir;
          if (key === 'layout') s.layout = value;
          else if (key === 'image') s.images.push(value);
          else if (key === 'images') s.images.push(...value.split('|').map((v) => v.trim()));
          else if (key === 'captions') s.captions.push(...value.split('|').map((v) => v.trim()));
          else if (key === 'image-title') s.imageTitle = value;
          else if (key === 'note') s.note = value;
          else if (key.startsWith('code-')) s.code[key.slice(5)] = value;
          continue;
        }
        if (/^```/.test(line)) { fence = !fence; continue; }
        if (fence) continue;

        if (/^#\s+/.test(line)) { s.title = line.replace(/^#\s+/, '').trim(); continue; }
        if (/^###\s+/.test(line)) { s.sub = line.replace(/^###\s+/, '').trim(); continue; }
        if (/^>\s?/.test(line)) { s.quote = ((s.quote ?? '') + line.replace(/^>\s?/, '')).trim(); continue; }

        if (/^\|/.test(line)) {
          const cells = line.replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
          if (cells.every((c) => /^:?-{2,}:?$/.test(c))) continue;
          tableRows = tableRows ?? [];
          tableRows.push(cells);
          s.table = tableRows;
          continue;
        }

        const bullet = line.match(/^(\s*)-\s+(.*)$/);
        if (bullet) { s.bullets.push({ level: Math.floor(bullet[1].length / 2), text: bullet[2].trim() }); continue; }

        // 裸行（封面的答辩人 / 日期 / 仓库）原样保留
        if (line.trim()) s.meta.push(line.trim());
      }
      return s;
    })
    .filter((s) => s.title);
}

/* ---------------------------------------------------------------- 行内标记 */

const units = (str) => {
  let n = 0;
  for (const ch of str) n += /[\u2E80-\u9FFF\uFF00-\uFFEF\u3000-\u303F]/.test(ch) ? 2 : 1;
  return n;
};

/**
 * 行内 `**粗体**` 与 `` `代码` `` → pptxgenjs 的 run 列表。
 * 注意：pptxgenjs 的 text 数组里每个元素是一个 run，`breakLine` 结束一个段；段落级属性
 * （bullet / paraSpaceAfter）必须写在**该段第一个 run** 上，写在后面会被忽略 —— 这是踩过的坑。
 */
function runs(text, { size, color = DARK, bold = false, codeColor = CODE_RED }) {
  const out = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0;
  let m;
  const push = (t, o = {}) => {
    if (!t) return;
    out.push({ text: t, options: { fontFace: FONT, fontSize: size, color, bold, ...o } });
  };
  while ((m = re.exec(text)) !== null) {
    push(text.slice(last, m.index));
    const tok = m[0];
    if (tok.startsWith('**')) {
      // 粗体里可能嵌着 `code`：拆开渲染，否则反引号会原样出现在页面上
      tok.slice(2, -2).split(/(`[^`]+`)/).forEach((part) => {
        if (!part) return;
        if (part.length > 2 && part.startsWith('`') && part.endsWith('`')) {
          push(part.slice(1, -1), { bold: true, fontFace: MONO, fontSize: size - 0.5, color: codeColor });
        } else {
          push(part, { bold: true });
        }
      });
    } else {
      push(tok.slice(1, -1), { fontFace: MONO, fontSize: size - 0.5, color: codeColor });
    }
    last = m.index + tok.length;
  }
  push(text.slice(last));
  return out;
}

/** 每条 bullet → 若干 run，段落属性只放首 run，`breakLine` 只放末 run。 */
function paras(bullets, { size, color = DARK, bullet = true, spaceAfter = 9, lineSpacing = 1.12, codeColor = CODE_RED }) {
  const out = [];
  bullets.forEach((b, idx) => {
    const rs = runs(b.text, { size: b.level ? size - 1.5 : size, color: b.level ? MUTED : color, codeColor });
    // 段落属性挂在首 run 上，而首 run 若是代码样式（等宽 + 红色），项目符号会被染成红色小竖线；
    // 所以前面垫一个零宽空格 run 专门承载段落属性。
    const lead = bullet && rs[0]?.options.fontFace === MONO
      ? [{ text: '\u200B', options: { fontFace: FONT, fontSize: size, color } }]
      : [];
    const seq = [...lead, ...rs];
    seq.forEach((r, j) => {
      const first = j === 0;
      const last = j === seq.length - 1;
      out.push({
        text: r.text,
        options: {
          ...r.options,
          ...(first && bullet ? { bullet: { code: b.level ? '25E6' : '2022' }, indentLevel: b.level, paraSpaceAfter: spaceAfter } : {}),
          ...(last ? { breakLine: idx < bullets.length - 1 } : {}),
          lineSpacingMultiple: lineSpacing,
        },
      });
    });
  });
  return out;
}

/** 估一段 bullets 的高度（英寸）：用来决定「先放表格还是先放要点」，不靠肉眼试错。 */
function estimateBulletsH(bullets, size, widthIn, spaceAfter = 9) {
  let h = 0;
  bullets.forEach((b) => {
    const sz = b.level ? size - 1.5 : size;
    const w = widthIn - b.level * 0.32 - 0.28;
    const lines = Math.max(1, Math.ceil(units(b.text) / Math.max(1, w / (sz * 0.0067))));
    h += (lines * sz * 1.34) / 72 + spaceAfter / 72;
  });
  return h;
}

/* ---------------------------------------------------------------- 版式工具 */

function addTitle(slide, text) {
  slide.addText(runs(text, { size: 24, color: DARK, bold: true }), { x: M, y: TITLE_Y, w: CW, h: 0.6, valign: 'middle' });
  slide.addShape('rect', { x: M, y: TITLE_Y + 0.7, w: 0.86, h: 0.055, fill: { color: ACCENT }, line: { color: ACCENT } });
}

function addFooter(slide, page, dark = false) {
  slide.addText('find-it-campus · 校园失物招领　|　程序设计答辩 2026-10-22', {
    x: M, y: H - 0.46, w: CW - 0.8, h: 0.28, fontSize: 8.5, color: dark ? '6E7E9B' : '9AA6BA', fontFace: FONT,
  });
  slide.addText(String(page), {
    x: W - M - 0.5, y: H - 0.46, w: 0.5, h: 0.28, fontSize: 9, color: dark ? '6E7E9B' : '9AA6BA', fontFace: FONT, align: 'right',
  });
}

function pngSize(file) {
  const buf = fs.readFileSync(file);
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

/** 图片按比例塞进框（contain），不拉伸。 */
function fitImage(slide, rel, box) {
  const file = resolveAsset(rel);
  const { width, height } = pngSize(file);
  const scale = Math.min(box.w / width, box.h / height);
  const w = width * scale;
  const h = height * scale;
  const pos = { x: box.x + (box.w - w) / 2, y: box.y + (box.h - h) / 2, w, h };
  slide.addImage({ path: file, ...pos });
  return pos;
}

function quote(slide, text, y, { x = M, w = CW } = {}) {
  slide.addShape('rect', { x, y, w, h: 0.52, fill: { color: TINT }, line: { color: TINT } });
  slide.addShape('rect', { x, y, w: 0.06, h: 0.52, fill: { color: ACCENT }, line: { color: ACCENT } });
  slide.addText(runs(text, { size: 12, color: '33415C' }), { x: x + 0.2, y, w: w - 0.32, h: 0.52, valign: 'middle' });
}

/* ---------------------------------------------------------------- 表格 */

/** 按内容长度分配列宽：长句列宽、数字列窄；同时保证每列不窄于 MIN（否则「本地 → 外部」会被折成两行）。 */
function autoColW(rows, total) {
  const n = rows[0].length;
  const max = new Array(n).fill(0);
  rows.forEach((r) => r.forEach((c, i) => { max[i] = Math.max(max[i], units(c.replace(/[`*]/g, ''))); }));
  const raw = max.map((v) => Math.max(v, 8));
  const sum = raw.reduce((a, b) => a + b, 0);
  let widths = raw.map((v) => (v / sum) * total);

  const MIN = 1.32;
  for (let iter = 0; iter < 4; iter += 1) {
    const pinned = widths.map((w, i) => (w < MIN ? i : -1)).filter((i) => i >= 0);
    if (!pinned.length) break;
    const free = widths.map((_, i) => i).filter((i) => !pinned.includes(i));
    const rest = total - pinned.length * MIN;
    const freeSum = free.reduce((a, i) => a + raw[i], 0) || 1;
    widths = widths.map((w, i) => (pinned.includes(i) ? MIN : (raw[i] / freeSum) * rest));
  }
  // 除掉浮点误差，确保正好铺满
  const diff = total - widths.reduce((a, b) => a + b, 0);
  widths[widths.length - 1] += diff;
  return widths;
}

/** 估表格高度（英寸）：给后面的 bullets / 小结留位置，不靠肉眼试错。 */
function estimateTableH(rows, colW, size) {
  const lh = (size * 1.42) / 72;
  let h = 0;
  rows.forEach((r) => {
    let lines = 1;
    r.forEach((c, i) => {
      const u = units(c.replace(/[`*]/g, ''));
      const per = Math.max(1, (colW[i] - 0.18) / (size * 0.0067));
      lines = Math.max(lines, Math.ceil(u / per));
    });
    h += lines * lh + 0.1;
  });
  return h;
}

function addTable(slide, rows, { x = M, y = BODY_TOP, w = CW, size = 11, headSize = 11.5 } = {}) {
  const colW = autoColW(rows, w);
  const header = rows[0].map((c) => ({
    text: runs(c, { size: headSize, color: 'FFFFFF', bold: true }),
    options: { fill: { color: ACCENT }, valign: 'middle', margin: 6 },
  }));
  const body = rows.slice(1).map((r, ri) =>
    r.map((c) => ({
      text: runs(c, { size, color: DARK }),
      options: { fill: { color: ri % 2 ? 'FFFFFF' : TINT }, valign: 'middle', margin: 6 },
    })),
  );

  slide.addTable([header, ...body], {
    x, y, w, colW,
    border: { type: 'solid', color: LINE, pt: 0.75 },
    fontFace: FONT, autoPage: false, valign: 'middle',
  });
  return { colW, h: estimateTableH(rows, colW, size) };
}

/* ---------------------------------------------------------------- 代码面板 */

/** 按显示宽度断行：中文算 2 格，否则长 JSON 行会溢出面板。 */
function wrapByWidth(text, maxUnits) {
  const out = [];
  let line = '';
  let width = 0;
  for (const ch of text) {
    const u = units(ch);
    if (width + u > maxUnits) { out.push(line); line = ''; width = 0; }
    line += ch;
    width += u;
  }
  if (line) out.push(line);
  return out;
}

/** JSON 美化：只为演示可读，行为「键名: 值」，长值按显示宽度折行。 */
function pretty(obj, depth = 0) {
  const pad = '  '.repeat(depth);
  if (Array.isArray(obj)) {
    if (!obj.length) return ['[]'];
    const lines = ['['];
    obj.forEach((v, i) => {
      const inner = pretty(v, depth + 1);
      lines.push(pad + '  ' + inner[0]);
      for (let k = 1; k < inner.length; k += 1) lines.push(pad + '  ' + inner[k]);
      if (i < obj.length - 1) lines[lines.length - 1] += ',';
    });
    lines.push(pad + ']');
    return lines;
  }
  if (obj && typeof obj === 'object') {
    const keys = Object.keys(obj);
    const lines = ['{'];
    keys.forEach((k, i) => {
      const v = obj[k];
      const comma = i < keys.length - 1 ? ',' : '';
      const head = `"${k}": `;
      if (v && typeof v === 'object') {
        const inner = pretty(v, depth + 1);
        lines.push(pad + '  ' + head + inner[0]);
        for (let j = 1; j < inner.length; j += 1) lines.push(pad + '  ' + inner[j]);
        lines[lines.length - 1] += comma;
      } else {
        const val = typeof v === 'string' ? `"${v}"` : String(v);
        const wrapped = wrapByWidth(head + val + comma, 44);
        lines.push(pad + '  ' + wrapped[0]);
        for (let j = 1; j < wrapped.length; j += 1) lines.push(pad + '      ' + wrapped[j]);
      }
    });
    lines.push(pad + '}');
    return lines;
  }
  return [String(obj)];
}

/** 从真实响应里抽关键字段展示，并标注省略：不手写示例，避免与接口漂移。 */
function responseExcerpt(body) {
  const d = body.data;
  const it = d.items[0];
  return {
    code: body.code,
    message: body.message,
    requestId: body.requestId,
    data: {
      engine: d.engine,
      inputType: d.inputType,
      tookMs: d.tookMs,
      total: d.total,
      items: [{ recordId: it.recordId, score: it.score, scorePercent: it.scorePercent, reason: it.reason, title: it.title }],
    },
  };
}

function codePanel(slide, { x, y, w, h, title, lines, size = 8.4 }) {
  slide.addText(title, { x, y, w, h: 0.26, fontSize: 9.5, bold: true, color: MUTED, fontFace: FONT, valign: 'middle' });
  slide.addShape('rect', { x, y: y + 0.28, w, h: h - 0.28, fill: { color: 'F7F8FA' }, line: { color: LINE } });
  slide.addText(lines.join('\n'), {
    x: x + 0.12, y: y + 0.32, w: w - 0.24, h: h - 0.36,
    fontFace: MONO, fontSize: size, color: '22303F', valign: 'top', lineSpacingMultiple: 1.02,
  });
}

/* ---------------------------------------------------------------- 渲染 */

const deck = parseDeck(fs.readFileSync(SRC, 'utf8'));
const pptx = new pptxgen();
pptx.defineLayout({ name: 'CN_16_9', width: W, height: H });
pptx.layout = 'CN_16_9';
pptx.author = '文通通';
pptx.company = 'find-it-campus';
pptx.title = '校园失物招领平台 · 程序设计答辩';
pptx.subject = '阶段 6.2 答辩 PPT（建议稿，最终口径由人类定）';

deck.forEach((s, idx) => {
  const page = idx + 1;

  /* ---- 封面 ---- */
  if (s.layout === 'cover') {
    const slide = pptx.addSlide();
    slide.background = { color: BG_DARK };
    slide.addShape('rect', { x: M, y: 2.0, w: 1.05, h: 0.07, fill: { color: ACCENT }, line: { color: ACCENT } });
    slide.addText(runs(s.title, { size: 40, color: 'FFFFFF', bold: true }), { x: M, y: 2.2, w: CW, h: 0.95, valign: 'middle' });
    slide.addText(runs(s.sub, { size: 17, color: 'A9BCD9' }), { x: M, y: 3.15, w: CW, h: 0.5, valign: 'middle' });
    slide.addText([{ text: s.meta.join('\n'), options: { fontSize: 12, color: '7C8FAF', fontFace: FONT, lineSpacingMultiple: 1.55 } }], {
      x: M, y: 5.95, w: CW, h: 0.95, valign: 'top',
    });
    if (s.note) slide.addNotes(s.note);
    return;
  }

  /* ---- 结尾 ---- */
  if (s.layout === 'end') {
    const slide = pptx.addSlide();
    slide.background = { color: BG_DARK };
    slide.addShape('rect', { x: M, y: 0.9, w: 0.9, h: 0.07, fill: { color: ACCENT }, line: { color: ACCENT } });
    slide.addText(runs(s.title, { size: 32, color: 'FFFFFF', bold: true }), { x: M, y: 1.05, w: CW, h: 0.7, valign: 'middle' });
    if (s.sub) slide.addText(runs(s.sub, { size: 15.5, color: 'A9BCD9' }), { x: M, y: 1.78, w: CW, h: 0.5, valign: 'middle' });
    slide.addText(paras(s.bullets, { size: 13, color: 'D6DEEB', spaceAfter: 11, codeColor: '8FB4FF' }), { x: M, y: 2.6, w: CW, h: 3.5, valign: 'top' });
    addFooter(slide, page, true);
    if (s.note) slide.addNotes(s.note);
    return;
  }

  const slide = pptx.addSlide();
  slide.background = { color: 'FFFFFF' };
  addTitle(slide, s.title);
  addFooter(slide, page);
  if (s.note) slide.addNotes(s.note);

  /* ---- 表格页 ---- */
  if (s.layout === 'table') {
    const size = s.table.length <= 4 ? 12.5 : s.table.length <= 6 ? 11 : 10;
    const bSize = s.bullets.length <= 3 ? 12 : 11;
    const tableH = estimateTableH(s.table, autoColW(s.table, CW), size);
    const bulletsH = s.bullets.length ? estimateBulletsH(s.bullets, bSize, CW, 6) : 0;
    const groupH = tableH + (bulletsH ? bulletsH + 0.28 : 0) + (s.quote ? 0.72 : 0);
    // 内容比版面少时把整组往上抬一点（最多 0.9in）：避免表格贴在标题下、页面下半空一片
    const lift = Math.min(0.35, Math.max(0, (BODY_BOTTOM - BODY_TOP - groupH) * 0.3));
    const y = BODY_TOP + lift;
    const { h } = addTable(slide, s.table, { y, size, headSize: size + 0.5 });
    let cursor = y + h + 0.28;

    if (s.bullets.length) {
      slide.addText(paras(s.bullets, { size: bSize, spaceAfter: 6 }), {
        x: M, y: cursor, w: CW, h: Math.max(bulletsH + 0.3, 0.8), valign: 'top',
      });
      cursor += bulletsH + 0.3;
    }
    if (s.quote) quote(slide, s.quote, Math.min(cursor, BODY_BOTTOM - 0.54));
    return;
  }

  /* ---- 图文页（正文左、图右） ---- */
  if (s.layout === 'image') {
    // 图宽按实际比例算：竖图（容器图）不占满 7.2，省下的宽度留给左栏文字，免得汉字被拆到两行
    const px = pngSize(resolveAsset(s.images[0]));
    const boxH = 4.45;
    const imgW = Math.min(7.2, boxH * (px.width / px.height));
    const box = { x: M + CW - imgW, y: BODY_TOP, w: imgW, h: boxH };
    const placed = fitImage(slide, s.images[0], box);
    if (s.imageTitle) {
      slide.addText(s.imageTitle, {
        x: box.x, y: Math.min(placed.y + placed.h + 0.08, 6.2), w: imgW, h: 0.3,
        fontSize: 8.5, color: '9AA6BA', fontFace: FONT, align: 'center',
      });
    }
    slide.addText(paras(s.bullets, { size: 12.5, spaceAfter: 9 }), {
      x: M, y: BODY_TOP + 0.05, w: CW - imgW - 0.5, h: 5.0, valign: 'top',
    });
    return;
  }

  /* ---- 三联截图页 ---- */
  if (s.layout === 'three-up') {
    const gap = 0.3;
    const w = (CW - gap * 2) / 3;
    const boxH = 3.1;
    s.images.forEach((rel, i) => {
      const x = M + i * (w + gap);
      slide.addShape('rect', { x, y: BODY_TOP, w, h: boxH, fill: { color: 'FFFFFF' }, line: { color: LINE } });
      fitImage(slide, rel, { x, y: BODY_TOP, w, h: boxH });
      if (s.captions[i]) {
        slide.addText(s.captions[i], {
          x, y: BODY_TOP + boxH + 0.08, w, h: 0.28, fontSize: 9.5, bold: true, color: '33415C', fontFace: FONT, align: 'center',
        });
      }
    });
    slide.addText(paras(s.bullets, { size: 11, spaceAfter: 6 }), {
      x: M, y: BODY_TOP + boxH + 0.5, w: CW, h: 1.8, valign: 'top',
    });
    return;
  }

  /* ---- 代码页 ---- */
  if (s.layout === 'code') {
    const req = JSON.parse(fs.readFileSync(resolveAsset(s.code.request), 'utf8'));
    const res = JSON.parse(fs.readFileSync(resolveAsset(s.code.response), 'utf8'));
    const panelW = (CW - 0.35) / 2;
    const panelH = 3.5;
    codePanel(slide, { x: M, y: BODY_TOP, w: panelW, h: panelH, title: 'POST /api/match　请求体', lines: pretty(req) });
    codePanel(slide, {
      x: M + panelW + 0.35, y: BODY_TOP, w: panelW, h: panelH,
      title: '200 OK　响应体（节选：展示字段已省略）', lines: pretty(responseExcerpt(res)),
    });
    slide.addText(paras(s.bullets, { size: 10.5, spaceAfter: 5 }), {
      x: M, y: BODY_TOP + panelH + 0.32, w: CW, h: 1.4, valign: 'top',
    });
    return;
  }

  /* ---- 默认：要点页（可带一张表 / 一条小结） ---- */
  const hasQuote = Boolean(s.quote);
  const hasTable = Array.isArray(s.table) && s.table.length > 1;
  const size = hasTable
    ? (s.bullets.length <= 5 ? 11.5 : 10.8)
    : s.bullets.length <= 4 ? 15.5 : s.bullets.length <= 6 ? 13.5 : 12.5;
  const bulletsW = CW;
  const bulletsH = estimateBulletsH(s.bullets, size, bulletsW, hasTable ? 7 : 10);

  if (hasTable) {
    const tSize = 10;
    const tableH = estimateTableH(s.table, autoColW(s.table, CW), tSize);
    const groupH = bulletsH + 0.4 + tableH;
    const y0 = BODY_TOP + 0.08;
    slide.addText(paras(s.bullets, { size, spaceAfter: 7 }), {
      x: M, y: y0, w: bulletsW, h: bulletsH + 0.15, valign: 'top',
    });
    addTable(slide, s.table, { y: y0 + bulletsH + 0.3, size: tSize, headSize: tSize + 0.5 });
    return;
  }

  const bodyH = (hasQuote ? BODY_BOTTOM - 0.72 : BODY_BOTTOM) - BODY_TOP;
  slide.addText(paras(s.bullets, { size, spaceAfter: 10 }), {
    x: M, y: BODY_TOP + 0.06, w: bulletsW, h: bodyH - 0.06, valign: 'top',
  });
  if (hasQuote) {
    // 小结跟着正文走，别钉在页底 —— 否则中段会留一大片空白
    const qy = Math.min(BODY_TOP + 0.06 + estimateBulletsH(s.bullets, size, bulletsW, 10) + 0.34, BODY_BOTTOM - 0.54);
    quote(slide, s.quote, qy);
  }
});

await pptx.writeFile({ fileName: OUT });
console.log(`已生成 ${path.relative(process.cwd(), OUT)}（${deck.length} 页）`);
