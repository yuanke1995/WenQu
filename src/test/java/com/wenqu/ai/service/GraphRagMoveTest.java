package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.GraphEntityMapper;
import com.wenqu.ai.mapper.GraphExtractMapper;
import com.wenqu.ai.mapper.GraphTripleMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.GraphEntity;
import com.wenqu.ai.model.GraphTriple;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档跨库移动时图谱随迁（{@link GraphRagService#migrateDocGraph}）与存量修复
 * （{@link GraphRagService#repairMovedDocsGraph}）的单测。
 * <p>
 * 不起 Spring 上下文：三个 Mapper 用 Mockito 假件，按 migrateDocGraph 的固定调用顺序喂数据
 * （selectList 依次是：本文档三元组 → 其它引用 → 目标库现存边键）。覆盖：
 * 独占实体整行迁入、目标库同名实体复用（mention 累加）、共享实体复制、同边去重删除、
 * 未移动守卫、修复入口逐文档迁移。
 *
 * @author yuanke
 */
class GraphRagMoveTest {

    private GraphEntityMapper entityMapper;
    private GraphTripleMapper tripleMapper;
    private AiDocumentMapper docMapper;
    private GraphRagService svc;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper/LambdaUpdateWrapper 依赖 MP 的 TableInfo lambda 缓存；纯单测手动补登记
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GraphTriple.class);
        TableInfoHelper.initTableInfo(assistant, GraphEntity.class);
        TableInfoHelper.initTableInfo(assistant, AiDocument.class);
        entityMapper = mock(GraphEntityMapper.class);
        tripleMapper = mock(GraphTripleMapper.class);
        docMapper = mock(AiDocumentMapper.class);
        svc = new GraphRagService(entityMapper, tripleMapper, mock(GraphExtractMapper.class),
                mock(KnowledgeMapper.class), mock(KnowledgeBaseMapper.class), docMapper,
                null, null, null, null, null);
    }

    private static GraphTriple triple(String id, String kb, String s, String p, String o, String docId) {
        GraphTriple t = new GraphTriple();
        t.setId(id);
        t.setKbId(kb);
        t.setSubjectId(s);
        t.setPredicate(p);
        t.setObjectId(o);
        t.setDocId(docId);
        return t;
    }

    private static GraphEntity entity(String id, String kb, String name, String norm, int mention) {
        GraphEntity e = new GraphEntity();
        e.setId(id);
        e.setKbId(kb);
        e.setName(name);
        e.setNameNorm(norm);
        e.setMentionCount(mention);
        return e;
    }

    private static AiDocument doc(String id, String kbId) {
        AiDocument d = new AiDocument();
        d.setId(id);
        d.setKbId(kbId);
        return d;
    }

    @Test
    @DisplayName("独占实体随文档整行迁入目标库，三元组改挂目标库")
    void moveExclusiveEntities() {
        when(docMapper.selectById("D1")).thenReturn(doc("D1", "B"));
        GraphTriple t1 = triple("T1", "A", "E1", "位于", "E2", "D1");
        GraphTriple t2 = triple("T2", "A", "E1", "包含", "E3", "D1");
        when(tripleMapper.selectList(any())).thenReturn(List.of(t1, t2), List.of(), List.of());
        when(entityMapper.selectBatchIds(any())).thenReturn(
                List.of(entity("E1", "A", "智报", "智报", 2),
                        entity("E2", "A", "云端", "云端", 1),
                        entity("E3", "A", "报表", "报表", 1)));
        when(entityMapper.selectOne(any())).thenReturn(null, null, null);

        Map<String, Object> r = svc.migrateDocGraph("D1", "A");

        assertThat(r.get("moved")).isEqualTo(2);
        assertThat(r.get("movedEntities")).isEqualTo(3);
        assertThat(r.get("deduped")).isEqualTo(0);
        // 实体行迁入：kb_id 改为目标库 B
        ArgumentCaptor<LambdaUpdateWrapper<GraphEntity>> entCap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(entityMapper, times(3)).update(isNull(), entCap.capture());
        entCap.getAllValues().forEach(w -> assertThat(w.getParamNameValuePairs().values()).contains("B"));
        // 三元组改挂：kb=B，实体引用保持原 id（独占迁入，无需重映射）
        ArgumentCaptor<LambdaUpdateWrapper<GraphTriple>> triCap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(tripleMapper, times(2)).update(isNull(), triCap.capture());
        assertThat(triCap.getAllValues().get(0).getParamNameValuePairs().values())
                .contains("B", "E1", "E2");
    }

    @Test
    @DisplayName("目标库已有同归一名实体：复用并累加提及；同边已存在则作重复删除")
    void reuseTargetEntitiesAndDedup() {
        when(docMapper.selectById("D1")).thenReturn(doc("D1", "B"));
        GraphTriple t1 = triple("T1", "A", "E1", "位于", "E2", "D1");
        when(tripleMapper.selectList(any())).thenReturn(
                List.of(t1), List.of(),
                List.of(triple("TB", "B", "E1T", "位于", "E2T", "D9")));
        when(entityMapper.selectBatchIds(any())).thenReturn(
                List.of(entity("E1", "A", "智报", "智报", 1), entity("E2", "A", "云端", "云端", 1)));
        when(entityMapper.selectOne(any())).thenReturn(
                entity("E1T", "B", "智报平台", "智报", 1),
                entity("E2T", "B", "云端", "云端", 3));

        Map<String, Object> r = svc.migrateDocGraph("D1", "A");

        assertThat(r.get("reusedEntities")).isEqualTo(2);
        assertThat(r.get("moved")).isEqualTo(0);
        assertThat(r.get("deduped")).isEqualTo(1);   // 目标库已有 智报-位于-云端，随迁行作重复删
        verify(tripleMapper).deleteById("T1");
        // 复用实体提及累加：E1T 1+1=2，E2T 3+1=4
        ArgumentCaptor<GraphEntity> upd = ArgumentCaptor.forClass(GraphEntity.class);
        verify(entityMapper, times(2)).updateById(upd.capture());
        assertThat(upd.getAllValues().get(0).getMentionCount()).isEqualTo(2);
        assertThat(upd.getAllValues().get(1).getMentionCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("源库共享实体：目标库复制一份，三元组指向副本；独占实体仍整行迁入")
    void copySharedEntity() {
        when(docMapper.selectById("D1")).thenReturn(doc("D1", "B"));
        GraphTriple t1 = triple("T1", "A", "E1", "位于", "E2", "D1");
        when(tripleMapper.selectList(any())).thenReturn(
                List.of(t1),
                // E1 还被 D2 文档的三元组引用 → 共享；E2 无 → 独占
                List.of(triple("T9", "A", "E1", "包含", "EX", "D2")),
                List.of());
        when(entityMapper.selectBatchIds(any())).thenReturn(
                List.of(entity("E1", "A", "智报", "智报", 5), entity("E2", "A", "云端", "云端", 1)));
        when(entityMapper.selectOne(any())).thenReturn(null, null);

        Map<String, Object> r = svc.migrateDocGraph("D1", "A");

        assertThat(r.get("copiedEntities")).isEqualTo(1);
        assertThat(r.get("movedEntities")).isEqualTo(1);
        assertThat(r.get("moved")).isEqualTo(1);
        ArgumentCaptor<GraphEntity> ins = ArgumentCaptor.forClass(GraphEntity.class);
        verify(entityMapper).insert(ins.capture());
        String copyId = ins.getValue().getId();
        assertThat(copyId).isNotBlank();
        assertThat(ins.getValue().getKbId()).isEqualTo("B");
        assertThat(ins.getValue().getNameNorm()).isEqualTo("智报");
        assertThat(ins.getValue().getMentionCount()).isEqualTo(1);   // 只带本文档的引用次数
        // 三元组主体指向副本，客体仍是迁入的独占实体原 id
        ArgumentCaptor<LambdaUpdateWrapper<GraphTriple>> triCap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(tripleMapper).update(isNull(), triCap.capture());
        assertThat(triCap.getValue().getParamNameValuePairs().values()).contains("B", copyId, "E2");
    }

    @Test
    @DisplayName("未移动（前后同库）：不动任何数据")
    void noOpWhenSameKb() {
        when(docMapper.selectById("D1")).thenReturn(doc("D1", "A"));
        Map<String, Object> r = svc.migrateDocGraph("D1", "A");
        assertThat(r.get("moved")).isEqualTo(0);
        verify(tripleMapper, never()).update(any(), any());
        verify(entityMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("存量修复：扫出三元组库≠文档库的文档，逐文档走同一迁移")
    void repairScansMismatchedDocs() {
        when(tripleMapper.mismatchedDocKbPairs()).thenReturn(
                List.of(Map.of("docId", "D1", "fromKbId", "A")));
        when(docMapper.selectById("D1")).thenReturn(doc("D1", "B"));
        GraphTriple t1 = triple("T1", "A", "E1", "位于", "E2", "D1");
        when(tripleMapper.selectList(any())).thenReturn(List.of(t1), List.of(), List.of());
        when(entityMapper.selectBatchIds(any())).thenReturn(
                List.of(entity("E1", "A", "智报", "智报", 1), entity("E2", "A", "云端", "云端", 1)));
        when(entityMapper.selectOne(any())).thenReturn(null, null);

        Map<String, Object> r = svc.repairMovedDocsGraph();

        assertThat(r.get("docs")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) r.get("details");
        assertThat(details.get(0).get("docId")).isEqualTo("D1");
        assertThat(details.get(0).get("moved")).isEqualTo(1);
        verify(tripleMapper).update(isNull(), any());
    }
}
