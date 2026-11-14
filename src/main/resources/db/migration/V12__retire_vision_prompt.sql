-- 图片描述提示词设置项退役（2026-11-14 拍板）：提示词固化为平台常量（ConfigDefaults.VISION_PROMPT），
-- 运行时不再读 vision.prompt（VisionService 直接传常量）。
-- 残留配置行必须删除：提示词是图片描述缓存键的一部分，库里旧值虽已无读取点，
-- 留着会让对账/恢复默认再碰一个没有字段定义的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` = 'vision.prompt';
