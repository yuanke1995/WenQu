package com.wenqu.ai.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.wenqu.ai.util.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 租户行级过滤判据（Phase 4 · P9），交给 MyBatis-Plus 的 {@code TenantLineInnerInterceptor} 用——
 * 谓词由框架拼在 SQL 上，业务代码不写 {@code tenant_id = ?}，写了两处就会漂。
 *
 * <p><b>哪些表不参与过滤</b>（刻意如此，不是漏配）：
 * <ul>
 *   <li>{@code c_ai_tenant}：注册表本身，平台管理动作要跨租户看全；</li>
 *   <li>RBAC 资源定义（{@code c_ai_menu}/{@code c_ai_api}/{@code c_ai_role} 及三张绑定表）：
 *       菜单与接口清单是平台内置的，角色目前是平台级预置（租户自定义角色是后续能力，见 plan §4.5 边界）；</li>
 *   <li>{@code c_ai_provider}/{@code c_ai_model}/{@code c_ai_config}：供应商、模型与平台参数由平台运营统一登记，
 *       各租户共用同一份网关与价格表——按租户各配一份是运维负担，也是「同一平台不同租户其实连的是同一个网关」
 *       这种事实的掩盖；</li>
 *   <li>{@code c_ai_image_desc}：内容寻址的图片描述缓存，跨租户命中是省一次视觉调用，不泄露归属信息；</li>
 *   <li>{@code c_ai_market_item}/{@code c_ai_market_install}：市场当前是平台级共享（官方模板 + 同事上架）。
 *       租户内市场要的是「本租户私有 ∪ 平台公共」两档可见性，而 {@code TenantLineHandler} 只能表达
 *       {@code tenant_id = ?} 一个谓词——硬套会把官方模板挡掉，所以留到运营交付刀用显式 SQL 做。</li>
 * </ul>
 *
 * <p><b>未绑定租户时不过滤</b>（{@link #ignoreTable}）：启动任务、调度器、清理任务本就要遍历所有租户的行。
 * 代价写在 {@link TenantContext} 的注释里——这些线程里「给某个用户看」的查询必须显式 {@code TenantContext.as(...)}，
 * 不能指望这道闸替它们兜。
 *
 * @author yuanke
 */
@Component
public class WenquTenantLineHandler implements TenantLineHandler {

    /** 平台全局表：不加租户谓词，也不要求有 tenant_id 列 */
    private static final Set<String> GLOBAL_TABLES = Set.of(
            "c_ai_tenant",
            // 开通申请发生在「还没有租户」的时候（Phase 4 刀三）：给它编一个归属等于把「谁申请的」写成假值
            "c_ai_tenant_request",
            "c_ai_menu", "c_ai_menu_api", "c_ai_api", "c_ai_role", "c_ai_role_menu", "c_ai_role_api",
            "c_ai_provider", "c_ai_model", "c_ai_config", "c_ai_image_desc",
            "c_ai_market_item", "c_ai_market_install");

    @Override
    public Expression getTenantId() {
        String t = TenantContext.current();
        return new StringValue(t == null ? TenantContext.DEFAULT_TENANT : t);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    /** 是否平台全局表（SchemaMigrator 的租户列归一据此跳过它们） */
    public static boolean isGlobalTable(String tableName) {
        if (tableName == null) return true;
        return GLOBAL_TABLES.contains(tableName.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (tableName == null) return true;
        String t = tableName.toLowerCase(java.util.Locale.ROOT);
        if (GLOBAL_TABLES.contains(t)) return true;
        // 平台视图与未绑定线程：不注入谓词（前者是显式的跨租户管理动作，后者是系统任务）
        return TenantContext.inPlatformView() || TenantContext.current() == null;
    }

    /**
     * 插入时若语句里已经显式带了 {@code tenant_id} 列，就不再让框架补第二份。
     * <p>第二份会让 MySQL 报「column specified twice」——开通租户、迁移归属这类带显式归属的写入会当场失败。
     */
    @Override
    public boolean ignoreInsert(List<Column> columns, String tenantIdColumn) {
        if (columns == null || columns.isEmpty()) return false;
        for (Column c : columns) {
            String name = c.getColumnName() == null ? "" : c.getColumnName().replace("`", "");
            if (tenantIdColumn.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
