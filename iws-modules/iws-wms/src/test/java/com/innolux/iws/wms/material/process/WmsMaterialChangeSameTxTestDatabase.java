/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 SAME_TX 集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45 并加载当前完整 init 与失败注入触发器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.process;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 物料事实 SAME_TX 隔离 MySQL。
 *
 * @author YONGJIE.PENG
 */
final class WmsMaterialChangeSameTxTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_material_same_tx_test";

    private static final String BOOTSTRAP_DATABASE = "iws_wms_same_tx_bootstrap";

    private static final String ROOT_PASSWORD = "iws_wms_same_tx_root";

    private static final String TRACE_ITEM_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_iws_material_change_trace_item_failure
        BEFORE INSERT ON wms_material_trace_item
        FOR EACH ROW
        BEGIN
            IF NEW.stock_unit_code = '__FAIL_ITEM__' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture trace item insert failure';
            END IF;
        END
        """;

    private static final String CHECKPOINT_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_iws_material_change_checkpoint_failure
        BEFORE UPDATE ON iws_process_attempt
        FOR EACH ROW
        BEGIN
            IF NEW.checkpoint_json LIKE '%"fixtureFault":"CHECKPOINT"%' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture checkpoint update failure';
            END IF;
        END
        """;

    private static final String PLATFORM_CLOSE_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_iws_material_change_close_failure
        BEFORE UPDATE ON iws_process_event
        FOR EACH ROW
        BEGIN
            IF NEW.event_status = 'SUCCESS' AND NEW.owner = 'fixture-platform-close' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture platform close failure';
            END IF;
        END
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsMaterialChangeSameTxTestDatabase() {
    }

    static String jdbcUrl() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
            + "/" + DATABASE_NAME
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    static String username() {
        return "root";
    }

    static String password() {
        return ROOT_PASSWORD;
    }

    static Connection openRootConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    static long count(String sql, Object... args) {
        try (Connection connection = openRootConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("独立连接查询失败: " + sql, error);
        }
    }

    static String string(String sql, Object... args) {
        try (Connection connection = openRootConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("独立连接查询失败: " + sql, error);
        }
    }

    static BigDecimal decimal(String sql, Object... args) {
        try (Connection connection = openRootConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBigDecimal(1) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("独立连接查询失败: " + sql, error);
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
    }

    private static MySQLContainer startContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(BOOTSTRAP_DATABASE)
            .withUsername("iws")
            .withPassword(ROOT_PASSWORD)
            .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MYSQL_ROOT_HOST", "%");
        try {
            container.start();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "物料事实SAME_TX集成测试需要可用的Docker与mysql:8.0.45镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-material-change-test-mysql-stop")
        );
        return container;
    }

    private static void initializeSchema() {
        try (Connection bootstrap = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD
        );
             Statement statement = bootstrap.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE_NAME
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        } catch (SQLException error) {
            throw new IllegalStateException("物料事实隔离库创建失败", error);
        }
        try (Connection connection = openRootConnection()) {
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath("script/sql/iws_init_from_empty.sql"))
            );
        } catch (SQLException error) {
            throw new IllegalStateException("物料事实隔离结构初始化失败", error);
        }
        try (Connection connection = openRootConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(TRACE_ITEM_FAILURE_TRIGGER);
            statement.execute(CHECKPOINT_FAILURE_TRIGGER);
            statement.execute(PLATFORM_CLOSE_FAILURE_TRIGGER);
        } catch (SQLException error) {
            throw new IllegalStateException("物料事实失败注入触发器初始化失败", error);
        }
    }

    private static Path scriptPath(String relativePath) {
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
