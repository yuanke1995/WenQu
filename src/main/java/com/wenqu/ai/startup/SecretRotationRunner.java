package com.wenqu.ai.startup;

import com.wenqu.ai.service.ConfigCryptoService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.DeploymentSecrets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 存量密文搬到当前密钥与 JWE 格式（Phase 0 密钥集中与轮换，docs/enterprise-upgrade-plan.md §4.1 P4）。
 * <p>
 * 六张表里所有敏感字段逐行判定：
 * <ol>
 *   <li>旧分块 RSA（{@code RSA:…}）→ 用 {@link LegacyRsaCipher} 读出明文，重新封成 JWE（当前 kid）；</li>
 *   <li>已是 JWE 但 kid 不是当前版本（轮换后的过渡态）→ {@code reencrypt} 搬到当前 kid；</li>
 *   <li>已经是当前 kid → 不动。</li>
 * </ol>
 * <b>只在确实解得开时才改写</b>：解不开（kid 不在密钥环里、密文损坏）就原样留下并计数告警——
 * 把解不开的密文当明文再封一层，等于把「密钥不一致」这个可诊断故障洗成不可逆的数据损坏。
 * <p>
 * 所以轮换的完整动作是：往密钥目录放一把更高版本的 PEM 私钥（{@code rsa/v4.key}）→ 重启 →
 * 本 Runner 把存量搬到新 kid → 旧密钥文件即可撤。两把并存的窗口就是这段重封过程，不需要停机，
 * 也不存在读到解不开的路径。表名/列名全是本类常量，无外部输入拼接。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(4)
public class SecretRotationRunner implements ApplicationRunner {

    /** 存放密文的列：{表, 主键列, 密文列} */
    private static final String[][] CIPHER_COLUMNS = {
            {"c_ai_config", "config_key", "config_value"},
            {"c_ai_user_config", "id", "config_value"},
            {"c_ai_credential", "id", "value"},
            {"c_ai_provider", "id", "api_key"},
            {"c_ai_user_mcp", "id", "headers"},
            {"c_ai_identity_provider", "id", "config"},
    };

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCryptoService crypto;
    private final ConfigService configService;
    private final DeploymentSecrets secrets;

    public SecretRotationRunner(JdbcTemplate jdbcTemplate, ConfigCryptoService crypto,
                               ConfigService configService, DeploymentSecrets secrets) {
        this.jdbcTemplate = jdbcTemplate;
        this.crypto = crypto;
        this.configService = configService;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (crypto.currentVersion() <= 0) {
            log.warn("[Rotation] RSA 密钥环为空，跳过密文搬迁（敏感配置的密钥不一致问题需先修密钥目录）");
            return;
        }
        int resealed = 0;
        int fromLegacy = 0;
        int undecryptable = 0;
        boolean configTableChanged = false;
        for (String[] t : CIPHER_COLUMNS) {
            try {
                int[] counts = resealTable(t[0], t[1], t[2]);
                resealed += counts[0];
                fromLegacy += counts[1];
                undecryptable += counts[2];
                if (counts[0] > 0 && "c_ai_config".equals(t[0])) {
                    configTableChanged = true;
                }
            } catch (Exception e) {
                log.warn("[Rotation] {} 搬迁失败（不阻塞启动）: {}", t[0], e.getMessage());
            }
        }
        if (configTableChanged) {
            // 内存缓存里还握着旧形态密文，重载一次让本副本立刻用上新密文（读侧两种形态都认，不重载也不会坏）
            configService.reload();
        }
        if (resealed > 0 || undecryptable > 0) {
            log.info("[Rotation] 存量密文重封到当前密钥 {}：{} 行（其中旧 RSA 格式搬迁 {} 行）；解不开而保留原值 {} 行",
                    secrets.currentKid(), resealed, fromLegacy, undecryptable);
        }
        if (undecryptable > 0) {
            log.error("[Rotation] 有 {} 行密文用现有密钥环解不开（密钥目录可能不是共享那一份，或历史密钥已撤下）——"
                    + "这些值参与调用时会网关 401，请补回对应 kid 的密钥文件后重启", undecryptable);
        }
    }

    /** @return {搬迁行数, 其中旧格式行数, 解不开行数} */
    private int[] resealTable(String table, String idColumn, String valueColumn) {
        String sql = "select " + idColumn + " as row_id, " + valueColumn + " as cipher from " + table
                + " where " + valueColumn + " like 'RSA:%' or " + valueColumn + " like 'JWE:%'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        String update = "update " + table + " set " + valueColumn + " = ? where " + idColumn + " = ?";
        int moved = 0;
        int legacy = 0;
        int failed = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("row_id");
            String stored = row.get("cipher") == null ? null : String.valueOf(row.get("cipher"));
            if (stored == null) {
                continue;
            }
            String fresh;
            boolean isLegacy = LegacyRsaCipher.isLegacy(stored);
            if (isLegacy) {
                String plain = LegacyRsaCipher.decrypt(stored, secrets::byKid);
                fresh = plain == null ? null : crypto.encrypt(plain);
            } else {
                if (crypto.onCurrentKey(stored)) {
                    continue;
                }
                fresh = crypto.reencrypt(stored);
            }
            if (fresh == null) {
                failed++;
                continue;
            }
            jdbcTemplate.update(update, fresh, id);
            moved++;
            if (isLegacy) {
                legacy++;
            }
        }
        return new int[]{moved, legacy, failed};
    }
}
