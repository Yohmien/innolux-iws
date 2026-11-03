/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45 并加载正式 core 与 trace 失败注入 fixture；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 物料 trace 隔离 MySQL 测试库。
 *
 * <p>容器只存在于测试 JVM，结构直接执行当前正式 core；额外触发器只用于证明写头后失败会回滚，
 * 不进入产品或数据库升级脚本。</p>
 *
 * @author YONGJIE.PENG
 */
final class WmsMaterialTraceTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_material_trace_test";

    private static final String DATABASE_USER = "iws_wms_material_trace_test";

    private static final String DATABASE_PASSWORD = "iws_wms_material_trace_test";

    private static final String ITEM_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_wms_material_trace_item_failure
        BEFORE INSERT ON wms_material_trace_item
        FOR EACH ROW
        BEGIN
            IF NEW.stock_unit_code = '__FAIL_ITEM__' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture item insert failure';
            END IF;
        END
        """;

    private static final String EDGE_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_wms_material_trace_edge_failure
        BEFORE INSERT ON wms_material_trace_edge
        FOR EACH ROW
        BEGIN
            IF NEW.group_no = 9999 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture edge insert failure';
            END IF;
        END
        """;

    private static final String CURRENT_NAME_FIXTURE = """
        CREATE TABLE wms_material_trace_current_name_fixture (
            fixture_id bigint NOT NULL,
            display_name varchar(128) NOT NULL,
            PRIMARY KEY (fixture_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsMaterialTraceTestDatabase() {
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
            .withPassword(DATABASE_PASSWORD)
            .withEnv("MYSQL_ROOT_PASSWORD", DATABASE_PASSWORD)
            .withEnv("MYSQL_ROOT_HOST", "%");
        try {
            container.start();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "物料trace集成测试需要可用的Docker与mysql:8.0.45镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-material-trace-test-mysql-stop")
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
        } catch (SQLException error) {
            throw new IllegalStateException("物料trace隔离结构初始化失败", error);
        }
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(), "root", DATABASE_PASSWORD
        )) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ITEM_FAILURE_TRIGGER);
                statement.execute(EDGE_FAILURE_TRIGGER);
                statement.execute(CURRENT_NAME_FIXTURE);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("物料trace隔离结构初始化失败", error);
        }
    }

    static Path scriptPath(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth <= 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (java.nio.file.Files.isRegularFile(candidate)) {
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
