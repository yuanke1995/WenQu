/**
 * OpenAI 兼容 Mock 网关（压测专用）：伪造流式回答 / askUser 工具调用 / 向量嵌入，
 * 让 100 路并发压测不烧真网关的 token，且延迟可控。
 *
 * 行为分支（按请求体内容判定，无需特殊 header）：
 * - messages 含 role=tool          → 第二轮：流式返回最终回答（走完工具调用闭环）
 * - 最后一条 user 消息含 [[ASK]]   → 第一轮：流式返回 askUser 工具调用（触发人在回路挂起）
 * - 含 [[SPLIT]]                   → 半句 → 工具调用 → 后半句，验「正文一条流」（SPLIT_CHUNK_MS 可逐字慢放）
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
    // 标记要在**全部** user 消息里找：框架会把记忆/上下文那份 <HARNESS_CONTEXT> 作为最后一条 user
    // 消息发出来，只看最后一条等于永远判不到 [[ASK]]——B 阶段的 40 路人在回路会静默退化成普通问答。
    const userText = msgs.filter((m) => m.role === 'user')
      .map((m) => (typeof m.content === 'string' ? m.content : JSON.stringify(m.content ?? '')))
      .join('\n');
    const wantsAsk = userText.includes('[[ASK]]');
    const wantsArtifact = userText.includes('[[ARTIFACT]]');
    const wantsPlan = userText.includes('[[PLAN]]');
    const wantsApproval = userText.includes('[[APPROVAL]]');
    const wantsLoop = userText.includes('[[LOOP]]');
    const wantsRich = userText.includes('[[RICHTEXT]]');
    // 一句话被工具调用劈开：前半句 → 工具 → 后半句，用来验「正文一条流」不按工具边界切正文
    const wantsSplit = userText.includes('[[SPLIT]]');
    // 深度执行 e2e：第 1 轮先出计划 → 批准后 goal_note 留缺口 → 第 2 轮 goal_submit 完成。
    // 轮次从目标提示词的「轮次：n/max」认。
    const wantsGoal = userText.includes('[[GOAL]]');
    const goalRound2 = /轮次：2\//.test(userText);
    // 已经调过哪些工具：按 assistant 消息里的 tool_calls 认（tool 结果消息本身不带名字）
    const calledTools = msgs.flatMap((m) => (m.tool_calls || []).map((t) => t.function?.name)).filter(Boolean);

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

    if (wantsGoal) {
      const emitTool = async (name, args) => {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        chunk(res, id, model, { role: 'assistant', tool_calls: [{
          index: 0, id: 'call_mock_goal_' + name + '_' + Date.now(), type: 'function',
          function: { name, arguments: JSON.stringify(args) },
        }] });
        await sleep(120);
        chunk(res, id, model, {}, 'tool_calls');
        res.write('data: [DONE]\n\n');
        res.end();
      };
      if (goalRound2) {
        if (calledTools.includes('goal_submit')) {
          res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
          chunk(res, id, model, { role: 'assistant' });
          chunk(res, id, model, { content: '验收已提交。' });
          await sleep(80);
          chunk(res, id, model, {}, 'stop');
          res.write('data: [DONE]\n\n');
          res.end();
          return;
        }
        await emitTool('goal_submit', {
          summary: '两轮推进完成（mock 验收）',
          evidence: JSON.stringify([{ kind: 'text', ref: 'mock-e2e', note: 'mock 网关模拟的验收证据' }]),
        });
        return;
      }
      if (!calledTools.includes('plan_write')) {
        await emitTool('plan_write', { content: '## 计划\n1. 第一轮记录缺口\n2. 第二轮提交验收' });
        return;
      }
      if (!calledTools.includes('plan_exit')) {
        await emitTool('plan_exit', { summary: '按两步推进' });
        return;
      }
      if (!calledTools.includes('goal_note')) {
        await emitTool('goal_note', {
          stage: 'EXECUTE',
          summary: '第一轮完成，留下一项缺口',
          remaining: JSON.stringify(['第二轮提交验收证据']),
        });
        return;
      }
      // 工具结果已回传：给一句正文收尾，避免下一轮带着未消费的 tool_call 进来
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant' });
      chunk(res, id, model, { content: '第一轮完成，缺口已记录；下一轮提交验收。' });
      await sleep(80);
      chunk(res, id, model, {}, 'stop');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    // 半句→工具→后半句：模型在句子中间发起工具调用，工具不写正文，两半在 content 里紧邻。
    // 用例拿来验正文一条流：答案＝整段 content（前半句＋后半句拼成完整一句），过程块只剩工具。
    if (wantsSplit && !hasToolResult) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant' });
      chunk(res, id, model, { content: '我先查一下最近' });
      await sleep(120);
      chunk(res, id, model, { tool_calls: [{
        index: 0, id: 'call_mock_split_1', type: 'function',
        function: { name: 'read_file', arguments: JSON.stringify({ path: 'README.md' }) },
      }] });
      await sleep(120);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }
    if (wantsSplit && hasToolResult) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant' });
      // SPLIT_CHUNK_MS：逐字慢速下发后半句，用于在浏览器里观察「运行中」的连续正文
      const tail = ' 30 天的记录，结论如下：一切正常。';
      const gap = +process.env.SPLIT_CHUNK_MS || 0;
      if (gap > 0) {
        for (const ch of tail) {
          chunk(res, id, model, { content: ch });
          await sleep(gap);
        }
      } else {
        chunk(res, id, model, { content: tail });
        await sleep(120);
      }
      chunk(res, id, model, {}, 'stop');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    // 富文本：思考（reasoning_content）+ <process> 过程独白 + 正文 + <related> 推荐块，
    // 用来验 think/process 两类块与旧列（thinking / process_text）的双写等价。
    if (wantsRich) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant' });
      await sleep(200);
      for (const t of ['先想一下：', '这一步要不要查库。']) {
        chunk(res, id, model, { reasoning_content: t });
        await sleep(80);
      }
      for (const t of ['<process>', '正在核对引用编号。', '</process>',
        '这是最终答案。', '<related>建议一|建议二</related>']) {
        chunk(res, id, model, { content: t });
        await sleep(80);
      }
      chunk(res, id, model, {}, 'stop');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    // 空转：同一把工具、同一份入参、同一份结果连发多次——progress 判「没带回新信息」，
    // 连续 5 次后引擎自动终止（STALL_AT=5），用来验 notice#stalled 与 STALL_SUFFIX。
    if (wantsLoop && calledTools.filter((n) => n === 'read_file').length < 8) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: `call_mock_loop_${calledTools.filter((n) => n === 'read_file').length + 1}`, type: 'function',
        function: { name: 'read_file', arguments: JSON.stringify({ path: 'README.md' }) },
      }] });
      await sleep(120);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    // 工具审批：连发两次 webSearch（第二次就是「第二次确认」），每次都要真批一次才会执行。
    // 只有联网搜索这类「有副作用」工具受 toolApprovalMode 管辖（write_file 在聊天档是自动放行的）。
    if (wantsApproval && calledTools.filter((n) => n === 'webSearch').length < 2) {
      const n = calledTools.filter((x) => x === 'webSearch').length + 1;
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: `call_mock_search_${n}`, type: 'function',
        function: {
          name: 'webSearch',
          arguments: JSON.stringify({ query: `blocks approval case ${n}` }),
        },
      }] });
      await sleep(200);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    if (wantsPlan && !calledTools.includes('plan_write')) {
      // 计划三步：plan_write（正文）→ plan_exit（确认卡）→ 批准后出答案
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: 'call_mock_plan_write_1', type: 'function',
        function: {
          name: 'plan_write',
          arguments: JSON.stringify({
            content: '## 计划\n1. 先确认需求边界\n2. 再做实现\n3. 最后回归验证',
          }),
        },
      }] });
      await sleep(200);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    if (wantsPlan && !calledTools.includes('plan_exit')) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: 'call_mock_plan_exit_1', type: 'function',
        function: { name: 'plan_exit', arguments: JSON.stringify({ summary: '按三步走' }) },
      }] });
      await sleep(200);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    if (!hasToolResult && wantsArtifact) {
      // 产物交付：真调 presentArtifact（内容过它的 HTML/引用句校验），第二轮再流式给结论。
      // 用例拿来验「工具卡 → 产物块 → 落库 artifacts 列」三段同源，以及产物卡在页面上的位置。
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: 'call_mock_artifact_1', type: 'function',
        function: {
          name: 'presentArtifact',
          arguments: JSON.stringify({
            filename: 'blocks-demo.html',
            description: 'blocks 端到端产物卡用例',
            content: '<!DOCTYPE html><html><head><meta charset="utf-8"><title>blocks demo</title></head>'
              + '<body><h1>产物卡用例</h1><p>这份 HTML 由 mock 网关交给 presentArtifact，用于验证产物块与落库列同源。</p></body></html>',
          }),
        },
      }] });
      await sleep(200);
      chunk(res, id, model, {}, 'tool_calls');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }

    if (!hasToolResult && wantsAsk) {
      // 第一轮：流式下发 askUser 工具调用。参数面照 AskUserTool 的 schema：
      // {question(必填), options[], multi, topic}——早先这里发的是 questions[]，工具按 schema 校验
      // 直接判错（未找到所需属性“question”），于是 B 阶段那 40 路「人在回路」根本没挂起过卡，
      // 只在工具卡上落了个 error。
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      chunk(res, id, model, { role: 'assistant', tool_calls: [{
        index: 0, id: 'call_mock_ask_1', type: 'function',
        function: {
          name: 'askUser',
          arguments: JSON.stringify({
            topic: '压测提问',
            question: '负载压测提问：请在候选中选择一个方案',
            options: ['方案A：推荐项（超时不代答）', '方案B：备选', '方案C：备选'],
          }),
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
