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

package com.innolux.iws.gen.config.properties;

import lombok.Data;
import com.innolux.iws.common.core.factory.YmlPropertySourceFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * 代码生成配置属性
 *
 * @author YONGJIE.PENG
 */
@Data
@Component
@ConfigurationProperties(prefix = "gen")
@PropertySource(value = "classpath:generator.yml", factory = YmlPropertySourceFactory.class)
public class GenProperties {

    /**
     * 作者
     */
    private String author;

    /**
     * 生成包路径
     */
    private String packageName;

    /**
     * 自动去除表前缀，默认是false
     */
    private boolean autoRemovePre = false;

    /**
     * 表前缀(类名不会包含表前缀)
     */
    private String tablePrefix;

}
