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

package com.innolux.iws.common.mybatis.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL 日志配置。
 *
 * @author YONGJIE.PENG
 */
@Data
@ConfigurationProperties(prefix = "mybatis-plus.sql-log")
public class SqlLogProperties {

    /**
     * 是否开启完整 SQL 输出。
     */
    private Boolean enabled = false;

    /**
     * 输出方式，可选 console、log。
     */
    private String output = "console";

}
