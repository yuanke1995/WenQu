package com.wisesoft.wenqu.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.repository.port.UserConfigMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 用户级配置模块。
 *
 * <p>由参考实现的 config/user.py 逐段翻译：UserConfigSchema（enable_memory，
 * extra=forbid 即未知键拒绝）、UserConfig 访问器（每次加载都从数据库查询，不做进程缓存；
 * save 先更新、零行则插入，唯一键冲突时回滚重试一次）。
 *
 * <p>必要替换：pydantic BaseModel → 校验由字段落库路径承担（本 schema 仅一个布尔字段，
 * 未知键拒绝语义由调用方载荷校验实现）；{@code update rowcount==0} → MyBatis-Plus
 * update 影响行数；IntegrityError 重试 → DataIntegrityViolationException 重试。
 */
@Service
public class UserConfigService {

    /** 用户专属配置 schema（参考实现 UserConfigSchema）。 */
    public static final class UserConfigSchema {

        /** 是否启用 Memory。 */
        private boolean enableMemory = false;

        public UserConfigSchema() {}

        public UserConfigSchema(boolean enableMemory) {
            this.enableMemory = enableMemory;
        }

        public boolean isEnableMemory() {
            return enableMemory;
        }
    }

    /** 用户级配置访问器（对应参考实现 UserConfig）。 */
    public static final class UserConfig {

        private final String uid;
        private final UserConfigSchema schema;
        private final LocalDateTime updatedAt;

        public UserConfig(String uid, UserConfigSchema schema, LocalDateTime updatedAt) {
            this.uid = uid;
            this.schema = schema != null ? schema : new UserConfigSchema();
            this.updatedAt = updatedAt;
        }

        public String getUid() {
            return uid;
        }

        public UserConfigSchema getSchema() {
            return schema;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        /** dump_config：键与时间格式照搬。 */
        public Map<String, Object> dumpConfig() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uid", uid);
            result.put("enable_memory", schema.isEnableMemory());
            result.put("updated_at", DateTimeUtils.formatUtcDatetime(updatedAt));
            return result;
        }
    }

    private final UserConfigMapper userConfigMapper;

    public UserConfigService(UserConfigMapper userConfigMapper) {
        this.userConfigMapper = userConfigMapper;
    }

    /** load：每次都从数据库查询，记录缺失时返回默认 schema。 */
    public UserConfig load(String uid) {
        com.wisesoft.wenqu.models.UserConfig record = userConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wisesoft.wenqu.models.UserConfig>()
                        .eq(com.wisesoft.wenqu.models.UserConfig::getUid, uid));
        if (record == null) {
            return new UserConfig(uid, null, null);
        }
        return new UserConfig(
                uid,
                new UserConfigSchema(Boolean.TRUE.equals(record.getEnableMemory())),
                record.getUpdatedAt());
    }

    /** save：先更新、零行则插入；插入唯一键冲突时重试一次更新（对应参考实现 commit 冲突回滚重试）。 */
    public UserConfig save(UserConfig config) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        int updated = userConfigMapper.update(
                null,
                new LambdaUpdateWrapper<com.wisesoft.wenqu.models.UserConfig>()
                        .eq(com.wisesoft.wenqu.models.UserConfig::getUid, config.getUid())
                        .set(com.wisesoft.wenqu.models.UserConfig::getEnableMemory, config.getSchema().isEnableMemory())
                        .set(com.wisesoft.wenqu.models.UserConfig::getUpdatedAt, now));
        if (updated == 0) {
            com.wisesoft.wenqu.models.UserConfig record = new com.wisesoft.wenqu.models.UserConfig();
            record.setUid(config.getUid());
            record.setEnableMemory(config.getSchema().isEnableMemory());
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                userConfigMapper.insert(record);
            } catch (DataIntegrityViolationException exc) {
                // 并发插入冲突：回滚语义下重试一次更新；仍零行则原样上抛
                int retry = userConfigMapper.update(
                        null,
                        new LambdaUpdateWrapper<com.wisesoft.wenqu.models.UserConfig>()
                                .eq(com.wisesoft.wenqu.models.UserConfig::getUid, config.getUid())
                                .set(com.wisesoft.wenqu.models.UserConfig::getEnableMemory, config.getSchema().isEnableMemory())
                                .set(com.wisesoft.wenqu.models.UserConfig::getUpdatedAt, now));
                if (retry == 0) {
                    throw exc;
                }
            }
        }
        return new UserConfig(config.getUid(), config.getSchema(), now);
    }
}
