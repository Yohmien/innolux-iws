/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45 并建立目录及计划事件最小真实结构；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog;

import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 库存业务目录隔离 MySQL 测试库。
 *
 * @author YONGJIE.PENG
 */
final class WmsCatalogTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_catalog_test";

    private static final String DATABASE_USER = "iws_wms_catalog_test";

    private static final String DATABASE_PASSWORD = "iws_wms_catalog_test";

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS wms_business_type (
            business_type_code varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务类型编码',
            business_type_name varchar(128) NOT NULL COMMENT '业务类型名称',
            status varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
            contract_version varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务合同版本',
            object_policy_key varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '对象策略键；本切面不注册策略',
            revision int NOT NULL DEFAULT 1 COMMENT '修订号',
            create_dept bigint DEFAULT NULL COMMENT '创建部门',
            create_by bigint DEFAULT NULL COMMENT '创建者',
            create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
            update_by bigint DEFAULT NULL COMMENT '更新者',
            update_time datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
            PRIMARY KEY (business_type_code),
            KEY idx_wms_business_type_status (status, business_type_code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS业务类型目录';

        CREATE TABLE IF NOT EXISTS wms_business_plan_mount (
            mount_id bigint NOT NULL COMMENT '挂载主键，系统生成',
            business_type_code varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务类型编码',
            plan_type_code varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '计划类型编码',
            schedule_code varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '调度业务编码，等于iws_business_schedule.business_code',
            contract_version varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '挂载合同版本',
            enabled tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
            revision int NOT NULL DEFAULT 1 COMMENT '修订号',
            create_dept bigint DEFAULT NULL COMMENT '创建部门',
            create_by bigint DEFAULT NULL COMMENT '创建者',
            create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
            update_by bigint DEFAULT NULL COMMENT '更新者',
            update_time datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
            PRIMARY KEY (mount_id),
            UNIQUE KEY uk_wms_business_plan_mount (business_type_code, plan_type_code, schedule_code),
            KEY idx_wms_business_plan_mount_resolve (schedule_code, plan_type_code, enabled)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS业务类型计划挂载目录';

        CREATE TABLE IF NOT EXISTS iws_business_schedule (
            id bigint NOT NULL,
            business_code varchar(64) NOT NULL,
            business_name varchar(128) NOT NULL,
            enabled tinyint(1) NOT NULL DEFAULT 1,
            fairness_window_size int NULL,
            revision bigint NOT NULL DEFAULT 0,
            remark varchar(500) NULL,
            del_flag char(1) NOT NULL DEFAULT '0',
            create_dept bigint NULL,
            create_by bigint NULL,
            create_time datetime(3) NOT NULL,
            update_by bigint NULL,
            update_time datetime(3) NOT NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_iws_business_schedule_code (business_code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

        CREATE TABLE IF NOT EXISTS iws_business_schedule_plan_type (
            id bigint NOT NULL,
            schedule_id bigint NOT NULL,
            plan_type varchar(64) NOT NULL,
            plan_type_name varchar(128) NULL,
            priority int NOT NULL DEFAULT 0,
            enabled tinyint(1) NOT NULL DEFAULT 1,
            create_dept bigint NULL,
            create_by bigint NULL,
            create_time datetime(3) NOT NULL,
            update_by bigint NULL,
            update_time datetime(3) NOT NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_iws_business_schedule_plan_type (schedule_id, plan_type)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsCatalogTestDatabase() {
    }

    static String jdbcUrl() {
        String url = MYSQL.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    static String username() {
        return DATABASE_USER;
    }

    static String password() {
        return DATABASE_PASSWORD;
    }

    private static MySQLContainer startContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);
        try {
            container.start();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "库存目录集成测试需要可用的 Docker 与 mysql:8.0.45 镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "wms-catalog-test-mysql-stop"));
        return container;
    }

    private static void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(), username(), password()); Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("库存目录测试结构初始化失败", error);
        }
    }
}
