/**
 * 把 docs/design 下的 Markdown 导出成可提交的 DOCX。
 *
 * 输入（相对本脚本）：
 *   ../program-design.md          主稿
 *   ../api-list.md                附录 A · 接口清单
 *   ../consistency-checklist.md   附录 B · 一致性自查
 *   ../diagrams/*.png             配图（由 render-diagrams.mjs 生成）
 * 输出：
 *   ../program-design.docx
 *
 * 只支持本项目文档实际用到的 Markdown 子集：标题、段落、无序/有序列表、引用、
 * 围栏代码块、表格、图片、水平线、行内加粗/代码/链接。刻意不引完整的 Markdown
 * 解析器 —— 文档结构简单，自己解析比调试解析器差异更快，也不会因为某个插件升级
 * 把表格渲染坏。
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, ImageRun,
  Table, TableRow, TableCell, WidthType, BorderStyle, ShadingType, Footer,
  PageNumber, TableLayoutType, convertMillimetersToTwip,
} from 'docx';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const DOCS = path.resolve(HERE, '..');
const OUT = path.join(DOCS, 'program-design.docx');

const FILES = ['program-design.md', 'api-list.md', 'consistency-checklist.md'];
const FONT = 'Microsoft YaHei';
const MONO = 'Consolas';
const ACCENT = '1F4E79';
const CODE_FILL = 'F4F5F7';
const HEAD_FILL = 'E8EEF7';
const BODY_SIZE = 21;   // half-points = 10.5pt
const TABLE_SIZE = 18;  // 9pt
const CODE_SIZE = 16;   // 8pt

/* ---------------------------------------------------------------- inline */

/** 行内标记 → TextRun 列表。支持 **粗体**、`代码`、[文本](链接)。 */
function inlineRuns(text, { size = BODY_SIZE, color, bold = false } = {}) {
  const runs = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
  let last = 0;
  let match;
  const push = (t, extra = {}) => {
    if (!t) return;
    runs.push(new TextRun({ text: t, font: FONT, size, color, bold, ...extra }));
  };
  while ((match = pattern.exec(text)) !== null) {
    push(text.slice(last, match.index));
    const token = match[0];
    if (token.startsWith('**')) {
      // 粗体里可能还嵌着 `code`：拆开渲染，否则反引号会原样留在纸上
      const inner = token.slice(2, -2);
      inner.split(/(\x60[^\x60]+\x60)/).forEach((part) => {
        if (!part) return;
        if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
          runs.push(new TextRun({
            text: part.slice(1, -1), font: MONO, size: size - 2, bold: true,
            color: '9C2B2B', shading: { type: ShadingType.CLEAR, fill: 'F2F2F2' },
          }));
        } else {
          push(part, { bold: true });
        }
      });
    } else if (token.startsWith('`')) {
      runs.push(new TextRun({
        text: token.slice(1, -1), font: MONO, size: size - 2,
        color: '9C2B2B', shading: { type: ShadingType.CLEAR, fill: 'F2F2F2' },
      }));
    } else {
      const label = token.slice(1, token.indexOf(']'));
      const url = token.slice(token.indexOf('](') + 2, -1);
      push(label);
      if (!url.startsWith('#')) push(`（${url}）`, { color: '666666', size: size - 2 });
    }
    last = match.index + token.length;
  }
  push(text.slice(last));
  if (runs.length === 0) runs.push(new TextRun({ text: '', font: FONT, size }));
  return runs;
}

/* ----------------------------------------------------------------- table */

function isTableStart(lines, i) {
  return lines[i]?.trim().startsWith('|') && /^\|[\s:|-]+\|$/.test(lines[i + 1]?.trim() ?? '');
}

function splitRow(line) {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  return trimmed.split('|').map((cell) => cell.trim());
}

/** 视觉长度：CJK / 全角记 2，ASCII 记 1，去掉 Markdown 标记。 */
function visualLength(text) {
  const plain = text.replace(/\*{2}/g, '').replace(/\x60/g, '');
  let n = 0;
  for (const ch of plain) n += /[\u2e80-\u9fff\uff00-\uffef]/.test(ch) ? 2 : 1;
  return n;
}

