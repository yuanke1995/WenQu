/**
 * OpenAI 兼容 Mock 网关（压测专用）：伪造流式回答 / askUser 工具调用 / 向量嵌入，
 * 让 100 路并发压测不烧真网关的 token，且延迟可控。
 *
 * 行为分支（按请求体内容判定，无需特殊 header）：
 * - messages 含 role=tool          → 第二轮：流式返回最终回答（走完工具调用闭环）
 * - 最后一条 user 消息含 [[ASK]]   → 第一轮：流式返回 askUser 工具调用（触发人在回路挂起）
 * - stream=false                   → 非流式补全（派遣/委派路由的实体调用路径）
 * - 其余                           → 流式返回 TTFB_MS + CHUNKS×CHUNK_MS 的可控长回答
 *
 * 启动：node scripts/loadtest/mock-gateway.mjs（环境变量见下方常量）
 */
import http from 'node:http';

const PORT = +process.env.PORT || 9101;
const TTFB_MS = +process.env.TTFB_MS || 800;   // 首字延迟：模拟网关排队与推理启动
const CHUNKS = +process.env.CHUNKS || 40;      // 回答分块数：CHUNKS×CHUNK_MS ≈ 生成时长
const CHUNK_MS = +process.env.CHUNK_MS || 40;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    req.on('data', (c) => parts.push(c));
    req.on('end', () => {
      try {
        resolve(parts.length ? JSON.parse(Buffer.concat(parts).toString('utf8')) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

/** OpenAI 流式 chunk（choices[0].delta） */
function chunk(res, id, model, delta, finish) {
  const body = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  };
  res.write(`data: ${JSON.stringify(body)}\n\n`);
}

async function streamAnswer(res, id, model, text) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  chunk(res, id, model, { role: 'assistant' });
  await sleep(TTFB_MS);
  for (let i = 0; i < CHUNKS; i++) {
    chunk(res, id, model, { content: '压测' });
    await sleep(CHUNK_MS);
  }
  chunk(res, id, model, {}, 'stop');
  res.write('data: [DONE]\n\n');
  res.end();
}

const server = http.createServer(async (req, res) => {
  try {
    const url = req.url.split('?')[0];
    if (req.method === 'GET' && url === '/v1/models') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ object: 'list', data: [{ id: 'mock-chat', object: 'model' }] }));
      return;
    }
    if (req.method === 'POST' && url === '/v1/embeddings') {
      const body = await readBody(req);
      const n = Array.isArray(body.input) ? body.input.length : 1;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        object: 'list',
        data: Array.from({ length: n }, (_, i) => ({
          object: 'embedding', index: i,
          embedding: Array.from({ length: 8 }, () => 0.1),
        })),
        model: body.model ?? 'mock-embed',
        usage: { prompt_tokens: 1, total_tokens: 1 },
      }));
      return;
    }
    if (req.method !== 'POST' || url !== '/v1/chat/completions') {
      res.writeHead(404).end();
      return;
    }

    const body = await readBody(req);
    const id = 'chatcmpl-mock-' + Math.random().toString(36).slice(2);
    const model = body.model ?? 'mock-chat';
    const msgs = Array.isArray(body.messages) ? body.messages : [];
    const hasToolResult = msgs.some((m) => m.role === 'tool');
    const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
    const userText = typeof lastUser?.content === 'string'
      ? lastUser.content
      : JSON.stringify(lastUser?.content ?? '');
    const wantsAsk = userText.includes('[[ASK]]');

    if (body.stream !== true) {
      // 实体调用（派遣/委派路由/标题等辅助调用）：返回可解析的「无匹配」判定，走各调用方默认兜底
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        id, object: 'chat.completion', created: Math.floor(Date.now() / 1000), model,
        choices: [{ index: 0, message: { role: 'assistant', content: '{}\n' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 10, completion_tokens: 2, total_tokens: 12 },
      }));
      return;
    }

    if (!hasToolResult && wantsAsk) {
      // 第一轮：流式下发 askUser 工具调用（arguments 为一卡多问 schema：questions[].{topic,question,options}）
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: 'call_mock_ask_1', type: 'function',
        function: {
          name: 'askUser',
          arguments: JSON.stringify({ questions: [{
            topic: '压测提问',
            question: '负载压测提问：请在候选中选择一个方案',
            options: ['方案A：推荐项（超时不代答）', '方案B：备选', '方案C：备选'],
          }] }),
        },
      }] });
      await sleep(200);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    await streamAnswer(res, id, model, 'mock');
  } catch (e) {
    try {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: String(e && e.message || e) } }));
    } catch { /* 已提交响应则放弃 */ }
  }
});

server.listen(PORT, () => console.log(`[mock-gateway] listening on :${PORT} (TTFB=${TTFB_MS}ms, ${CHUNKS}×${CHUNK_MS}ms)`));
