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

package com.innolux.iws.common.web.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * XSS 过滤配置属性，用于控制过滤器开关及排除路径。
 *
 * @author YONGJIE.PENG
 */
@Data
@ConfigurationProperties(prefix = "xss")
public class XssProperties {

    /**
     * XSS 过滤总开关。
     */
    private Boolean enabled;

    /**
     * 跳过 XSS 过滤的请求路径集合。
     */
    private List<String> excludeUrls = new ArrayList<>();

}
