-- 浏览器操作工具已统一为 BROWSER 族（browser_* 一套工具名），给智能体一个独立的三态开关：
-- 1=开 0=关 NULL=继承平台 tool.browser.enabled。执行位置（本机 headed/headless、云端）是个人设置，
-- 不在这个字段里——能力归属与使用方式分开，避免管理员的开关覆盖用户的位置选择。

ALTER TABLE `c_ai_agent`
    ADD COLUMN `tool_browser` INT DEFAULT NULL
        COMMENT '浏览器操作工具(browser_*): 1=开 0=关 NULL=继承平台 tool.browser.enabled'
        AFTER `tool_mcp`;