function buildTable(rows) {
  const header = rows[0];
  const body = rows.slice(1);
  const colCount = Math.max(...rows.map((r) => r.length));
  const cellBorder = { style: BorderStyle.SINGLE, size: 4, color: 'B7C4D8' };
  const borders = { top: cellBorder, bottom: cellBorder, left: cellBorder, right: cellBorder };

  // 按内容长度分配列宽：Word 的 AUTOFIT 会把「18080」「Controller」这种短列挤成两行，
  // 所以自己算一版权重。列的硬下限按「最长不可断词」估算（Word 只在空格处换行，
  // 英文标识符不能被拆成 Control / ler），中文可以逐字断行，不参与下限计算。
  const widths = (() => {
    const weights = Array.from({ length: colCount }, (_, col) => {
      const longest = Math.max(...rows.map((r) => visualLength(r[col] ?? '')), 4);
      return Math.min(48, Math.max(6, longest));
    });
    const mins = Array.from({ length: colCount }, (_, col) => {
      const words = rows.flatMap((r) => (r[col] ?? '').match(/[A-Za-z0-9_.-]+/g) ?? []);
      const longestWord = Math.max(0, ...words.map((w) => w.length));
      return Math.max(8, Math.min(26, longestWord * 1.15));
    });
    const minSum = mins.reduce((a, b) => a + b, 0);
    const normMins = minSum > 88 ? mins.map((m) => (m / minSum) * 88) : mins;
    const free = 100 - normMins.reduce((a, b) => a + b, 0);
    const weightSum = weights.reduce((a, b) => a + b, 0);
    const pct = normMins.map((m, i) => m + (weights[i] / weightSum) * free);
    // 归一化收尾，避免四舍五入后总和偏离 100
    const sum = pct.reduce((a, b) => a + b, 0);
    return pct.map((p) => (p / sum) * 100);
  })();

  const makeCell = (text, isHeader, col) => new TableCell({
    borders,
    width: { size: Math.round(widths[col]), type: WidthType.PERCENTAGE },
    shading: isHeader ? { type: ShadingType.CLEAR, fill: HEAD_FILL } : undefined,
    margins: { top: 60, bottom: 60, left: 90, right: 90 },
    children: [new Paragraph({
      spacing: { before: 0, after: 0, line: 260 },
      children: inlineRuns(text, { size: TABLE_SIZE, bold: isHeader }),
    })],
  });

  return new Table({
    width: { size: 100, type: WidthType.PERCENTAGE },
    layout: TableLayoutType.FIXED,
    borders: {
      top: cellBorder, bottom: cellBorder, left: cellBorder, right: cellBorder,
      insideHorizontal: cellBorder, insideVertical: cellBorder,
    },
    rows: [
      new TableRow({ tableHeader: true, children: header.map((c, col) => makeCell(c, true, col)) }),
      ...body.map((r) => new TableRow({
        children: Array.from({ length: colCount }, (_, col) => makeCell(r[col] ?? '', false, col)),
      })),
    ],
  });
}

/* ----------------------------------------------------------------- image */

function pngSize(buffer) {
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

function buildImage(alt, relPath, baseDir) {
  const abs = path.resolve(baseDir, relPath);
  if (!fs.existsSync(abs)) {
    return new Paragraph({
      spacing: { before: 120, after: 120 },
      children: [new TextRun({ text: `［缺图：${relPath}］`, font: FONT, size: BODY_SIZE, color: 'C00000' })],
    });
  }
  const data = fs.readFileSync(abs);
  const { width, height } = pngSize(data);
  // 同时受页宽与页高约束：太高的图会被自动缩小，但至少留出图注与页脚的空间
  const scale = Math.min(1, 600 / width, 480 / height);
  return [
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { before: 160, after: 80 },
      keepNext: true,
      children: [new ImageRun({
        type: 'png',
        data,
        transformation: { width: Math.round(width * scale), height: Math.round(height * scale) },
      })],
    }),
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 200 },
      children: [new TextRun({ text: alt, font: FONT, size: 18, color: '555555' })],
    }),
  ];
}

/* ------------------------------------------------------------ block parser */

