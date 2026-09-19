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

package com.innolux.iws.common.sms.core.dao;

import com.innolux.iws.common.core.constant.GlobalConstants;
import com.innolux.iws.common.redis.utils.RedisUtils;
import org.dromara.sms4j.api.dao.SmsDao;

import java.time.Duration;

/**
 * SmsDao缓存配置 (使用框架自带RedisUtils实现 协议统一)
 * <p>主要用于短信重试和拦截的缓存
 *
 * @author YONGJIE.PENG
 */
public class PlusSmsDao implements SmsDao {

    /**
     * 存储
     *
     * @param key       键
     * @param value     值
     * @param cacheTime 缓存时间（单位：秒)
     */
    @Override
    public void set(String key, Object value, long cacheTime) {
        RedisUtils.setCacheObject(GlobalConstants.GLOBAL_REDIS_KEY + key, value, Duration.ofSeconds(cacheTime));
    }

    /**
     * 存储
     *
     * @param key   键
     * @param value 值
     */
    @Override
    public void set(String key, Object value) {
        RedisUtils.setCacheObject(GlobalConstants.GLOBAL_REDIS_KEY + key, value, true);
    }

    /**
     * 读取
     *
     * @param key 键
     * @return 值
     */
    @Override
    public Object get(String key) {
        return RedisUtils.getCacheObject(GlobalConstants.GLOBAL_REDIS_KEY + key);
    }

    /**
     * remove
     * <p> 根据key移除缓存
     *
     * @param key 缓存键
     * @return 被删除的value
     * @author YONGJIE.PENG
     */
    @Override
    public Object remove(String key) {
        return RedisUtils.deleteObject(GlobalConstants.GLOBAL_REDIS_KEY + key);
    }

    /**
     * 清空
     */
    @Override
    public void clean() {
        RedisUtils.deleteKeys(GlobalConstants.GLOBAL_REDIS_KEY + "sms:*");
    }

}
