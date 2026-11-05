/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 使用原 sys_config 保存应用、终端和菜单到受控页面键的映射；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamViewMapping;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.system.domain.SysConfig;
import com.innolux.iws.system.domain.bo.SysConfigBo;
import com.innolux.iws.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 统一界面映射存储与读取。
 */
@Service
@RequiredArgsConstructor
@DS("identity")
public class IamViewMappingService {

    private static final Pattern VIEW_KEY_PATTERN =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}");
    private static final Pattern TERMINAL_PATTERN = Pattern.compile("WEB|PDA");
    private static final String MAPPING_LOCK = "iam:view-mapping-lock";
    private static final String VERSION_KEY = "iam.view.mapping.version";
    private static final String ITEM_KEY_PREFIX = "iam.view.mapping.item.";
    private static final int CONFIG_VALUE_MAX_LENGTH = 500;

    private final SysConfigMapper configMapper;
    private final ISysConfigService configService;

    /**
     * 查询全部映射及版本。
     *
     * @return 映射快照
     */
    public MappingSnapshot snapshot() {
        long version = readVersion();
        List<IamViewMapping> mappings = configMapper.lambda()
            .likeRight(SysConfig::getConfigKey, ITEM_KEY_PREFIX)
            .list()
            .stream()
            .map(item -> JsonUtils.parseObject(item.getConfigValue(), IamViewMapping.class))
            .filter(this::isValid)
            .sorted(Comparator
                .comparing(IamViewMapping::applicationId)
                .thenComparing(IamViewMapping::terminal)
                .thenComparing(IamViewMapping::menuId))
            .toList();
        if (version > 0 || !mappings.isEmpty()) {
            return new MappingSnapshot(version, mappings);
        }
        return readLegacySnapshot();
    }

    /**
     * 查询指定应用和终端的映射。
     *
     * @param applicationId 应用标识
     * @param terminal      终端
     * @return 以菜单主键索引的映射
     */
    public Map<Long, IamViewMapping> find(String applicationId, String terminal) {
        Map<Long, IamViewMapping> result = new LinkedHashMap<>();
        for (IamViewMapping mapping : snapshot().mappings()) {
            if (isValid(mapping)
                && applicationId.equals(mapping.applicationId())
                && terminal.equals(mapping.terminal())) {
                result.put(mapping.menuId(), mapping);
            }
        }
        return result;
    }

    /**
     * 新增或替换一个映射并递增映射版本。
     *
     * @param mapping 待保存映射
     * @return 更新后的快照
     */
    @Transactional(rollbackFor = Exception.class)
    public MappingSnapshot save(IamViewMapping mapping) {
        if (!isValid(mapping)) {
            throw new IllegalArgumentException("统一界面映射格式不合法");
        }
        RLock lock = RedisUtils.getClient().getLock(MAPPING_LOCK);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, 15, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("统一界面映射正在更新，请重试");
            }
            MappingSnapshot current = snapshot();
            List<IamViewMapping> mappings = new ArrayList<>(current.mappings());
            mappings.removeIf(item -> item.applicationId().equals(mapping.applicationId())
                && item.terminal().equals(mapping.terminal())
                && item.menuId().equals(mapping.menuId()));
            mappings.add(mapping);
            long version = current.version() + 1;
            persistMapping(mapping, itemKey(mapping));
            persistVersion(version);
            return new MappingSnapshot(version, List.copyOf(mappings));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("统一界面映射更新被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 删除映射并递增版本。
     *
     * @param applicationId 应用标识
     * @param terminal      终端
     * @param menuId        菜单主键
     * @return 更新后的快照
     */
    @Transactional(rollbackFor = Exception.class)
    public MappingSnapshot remove(String applicationId, String terminal, Long menuId) {
        RLock lock = RedisUtils.getClient().getLock(MAPPING_LOCK);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, 15, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("统一界面映射正在更新，请重试");
            }
            MappingSnapshot current = snapshot();
            List<IamViewMapping> mappings = new ArrayList<>(current.mappings());
            boolean removed = mappings.removeIf(item -> item.applicationId().equals(applicationId)
                && item.terminal().equals(terminal)
                && item.menuId().equals(menuId));
            if (!removed) {
                return current;
            }
            long version = current.version() + 1;
            removeMapping(applicationId, terminal, menuId);
            persistVersion(version);
            return new MappingSnapshot(version, List.copyOf(mappings));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("统一界面映射更新被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 持久化到原 sys_config 并触发现有缓存更新。
     *
     * @param value JSON 配置值
     */
    private void persistMapping(IamViewMapping mapping, String configKey) {
        String value = JsonUtils.toJsonString(mapping);
        if (value.length() > CONFIG_VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("统一界面映射超过原 sys_config 键值长度限制");
        }
        persistValue(configKey, "统一身份界面映射项", value,
            "applicationId + terminal + menuId -> viewKey；由统一身份适配器读取");
    }

    /**
     * 保存当前映射版本。
     *
     * @param version 版本号
     */
    private void persistVersion(long version) {
        persistValue(VERSION_KEY, "统一身份界面映射版本", Long.toString(version),
            "每次映射增删改递增；客户端用于识别上下文变化");
    }

    /**
     * 新增或更新单个原 sys_config 项。
     *
     * @param configKey 参数键
     * @param configName 参数名称
     * @param value 参数值
     * @param remark 备注
     */
    private void persistValue(String configKey, String configName, String value, String remark) {
        SysConfig existing = configMapper.lambda()
            .eq(SysConfig::getConfigKey, configKey)
            .one();
        SysConfigBo bo = new SysConfigBo();
        bo.setConfigId(existing == null ? null : existing.getConfigId());
        bo.setConfigName(configName);
        bo.setConfigKey(configKey);
        bo.setConfigValue(value);
        bo.setConfigType("N");
        bo.setRemark(remark);
        if (existing == null) {
            configService.insertConfig(bo);
        } else {
            configService.updateConfig(bo);
        }
    }

    /**
     * 删除一个映射项。
     *
     * @param applicationId 应用标识
     * @param terminal 终端
     * @param menuId 菜单主键
     */
    private void removeMapping(String applicationId, String terminal, Long menuId) {
        SysConfig existing = configMapper.lambda()
            .eq(SysConfig::getConfigKey, itemKey(applicationId, terminal, menuId))
            .one();
        if (existing != null) {
            configService.deleteConfigByIds(List.of(existing.getConfigId()));
        }
    }

    /**
     * 读取版本号；损坏值回退为 0，避免错误版本影响页面上下文。
     *
     * @return 版本号
     */
    private long readVersion() {
        SysConfig versionConfig = configMapper.lambda()
            .eq(SysConfig::getConfigKey, VERSION_KEY)
            .one();
        String value = versionConfig == null ? null : versionConfig.getConfigValue();
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 读取旧版单文档映射，仅作为未迁移开发现场的回退。
     *
     * @return 映射快照
     */
    private MappingSnapshot readLegacySnapshot() {
        String payload = configService.selectConfigByKey(IamProtocolConstants.VIEW_MAPPING_CONFIG_KEY);
        MappingDocument document = payload == null || payload.isBlank()
            ? null
            : JsonUtils.parseObject(payload, MappingDocument.class);
        if (document == null || document.mappings() == null) {
            return new MappingSnapshot(0, List.of());
        }
        return new MappingSnapshot(
            document.version(),
            document.mappings().stream().filter(this::isValid).toList()
        );
    }

    /**
     * 生成稳定且不超过参数键长度限制的配置键。
     *
     * @param mapping 映射
     * @return 参数键
     */
    private String itemKey(IamViewMapping mapping) {
        return itemKey(mapping.applicationId(), mapping.terminal(), mapping.menuId());
    }

    /**
     * 生成稳定且不超过参数键长度限制的配置键。
     *
     * @param applicationId 应用标识
     * @param terminal 终端
     * @param menuId 菜单主键
     * @return 参数键
     */
    private String itemKey(String applicationId, String terminal, Long menuId) {
        String identity = applicationId + ":" + terminal + ":" + menuId;
        return ITEM_KEY_PREFIX + DigestUtil.sha256Hex(identity);
    }

    /**
     * 读取时再次执行格式校验，损坏配置不生效。
     *
     * @param mapping 映射
     * @return 是否有效
     */
    private boolean isValid(IamViewMapping mapping) {
        return mapping != null
            && mapping.applicationId() != null
            && !mapping.applicationId().isBlank()
            && mapping.menuId() != null
            && mapping.menuId() > 0
            && mapping.terminal() != null
            && TERMINAL_PATTERN.matcher(mapping.terminal()).matches()
            && mapping.viewKey() != null
            && VIEW_KEY_PATTERN.matcher(mapping.viewKey()).matches();
    }

    /**
     * 映射版本和列表。
     *
     * @param version  版本
     * @param mappings 映射
     */
    public record MappingSnapshot(long version, List<IamViewMapping> mappings) {
    }

    /**
     * sys_config 中的映射文档。
     *
     * @param version  版本
     * @param mappings 映射
     */
    public record MappingDocument(long version, List<IamViewMapping> mappings) {
    }
}
