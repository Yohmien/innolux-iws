/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.oss.factory;

import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.constant.CacheNames;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.oss.client.DefaultOssClientImpl;
import com.innolux.iws.common.oss.client.OssClient;
import com.innolux.iws.common.oss.config.OssClientConfig;
import com.innolux.iws.common.oss.constant.OssConstant;
import com.innolux.iws.common.oss.exception.S3StorageException;
import com.innolux.iws.common.oss.properties.OssProperties;
import com.innolux.iws.common.redis.utils.CacheUtils;
import com.innolux.iws.common.redis.utils.RedisUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S3存储客户端工厂
 *
 * @author YONGJIE.PENG
 */
@Slf4j
public class OssFactory {

    private static final Map<String, OssClient> CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantLock> CLIENT_LOCKS = new ConcurrentHashMap<>();

    /**
     * 获取默认实例
     */
    public static OssClient instance() {
        // 获取redis 默认类型
        String configKey = RedisUtils.getCacheObject(OssConstant.DEFAULT_CONFIG_KEY);
        if (StringUtils.isEmpty(configKey)) {
            throw S3StorageException.form("文件存储服务类型无法找到!");
        }
        return instance(configKey);
    }

    /**
     * 根据类型获取实例
     */
    public static OssClient instance(String configKey) {
        if (StringUtils.isBlank(configKey)) {
            throw S3StorageException.form("文件存储服务类型无法找到!");
        }
        String json = CacheUtils.get(CacheNames.SYS_OSS_CONFIG, configKey);
        if (json == null) {
            throw S3StorageException.form("系统异常, '" + configKey + "'配置信息不存在!");
        }
        OssProperties properties = JsonUtils.parseObject(json, OssProperties.class);
        OssClientConfig config = OssClientConfig.formProperties(properties);
        ReentrantLock lock = getClientLock(configKey);
        lock.lock();
        try {
            OssClient client = CLIENT_CACHE.get(configKey);
            if (client != null) {
                if (client.verifyConfig(config)) {
                    return client;
                }
                closeClient(configKey, client);
            }
            OssClient newClient = new DefaultOssClientImpl(configKey, config);
            CLIENT_CACHE.put(configKey, newClient);
            return newClient;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除实例
     */
    public static boolean remove(String configKey) {
        if (StringUtils.isBlank(configKey)) {
            return false;
        }
        ReentrantLock lock = getClientLock(configKey);
        lock.lock();
        try {
            OssClient client = CLIENT_CACHE.remove(configKey);
            if (client == null) {
                return false;
            }
            closeClient(configKey, client);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定配置键对应的客户端锁。
     *
     * @param configKey 配置键
     * @return 客户端锁
     */
    private static ReentrantLock getClientLock(String configKey) {
        return CLIENT_LOCKS.computeIfAbsent(configKey, key -> new ReentrantLock());
    }

    /**
     * 关闭 OSS 客户端。
     *
     * @param configKey 配置键
     * @param client    OSS 客户端
     */
    private static void closeClient(String configKey, OssClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("S3存储客户端 [{}] 关闭异常，错误信息: {}", configKey, e.getMessage(), e);
        }
    }

}
