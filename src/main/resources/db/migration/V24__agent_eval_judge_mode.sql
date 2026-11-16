-- 智能体评测忠实度口径分型：口径跟着「用例的期望」走，不跟智能体或本轮实际行为走
-- （按行为分型会让「本该引用却没引用」的回归落到宽松口径被漏抓）。
-- strict=考知识库 grounding：事实断言必须有资料/工具结果依据，无据即低分；
-- open=考开放质量：只判与资料/工具结果矛盾或明显错误的事实，常识性内容不要求出处。
-- NULL=自动推导：有期望引用→strict，无期望引用→open（存量行不回填，读写时推导）。
-- result 侧落档位快照：失败展开能看到这条是按哪档评的，改用例档位不漂移历史口径。

ALTER TABLE `c_ai_agent_eval_case`
    ADD COLUMN `judge_mode` VARCHAR(8) DEFAULT NULL
        COMMENT '忠实度口径: strict=考知识库grounding open=考开放质量 NULL=自动（有期望引用→strict 无→open）' AFTER `expected_tools`;

ALTER TABLE `c_ai_agent_eval_result`
    ADD COLUMN `judge_mode` VARCHAR(8) DEFAULT NULL
        COMMENT '本次评判所用口径快照（strict/open；NULL=旧数据或评判不可用）' AFTER `judge_note`;