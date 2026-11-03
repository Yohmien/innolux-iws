/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45，加载完整 init、P5 六表和失败注入触发器；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

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

/** 分配锁位隔离 MySQL。 */
final class WmsAllocationReservationTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_allocation_reservation_test";

    private static final String BOOTSTRAP_DATABASE = "iws_wms_allocation_bootstrap";

    private static final String ROOT_PASSWORD = "iws_wms_allocation_root";

    private static final String CHECKPOINT_FAILURE_TRIGGER = """
        CREATE TRIGGER trg_iws_allocation_checkpoint_failure
        BEFORE UPDATE ON iws_process_attempt
        FOR EACH ROW
        BEGIN
            IF NEW.checkpoint_json LIKE '%"fixtureFault":"CHECKPOINT"%' THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture allocation checkpoint failure';
            END IF;
        END
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsAllocationReservationTestDatabase() {
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
                "分配锁位集成测试需要可用的Docker与mysql:8.0.45镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-allocation-test-mysql-stop")
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
            throw new IllegalStateException("分配锁位隔离库创建失败", error);
        }
        try (Connection connection = openRootConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET @iws_inventory_target_schema = DATABASE()");
            }
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath("script/sql/iws_init_from_empty.sql"))
            );
            assertRequiredAllocationTables(connection);
            FileSystemResource businessTask = new FileSystemResource(scriptPath(
                "script/sql/iws_inventory_management_business_task.sql"));
            ScriptUtils.executeSqlScript(connection, businessTask);
            ScriptUtils.executeSqlScript(connection, businessTask);
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath(
                    "script/sql/iws_inventory_management_business_task_verify.sql"))
            );
        } catch (SQLException error) {
            throw new IllegalStateException("分配锁位隔离结构初始化失败", error);
        }
        try (Connection connection = openRootConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CHECKPOINT_FAILURE_TRIGGER);
        } catch (SQLException error) {
            throw new IllegalStateException("分配锁位失败注入触发器初始化失败", error);
        }
    }

    private static void assertRequiredAllocationTables(Connection connection)
        throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 select count(*) from information_schema.tables
                  where table_schema = database()
                    and table_name in (
                        'wms_access_channel_lock',
                        'wms_allocation_candidate',
                        'wms_allocation_decision',
                        'wms_allocation_lock',
                        'wms_allocation_release',
                        'wms_inventory_reservation'
                    )
                 """)) {
            result.next();
            if (result.getLong(1) != 6L) {
                throw new IllegalStateException("正式空库入口未创建完整分配六表");
            }
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
