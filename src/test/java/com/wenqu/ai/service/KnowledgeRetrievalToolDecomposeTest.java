package com.wenqu.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalToolDecomposeTest {

    private HybridRetrievalService hybrid;
    private KnowledgeRetrievalTool tool;

    @BeforeEach
    void setUp() {
        hybrid = mock(HybridRetrievalService.class);
        when(hybrid.search(anyString(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(hybrid.searchMulti(anyList(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        QuerySplitter splitter = new QuerySplitter(mock(KeywordExtractor.class));
        tool = new KnowledgeRetrievalTool(hybrid, mock(ConfigService.class), mock(RerankService.class),
                mock(KbRetrievalParamService.class), mock(FollowUpQueryRewriter.class), splitter);
    }

    private ToolContext context(String question, boolean rewriteEnabled) {
        return new ToolContext(Map.of(KnowledgeRetrievalTool.CTX_TURN_QUERY,
                new KnowledgeRetrievalTool.TurnQueryContext(question, null, "u1", "s1", null, rewriteEnabled)));
    }

    @Test
    void multiQuestionIsDiagnosticOnly() {
        tool.searchKnowledge("甲和乙", 5, context("甲和乙", false));

        verify(hybrid).search(anyString(), any(), any(), anyBoolean(), any());
        verify(hybrid, never()).searchMulti(anyList(), any(), any(), anyBoolean(), any());
    }

    @Test
    void singleQuestionKeepsOriginalSearch() {
        tool.searchKnowledge("如何配置知识库", 5, context("如何配置知识库", false));

        verify(hybrid).search(anyString(), any(), any(), anyBoolean(), any());
        verify(hybrid, never()).searchMulti(anyList(), any(), any(), anyBoolean(), any());
    }

    @Test
    void modelAlreadySplitQueryIsNotDecomposedAgain() {
        tool.searchKnowledge("甲", 5, context("甲和乙", false));

        verify(hybrid).search(anyString(), any(), any(), anyBoolean(), any());
        verify(hybrid, never()).searchMulti(anyList(), any(), any(), anyBoolean(), any());
    }

    @Test
    void noTurnContextNeverDecomposes() {
        String out = tool.searchKnowledge("甲和乙", 5, null, null, null);

        assertTrue(out.contains("未在知识库中检索到相关内容"));
        verify(hybrid).search(anyString(), any(), any(), anyBoolean(), any());
        verify(hybrid, never()).searchMulti(anyList(), any(), any(), anyBoolean(), any());
    }
}
