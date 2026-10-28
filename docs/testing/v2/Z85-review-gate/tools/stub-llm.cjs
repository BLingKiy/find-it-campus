// Z85 verification stub: one HTTP endpoint that behaves like DeepSeek chat completions
// for both the postprocess prompt and the pair scoring prompt. No network, no key.
const http = require('node:http');

const PORT = 19090;

function reply(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function contentFor(userText) {
  if (userText.includes('STUB_FAIL')) {
    return { __fail: true };
  }
  if (userText.includes('STUB_REJECT')) {
    return {
      decision: 'REJECT',
      reasons: ['内容包含无法在保留特征的前提下去身份化的信息'],
      title: '', description: '', location: '', summary: ''
    };
  }
  if (userText.includes('STUB_SENSITIVE')) {
    return {
      decision: 'APPROVE',
      reasons: ['stub: 模型声称通过'],
      title: '黑色钱包 13800000001',
      description: '在图书馆三楼捡到黑色皮质钱包',
      location: '图书馆三楼',
      summary: '黑色皮质钱包，图书馆三楼'
    };
  }
  // pair scoring prompt
  if (userText.includes('【寻物】') && userText.includes('【招领】')) {
    return { score: 0.92, reason: 'stub: 类别与关键特征一致' };
  }
  if (userText.includes('Z85APPROVE')) {
    return {
      decision: 'APPROVE',
      reasons: ['已去除姓名与门牌，保留黑色皮质与工行卡特征'],
      title: 'Z85APPROVE 黑色钱包',
      description: 'Z85APPROVE 在图书馆三楼捡到黑色皮质钱包，内有工行卡',
      location: '图书馆三楼',
      summary: '黑色皮质钱包，图书馆三楼'
    };
  }
  return {
    decision: 'APPROVE',
    reasons: ['已去除姓名与门牌，保留黑色皮质与工行卡特征'],
    title: '黑色钱包',
    description: '在图书馆三楼捡到黑色皮质钱包，内有工行卡',
    location: '图书馆三楼',
    summary: '黑色皮质钱包，图书馆三楼'
  };
}

const server = http.createServer((req, res) => {
  let raw = '';
  req.on('data', (chunk) => { raw += chunk; });
  req.on('end', () => {
    if (req.method !== 'POST' || !req.url.includes('/chat/completions')) {
      reply(res, 404, { error: 'not found' });
      return;
    }
    let body;
    try {
      body = JSON.parse(raw || '{}');
    } catch {
      reply(res, 400, { error: 'bad json' });
      return;
    }
    const messages = body.messages || [];
    const userText = messages.filter((m) => m.role === 'user').map((m) => m.content).join('\n');
    const content = contentFor(userText);
    if (content.__fail) {
      reply(res, 500, { error: { message: 'stub failure' } });
      return;
    }
    reply(res, 200, {
      choices: [{ message: { role: 'assistant', content: JSON.stringify(content) } }],
      usage: { prompt_tokens: 128, completion_tokens: 64 }
    });
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`stub llm listening on ${PORT}`);
});
