package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KnowledgeBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceTypeResolverTest {

    private final KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
    private final AiDocumentMapper docMapper = mock(AiDocumentMapper.class);
    private final SourceTypeResolver resolver = new SourceTypeResolver(kbService, docMapper);

    @Test
    void builtinKnowledgeBaseIsOfficial() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setBuiltin(1);
        when(kbService.get("kb1")).thenReturn(kb);

        assertEquals(SourceTypeResolver.OFFICIAL, resolver.resolve("kb1", "d1"));
    }

    @Test
    void urlDocumentIsExternal() {
        AiDocument doc = new AiDocument();
        doc.setFileType("url");
        when(docMapper.selectById("d2")).thenReturn(doc);

        assertEquals(SourceTypeResolver.EXTERNAL, resolver.resolve("kb1", "d2"));
    }

    @Test
    void userUploadIsUpload() {
        AiDocument doc = new AiDocument();
        doc.setFileType("pdf");
        doc.setCreatedBy("yuanke");
        when(docMapper.selectById("d3")).thenReturn(doc);

        assertEquals(SourceTypeResolver.UPLOAD, resolver.resolve("kb1", "d3"));
    }

    @Test
    void unknownFallsBackToInternal() {
        assertEquals(SourceTypeResolver.INTERNAL, resolver.resolve(null, null));
    }
}
