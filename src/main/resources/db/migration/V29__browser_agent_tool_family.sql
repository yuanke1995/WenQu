-- 浏览器工具族从 MCP 迁到独立 BROWSER 族：修正平台共享助手「网页操作助手」的运行配置。
-- 旧配置 tool_mcp=1 + mcps='浏览器操作' 指向一张工具表已被 BrowserTools 清空的 MCP，
-- 助手会拿到「指定了 MCP 却没有工具」的降级提示，拿不到任何 browser_* 工具。
-- 新配置：挂 BROWSER 族（tool_browser=1），不再依赖 MCP 族。
-- 一次性数据迁移，不写进每次启动的种子逻辑，保留「运行配置归管理员」的承诺。

UPDATE `c_ai_agent`
   SET `tool_browser` = 1,
       `tool_mcp` = 0,
       `mcps` = NULL
 WHERE `id` = 'platform-browser-agent';
