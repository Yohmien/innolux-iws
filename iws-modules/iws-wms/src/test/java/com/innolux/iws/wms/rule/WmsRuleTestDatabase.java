/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 启动一次性 MySQL 8.0.45 并执行正式规则表 DDL 建立隔离测试库；
 * @ModifyRecord : V1.0 新建；V1.1 增加规则范围 Guard 预期目标 schema fixture；
 */

package com.innolux.iws.wms.rule;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 在库管理规则隔离 MySQL 测试库。
 *
 * <p>容器只在测试 JVM 内启动，数据库名、账号和端口均由容器分配；结构执行仓库内正式 core、
 * 规则 DDL 和规则发布 Scope Guard 的预期目标 fixture。</p>
 *
 * <p>{@code zone_kind} 与库区、储位 {@code lifecycle_status} 已进入产品 core/init，
 * 本 fixture 直接验证同一正式空间结构。</p>
 *
 * @author YONGJIE.PENG
 */
final class WmsRuleTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_rule_test";

    private static final String DATABASE_USER = "iws_wms_rule_test";

    private static final String DATABASE_PASSWORD = "iws_wms_rule_test";

    private static final String TEST_SCOPE_SCHEMA = """
        CREATE TABLE IF NOT EXISTS sys_role (
            role_id bigint NOT NULL,
            role_name varchar(64) NOT NULL,
            status char(1) NOT NULL DEFAULT '0',
            del_flag char(1) NOT NULL DEFAULT '0',
            PRIMARY KEY (role_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS sys_user_role (
            user_id bigint NOT NULL,
            role_id bigint NOT NULL,
            PRIMARY KEY (user_id, role_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS sys_menu (
            menu_id bigint NOT NULL,
            menu_name varchar(64) NOT NULL,
            perms varchar(128) NULL,
            status char(1) NOT NULL DEFAULT '0',
            PRIMARY KEY (menu_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

        CREATE TABLE IF NOT EXISTS sys_role_menu (
            role_id bigint NOT NULL,
            menu_id bigint NOT NULL,
            PRIMARY KEY (role_id, menu_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
        """;

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsRuleTestDatabase() {
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

    static void executeAsRoot(String sql) {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", DATABASE_PASSWORD
        ); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException error) {
            throw new IllegalStateException("规则库失败注入执行失败", error);
        }
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
                "规则服务集成测试需要可用的 Docker 与 mysql:8.0.45 镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "wms-rule-test-mysql-stop"));
        return container;
    }

    private static void initializeSchema() {
        grantMetadataVisibility();
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(), username(), password()
        )) {
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath("script/sql/iws_wms_smt_core.sql"))
            );
            ScriptUtils.executeSqlScript(
                connection,
                new ByteArrayResource(TEST_SCOPE_SCHEMA.getBytes(StandardCharsets.UTF_8))
            );
            String script = Files.readString(
                scriptPath("script/sql/iws_inventory_management_rules.sql"),
                StandardCharsets.UTF_8
            ).replace(
                "SET @target_schema = 'innolux-iws';",
                "SET @target_schema = '" + DATABASE_NAME + "';"
            );
            ScriptUtils.executeSqlScript(
                connection,
                new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))
            );
            String bindingUpgrade = Files.readString(
                scriptPath("script/sql/iws_inventory_rule_attribute_bindings.sql"),
                StandardCharsets.UTF_8
            ).replace(
                "SET @target_schema = 'innolux-iws';",
                "SET @target_schema = '" + DATABASE_NAME + "';"
            );
            for (int execution = 0; execution < 2; execution++) {
                ScriptUtils.executeSqlScript(
                    connection,
                    new ByteArrayResource(bindingUpgrade.getBytes(StandardCharsets.UTF_8))
                );
            }
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("在库管理规则隔离结构初始化失败", error);
        }
    }

    private static void grantMetadataVisibility() {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", DATABASE_PASSWORD
        ); Statement statement = connection.createStatement()) {
            statement.execute(
                "grant select, trigger on *.* to '" + DATABASE_USER + "'@'%'"
            );
        } catch (SQLException error) {
            throw new IllegalStateException("规则表隔离验收账号元数据授权失败", error);
        }
    }

    static Path scriptPath(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth <= 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "找不到项目脚本: " + relativePath + "，当前工作目录 " + System.getProperty("user.dir")
        );
    }
}
