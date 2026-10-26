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

package com.innolux.iws.monitor.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin 监控启动程序
 *
 * @author YONGJIE.PENG
 */
@EnableAdminServer
@SpringBootApplication
public class MonitorAdminApplication {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorAdminApplication.class);

    /**
     * Admin 监控服务启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MonitorAdminApplication.class, args);
        LOG.info("Admin 监控启动成功");
    }

}
