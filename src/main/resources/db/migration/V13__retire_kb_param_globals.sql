-- 检索/解析参数的全局默认值层退役（2026-11-14）：
-- 默认值只归知识库弹窗展示的内置常量（留空=跟随内置默认），系统设置不再有
-- 「检索设置」检索参数项与「文档解析默认模板」；其中 6 个原先仅全局的键
-- （vectorTopK/keywordLimit/minContextScore/minFusionScore/lowConfidencePolicy/top1Floor）
-- 下沉为知识库级参数（KbParamCodec.KEYS）。先例：V1 退役段 / V10 / V12。
DELETE FROM c_ai_config
WHERE config_key IN (
    'retrieval.vectorWeight', 'retrieval.keywordWeight', 'retrieval.vecThreshold',
    'retrieval.vectorTopK', 'retrieval.keywordLimit',
    'retrieval.minContextScore', 'retrieval.minFusionScore',
    'retrieval.lowConfidencePolicy', 'retrieval.top1Floor',
    'chunk.maxSize', 'chunk.overlap', 'chunk.maxChunks', 'chunk.maxImages',
    'chunk.structural', 'chunk.structuralRatio', 'chunk.headingDepth',
    'parse.qaEnabled', 'parse.qaPerChunk', 'parse.childEnabled', 'parse.childSize',
    'parse.ocrEngine', 'parse.ocrMinText', 'parse.ocrDpi',
    'eval.applyObserveHours'
);

-- 个人层同名键一并清理（这些键从无个人设置入口，防御性对齐 V1 退役段口径）
DELETE FROM c_ai_user_config
WHERE config_key IN (
    'retrieval.vectorWeight', 'retrieval.keywordWeight', 'retrieval.vecThreshold',
    'retrieval.vectorTopK', 'retrieval.keywordLimit',
    'retrieval.minContextScore', 'retrieval.minFusionScore',
    'retrieval.lowConfidencePolicy', 'retrieval.top1Floor',
    'chunk.maxSize', 'chunk.overlap', 'chunk.maxChunks', 'chunk.maxImages',
    'chunk.structural', 'chunk.structuralRatio', 'chunk.headingDepth',
    'parse.qaEnabled', 'parse.qaPerChunk', 'parse.childEnabled', 'parse.childSize',
    'parse.ocrEngine', 'parse.ocrMinText', 'parse.ocrDpi',
    'eval.applyObserveHours'
);
