// ==================== 可选浏览器引擎：Stagehand（自然语言结构化抽取） ====================
//
// 默认浏览器引擎是 @playwright/mcp（见 wenqu-agent.mjs 的 opBrowser）：tabs/observe/extract 都能
// 在它上面确定性完成，不要模型 Key。本文件是**可选**增强引擎，只在显式开启时使用：
//
//   export WENQU_BROWSER_ENGINE=stagehand
//   export WENQU_STAGEHAND_MODEL=openai/gpt-4o-mini   # 或 anthropic/… google/… 等
//   export WENQU_STAGEHAND_API_KEY=sk-...             # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY
//   cd scripts/local-agent && npm i @browserbasehq/stagehand
//
// 为什么默认不启用：Stagehand 需要一个外部大模型来“理解”页面（每次 observe/extract 都会调用模型、
// 产生费用），并且它自管一个浏览器实例，与 @playwright/mcp 的页面不共享 Cookie/登录态——只适合
// 对“公网、无需登录、按自然语言一句抽一张表”的场景。公司内网/已登录页面请继续用 selector/function。
//
// 说明：本文件按 Stagehand v4 的 API（Stagehand.create / observe / extract）编写；不同小版本的
// 浏览器启动选项可能变化，调用失败会抛出可读错误并由调用方回退到 @playwright/mcp。

let instance = null

async function getInstance (headless) {
  if (instance) return instance
  let mod
  try {
    mod = await import('@browserbasehq/stagehand')
  } catch (e) {
    throw new Error('未安装 @browserbasehq/stagehand（在 scripts/local-agent 下执行 npm i @browserbasehq/stagehand）')
  }
  const Stagehand = mod.Stagehand || (mod.default && mod.default.Stagehand)
  if (!Stagehand || typeof Stagehand.create !== 'function') {
    throw new Error('当前 stagehand 版本不支持 Stagehand.create（需要 v4 及以上）')
  }
  const modelName = process.env.WENQU_STAGEHAND_MODEL || 'openai/gpt-4o-mini'
  const apiKey = process.env.WENQU_STAGEHAND_API_KEY
    || process.env.OPENAI_API_KEY || process.env.ANTHROPIC_API_KEY
  if (!apiKey) {
    throw new Error('缺少模型 Key：设置 WENQU_STAGEHAND_API_KEY 或 OPENAI_API_KEY / ANTHROPIC_API_KEY')
  }
  instance = await Stagehand.create({
    model: { modelName, apiKey },
    browser: { headless: Boolean(headless) }
  })
  return instance
}

async function activePage (sh) {
  try {
    const b = sh.browser
    if (b && b.context && typeof b.context.pages === 'function') {
      const pages = await b.context.pages()
      if (pages && pages.length) return pages[0]
    }
  } catch { /* 忽略：退到 sh.page */ }
  return sh.page || null
}

/**
 * 用 Stagehand 按自然语言 instruction（可选 schema）从页面抽结构化数据。
 * @param {{instruction:string, schema?:string, url?:string}} opts
 * @param {boolean} headless
 * @returns {Promise<string>} 结构化结果（JSON 文本）
 */
export async function stagehandExtract (opts, headless) {
  const { instruction, schema, url } = opts || {}
  if (!instruction) throw new Error('缺少 instruction')
  const sh = await getInstance(headless)
  // Stagehand 自管浏览器实例：若给了 url，先导航过去（拿不到 url 时在它当前默认页上尽力而为）。
  if (url) {
    try {
      const page = await activePage(sh)
      if (page && typeof page.goto === 'function') await page.goto(url)
    } catch { /* 交给 extract 兜底 */ }
  }
  // schema 以 JSON 字符串传入时，v4 期望 Zod schema；这里不擅自编译，交给上层按需扩展。
  const res = schema ? await sh.extract(instruction, schema) : await sh.extract(instruction)
  return typeof res === 'string' ? res : JSON.stringify(res, null, 2)
}

/** 进程退出时尽量收掉 stagehand 的浏览器实例。 */
export async function closeStagehand () {
  if (!instance) return
  try { await instance.close() } catch { /* ignore */ }
  instance = null
}
