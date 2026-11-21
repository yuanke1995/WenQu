package com.wenqu.ai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuerySplitterTest {

    @Test
    void splitsByConnectivesWithoutExtractingKeywords() {
        KeywordExtractor extractor = mock(KeywordExtractor.class);
        QuerySplitter splitter = new QuerySplitter(extractor);

        QuerySplitter.SplitResult and = splitter.analyze("A和B和C");
        assertTrue(and.multiQuestion());
        assertEquals(List.of("A", "B", "C"), and.queries());

        QuerySplitter.SplitResult comma = splitter.analyze("X，Y");
        assertTrue(comma.multiQuestion());
        assertEquals(List.of("X", "Y"), comma.queries());
        verifyNoInteractions(extractor);
    }

    @Test
    void splitsByQuestionMarks() {
        QuerySplitter splitter = new QuerySplitter(mock(KeywordExtractor.class));

        QuerySplitter.SplitResult result = splitter.analyze("甲是什么？乙怎么配？");
        assertTrue(result.multiQuestion());
        assertEquals(List.of("甲是什么", "乙怎么配"), result.queries());
    }

    @Test
    void singleQuestionShortCircuits() {
        QuerySplitter splitter = new QuerySplitter(mock(KeywordExtractor.class));

        QuerySplitter.SplitResult result = splitter.analyze("如何配置知识库");
        assertFalse(result.multiQuestion());
        assertEquals(List.of("如何配置知识库"), result.queries());
    }

    @Test
    void evalSplitKeepsKeywordCombinationFallback() {
        KeywordExtractor extractor = mock(KeywordExtractor.class);
        when(extractor.extract("甲乙")).thenReturn(List.of("甲", "乙", "丙"));
        QuerySplitter splitter = new QuerySplitter(extractor);

        assertEquals(List.of("甲乙", "甲 乙"), splitter.split("甲乙"));
    }

    @Test
    void diagResetClearsSubQueries() {
        HybridRetrievalService.RetrievalDiag diag = new HybridRetrievalService.RetrievalDiag();
        diag.subQueries(List.of("a", "b"));
        assertEquals(2, diag.subQueries().size());

        diag.reset();
        assertTrue(diag.subQueries().isEmpty());
    }
}
