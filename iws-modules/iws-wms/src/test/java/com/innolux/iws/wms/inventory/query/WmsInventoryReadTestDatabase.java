/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45 并加载正式 core 与最小计划事件结构；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * P7 只读查询隔离 MySQL 测试库。
 */
final class WmsInventoryReadTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_inventory_read_test";

    private static final String DATABASE_USER = "iws_wms_inventory_read_test";

    private static final String DATABASE_PASSWORD = "iws_wms_inventory_read_test";

    private static final String PROCESS_FIXTURE = """
        CREATE TABLE IF NOT EXISTS wms_business_task (
            task_id bigint NOT NULL,
            business_type varchar(64) NOT NULL,
            business_id bigint NOT NULL,
            line_id bigint NULL,
            allocation_unit_id bigint NULL,
            round_no int NOT NULL DEFAULT 0,
            phase varchar(32) NOT NULL,
            task_key varchar(96) NOT NULL,
            plan_type varchar(64) NOT NULL,
            plan_instance_id bigint NULL,
            input_hash char(64) NOT NULL,
            input_json longtext NOT NULL,
            status varchar(24) NOT NULL,
            revision int NOT NULL DEFAULT 1,
            create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            PRIMARY KEY (task_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS iws_process_instance (
            id bigint NOT NULL,
            plan_status varchar(24) NOT NULL,
            instance_version bigint NOT NULL DEFAULT 0,
            started_at datetime(3) NULL,
            ended_at datetime(3) NULL,
            PRIMARY KEY (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

        CREATE TABLE IF NOT EXISTS iws_process_event (
            id bigint NOT NULL,
            instance_id bigint NOT NULL,
            event_code varchar(64) NOT NULL,
            event_status varchar(32) NOT NULL,
            effect_result varchar(24) NULL,
            update_time datetime(3) NOT NULL,
            PRIMARY KEY (id),
            KEY idx_iws_process_event_instance (instance_id, update_time, id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

        CREATE TABLE IF NOT EXISTS wms_allocation_lock (
            allocation_lock_id bigint NOT NULL,
            channel_lock_id bigint NULL,
            storage_id bigint NOT NULL,
            business_type varchar(64) NOT NULL,
            business_name varchar(128) NULL,
            business_id bigint NOT NULL,
            business_no varchar(64) NULL,
            business_line_id bigint NULL,
            allocation_unit_id bigint NOT NULL,
            round_no int NOT NULL,
            task_key varchar(96) NOT NULL,
            plan_instance_id bigint NOT NULL,
            status varchar(16) NOT NULL,
            effect_result varchar(24) NULL,
            create_dept bigint NULL,
            create_by bigint NULL,
            update_time datetime(3) NOT NULL,
            PRIMARY KEY (allocation_lock_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS wms_access_channel_lock (
            channel_lock_id bigint NOT NULL,
            channel_id bigint NOT NULL,
            status varchar(16) NOT NULL,
            effect_result varchar(24) NULL,
            PRIMARY KEY (channel_lock_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS wms_object_audit (
            audit_id bigint NOT NULL,
            object_type varchar(64) NOT NULL,
            object_id bigint NOT NULL,
            action varchar(64) NOT NULL,
            evidence_json longtext NULL,
            occurred_at datetime(3) NOT NULL,
            recorded_at datetime(3) NOT NULL,
            PRIMARY KEY (audit_id),
            KEY idx_wms_object_audit_lookup (
                object_type, object_id, action, recorded_at, audit_id
            )
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsInventoryReadTestDatabase() {
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
                "P7只读查询集成测试需要可用的Docker与mysql:8.0.45镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-inventory-read-test-mysql-stop")
        );
        return container;
    }

    private static void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(), username(), password()
        )) {
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath("script/sql/iws_wms_smt_core.sql"))
            );
            ScriptUtils.executeSqlScript(
                connection,
                new ByteArrayResource(PROCESS_FIXTURE.getBytes(StandardCharsets.UTF_8))
            );
        } catch (SQLException error) {
            throw new IllegalStateException("P7只读查询隔离结构初始化失败", error);
        }
    }

    static Path scriptPath(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth <= 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "找不到项目脚本: " + relativePath + "，当前工作目录 "
                + System.getProperty("user.dir")
        );
    }
}