function parseMarkdown(md, baseDir, outline) {
  const lines = md.split(/\r?\n/);
  const out = [];
  let i = 0;

  while (i < lines.length) {
    const raw = lines[i];
    const line = raw.trimEnd();

    if (!line.trim()) { i += 1; continue; }

    // 水平线
    if (/^---+$/.test(line.trim())) {
      out.push(new Paragraph({
        spacing: { before: 120, after: 120 },
        border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: 'D0D7E2' } },
        children: [],
      }));
      i += 1;
      continue;
    }

    // 代码块
    if (line.trim().startsWith('```')) {
      i += 1;
      const code = [];
      while (i < lines.length && !lines[i].trim().startsWith('```')) { code.push(lines[i]); i += 1; }
      i += 1;
      const children = code.map((text, index) => new TextRun({
        text: text.replace(/\t/g, '    '),
        font: MONO,
        size: CODE_SIZE,
        break: index === 0 ? undefined : 1,
      }));
      out.push(new Paragraph({
        spacing: { before: 80, after: 160, line: 240 },
        shading: { type: ShadingType.CLEAR, fill: CODE_FILL },
        indent: { left: 120, right: 120 },
        children: children.length ? children : [new TextRun({ text: '', font: MONO, size: CODE_SIZE })],
      }));
      continue;
    }

    // 表格
    if (isTableStart(lines, i)) {
      const rows = [splitRow(lines[i])];
      i += 2;
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(splitRow(lines[i]));
        i += 1;
      }
      out.push(buildTable(rows));
      out.push(new Paragraph({ spacing: { after: 80 }, children: [] }));
      continue;
    }

    // 图片
    const imageMatch = line.trim().match(/^!\[([^\]]*)\]\(([^)]+)\)$/);
    if (imageMatch) {
      out.push(...[].concat(buildImage(imageMatch[1], imageMatch[2], baseDir)));
      i += 1;
      continue;
    }

    // 标题
    const headingMatch = line.match(/^(#{1,4})\s+(.*)$/);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const text = headingMatch[2].trim();
      // 目录只收 H1（仅附录）与 H2：H3 太细，排出来会有三四页
      if ((level === 1 && text.startsWith('附录')) || level === 2) {
        outline.push({ level, text: text.replace(/\*{2}/g, '').replace(/\x60/g, '') });
      }
      const sizes = { 1: 36, 2: 28, 3: 24, 4: 22 };
      if (level === 1) {
        out.push(new Paragraph({
          alignment: AlignmentType.CENTER,
          spacing: { before: 0, after: 240 },
          children: [new TextRun({ text, font: FONT, size: sizes[1], bold: true, color: ACCENT })],
        }));
      } else {
        out.push(new Paragraph({
          heading: level === 2 ? HeadingLevel.HEADING_1 : level === 3 ? HeadingLevel.HEADING_2 : HeadingLevel.HEADING_3,
          spacing: { before: level === 2 ? 320 : 220, after: 120, keepNext: true },
          children: [new TextRun({ text, font: FONT, size: sizes[level], bold: true, color: level === 2 ? ACCENT : '222222' })],
        }));
      }
      i += 1;
      continue;
    }

    // 引用块：逐行保留换行（挤成一段会让「对象/基线/执行」这类短行粘在一起）
    if (line.trim().startsWith('>')) {
      const quote = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quote.push(lines[i].trim().replace(/^>\s?/, ''));
        i += 1;
      }
      quote.forEach((text, index) => {
        out.push(new Paragraph({
          spacing: {
            before: index === 0 ? 80 : 0,
            after: index === quote.length - 1 ? 160 : 20,
            line: 300,
          },
          indent: { left: 240 },
          border: { left: { style: BorderStyle.SINGLE, size: 12, color: 'B7C4D8', space: 8 } },
          children: inlineRuns(text, { color: '444444' }),
        }));
      });
      continue;
    }

    // 列表
    if (/^([-*]|\d+\.)\s+/.test(line.trim())) {
      const ordered = /^\d+\.\s+/.test(line.trim());
      const text = line.trim().replace(/^([-*]|\d+\.)\s+/, '');
      out.push(new Paragraph({
        spacing: { before: 20, after: 60, line: 300 },
        bullet: ordered ? undefined : { level: 0 },
        indent: ordered ? { left: 360, hanging: 240 } : undefined,
        children: inlineRuns(text),
      }));
      i += 1;
      continue;
    }

    // 普通段落：合并连续非空、非结构行
    const buffer = [line.trim()];
    i += 1;
    while (i < lines.length) {
      const next = lines[i].trim();
      if (!next
        || next.startsWith('#') || next.startsWith('>') || next.startsWith('```')
        || next.startsWith('|') || /^---+$/.test(next) || /^!\[/.test(next)
        || /^([-*]|\d+\.)\s+/.test(next)) break;
      buffer.push(next);
      i += 1;
    }
    out.push(new Paragraph({
      spacing: { before: 40, after: 120, line: 320 },
      indent: { firstLine: 0 },
      children: inlineRuns(buffer.join('')),
    }));
  }
  return out;
}

/* ------------------------------------------------------------------ cover */

