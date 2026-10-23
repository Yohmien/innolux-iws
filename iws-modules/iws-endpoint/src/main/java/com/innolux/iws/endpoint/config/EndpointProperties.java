/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录部署配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 端点目录部署配置。
 *
 * <p>当前环境只来自部署配置，页面选择不会切换运行时环境。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "iws.endpoint")
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointProperties {

    /**
     * 是否启用端点目录模块。
     */
    private boolean enabled;

    /**
     * 当前运行环境编码。
     */
    private String currentEnvironment;

    /**
     * 获取规范化后的当前环境编码。
     *
     * @return 当前环境编码，未配置时返回 null
     */
    public String getCurrentEnvironment() {
        if (currentEnvironment == null || currentEnvironment.isBlank()) {
            return null;
        }
        return currentEnvironment.trim();
    }
}
