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

package com.innolux.iws.system.listener;

import com.innolux.iws.common.core.constant.CacheNames;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.oss.constant.OssConstant;
import com.innolux.iws.common.oss.factory.OssFactory;
import com.innolux.iws.common.redis.utils.CacheUtils;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.system.event.OssConfigChangeEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * OSS 配置变更监听器。
 *
 * @author YONGJIE.PENG
 */
@Component
public class OssConfigChangeListener {

    /**
     * 数据提交后同步刷新 OSS 配置缓存与客户端实例。
     *
     * @param event OSS 配置变更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void refreshOssConfig(OssConfigChangeEvent event) {
        if (event.defaultConfig()) {
            RedisUtils.setCacheObject(OssConstant.DEFAULT_CONFIG_KEY, event.configKey());
            return;
        }
        if (StringUtils.isNotBlank(event.oldConfigKey()) && !StringUtils.equals(event.oldConfigKey(), event.configKey())) {
            CacheUtils.evict(CacheNames.SYS_OSS_CONFIG, event.oldConfigKey());
            OssFactory.remove(event.oldConfigKey());
        }
        if (StringUtils.isBlank(event.configKey())) {
            return;
        }
        if (StringUtils.isBlank(event.configJson())) {
            CacheUtils.evict(CacheNames.SYS_OSS_CONFIG, event.configKey());
        } else {
            CacheUtils.put(CacheNames.SYS_OSS_CONFIG, event.configKey(), event.configJson());
        }
        OssFactory.remove(event.configKey());
    }

}