function cover(outline) {
  const meta = [
    ['文档版本', 'v1.0'],
    ['对应代码基线', '34c5dd1（feat/5.1-integration @ 919b3e9 + 5.1 测试资产）'],
    ['适用仓库', 'https://github.com/BLingKiy/find-it-campus'],
    ['编写日期', '2026-10-22'],
  ];
  return [
    new Paragraph({ spacing: { before: 1600 }, children: [] }),
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 200 },
      children: [new TextRun({ text: '校园失物招领平台', font: FONT, size: 48, bold: true, color: ACCENT })],
    }),
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 120 },
      children: [new TextRun({ text: 'find-it-campus', font: MONO, size: 32, color: '333333' })],
    }),
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 600 },
      children: [new TextRun({ text: '程 序 设 计 文 档', font: FONT, size: 36, bold: true, color: '222222' })],
    }),
    new Table({
      width: { size: 78, type: WidthType.PERCENTAGE },
      alignment: AlignmentType.CENTER,
      rows: [
        ...meta.map(([k, v]) => new TableRow({
          children: [
            new TableCell({
              width: { size: 26, type: WidthType.PERCENTAGE },
              shading: { type: ShadingType.CLEAR, fill: HEAD_FILL },
              margins: { top: 80, bottom: 80, left: 120, right: 120 },
              children: [new Paragraph({ children: [new TextRun({ text: k, font: FONT, size: 20, bold: true })] })],
            }),
            new TableCell({
              margins: { top: 80, bottom: 80, left: 120, right: 120 },
              children: [new Paragraph({ children: [new TextRun({ text: v, font: FONT, size: 20 })] })],
            }),
          ],
        })),
      ],
    }),
    new Paragraph({
      spacing: { before: 600 },
      alignment: AlignmentType.CENTER,
      children: [new TextRun({
        text: '本文只写仓库里能验证的内容；未落地的能力在文中明确标注。',
        font: FONT, size: 18, color: '666666',
      })],
    }),
    new Paragraph({ pageBreakBefore: true, children: [] }),
  ];
}

function outlinePage(outline) {
  const blocks = [
    new Paragraph({
      spacing: { before: 200, after: 240 },
      children: [new TextRun({ text: '目 录', font: FONT, size: 32, bold: true, color: ACCENT })],
    }),
  ];
  for (const item of outline) {
    if (item.level === 1 && blocks.length > 1) {
      blocks.push(new Paragraph({ spacing: { before: 200 }, children: [] }));
    }
    blocks.push(new Paragraph({
      spacing: { before: item.level === 2 ? 80 : 20, after: 20, line: 300 },
      indent: { left: item.level === 1 ? 0 : item.level === 2 ? 360 : 720 },
      children: [new TextRun({
        text: item.text,
        font: FONT,
        size: item.level === 1 ? 22 : 20,
        bold: item.level === 1,
        color: item.level === 1 ? '222222' : '444444',
      })],
    }));
  }
  blocks.push(new Paragraph({ pageBreakBefore: true, children: [] }));
  return blocks;
}

/* ------------------------------------------------------------------- main */

const children = [];
const outline = [];
const parsed = FILES.map((file) => {
  const full = path.join(DOCS, file);
  if (!fs.existsSync(full)) throw new Error(`缺少输入文件：${full}`);
  return { file, baseDir: DOCS, md: fs.readFileSync(full, 'utf8') };
});

// 第一遍只为收集目录
for (const { md } of parsed) parseMarkdown(md, DOCS, outline);

children.push(...cover(outline));
children.push(...outlinePage(outline));

for (const [index, { file, md }] of parsed.entries()) {
  if (index > 0) {
    children.push(new Paragraph({ pageBreakBefore: true, children: [] }));
  }
  children.push(...parseMarkdown(md, DOCS, []));
}

const doc = new Document({
  creator: 'find-it-campus 课程项目组',
  title: '校园失物招领平台（find-it-campus）程序设计文档',
  description: '课程设计文档：需求、架构、数据库、接口、AI 匹配、开发过程、测试',
  styles: {
    default: {
      document: {
        run: { font: FONT, size: BODY_SIZE },
        paragraph: { spacing: { line: 320 } },
      },
    },
  },
  sections: [{
    properties: {
      page: {
        size: { width: convertMillimetersToTwip(210), height: convertMillimetersToTwip(297) },
        margin: {
          top: convertMillimetersToTwip(22), bottom: convertMillimetersToTwip(22),
          left: convertMillimetersToTwip(22), right: convertMillimetersToTwip(22),
        },
      },
    },
    footers: {
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [
            new TextRun({ text: 'find-it-campus 程序设计文档 · ', font: FONT, size: 16, color: '777777' }),
            new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 16, color: '777777' }),
            new TextRun({ text: ' / ', font: FONT, size: 16, color: '777777' }),
            new TextRun({ children: [PageNumber.TOTAL_PAGES], font: FONT, size: 16, color: '777777' }),
          ],
        })],
      }),
    },
    children,
  }],
});

const buffer = await Packer.toBuffer(doc);
fs.writeFileSync(OUT, buffer);
console.log(`已生成 ${OUT}（${(buffer.length / 1024).toFixed(1)} KB，${children.length} 个块）`);
