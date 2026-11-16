-- 智能体评测 P2-a 采样复核闸（智能体评测P2-复核闸与上下文回放.md §3）：
-- 采样不再直接入集——落 pending 待人工采纳后才参与回归与门禁（对标 LangSmith annotation queue）。
-- 存量行默认 approved 不回填；手动新增走默认，行为不变。
-- origin 存采样原轮快照 {sessionId, messageId, answer}，复核视图据此判断
-- 「问题离开上下文是否成立」「期望引用是否可信」；P2-b 前文快照在此基础上扩展。

ALTER TABLE `c_ai_agent_eval_case`
    ADD COLUMN `review_status` VARCHAR(16) NOT NULL DEFAULT 'approved'
        COMMENT '复核状态: pending=待复核（不参与运行/门禁） approved=已采纳' AFTER `source`,
    ADD COLUMN `origin` TEXT DEFAULT NULL
        COMMENT '采样原轮快照 JSON {sessionId,messageId,answer≤2000字}；手动用例 NULL' AFTER `review_status`;