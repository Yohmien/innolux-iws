/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环集成测试
 * @Version      : V1.0
 * @Description  : 为 WMS 公司事务测试启动一次性 MySQL 容器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.company;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ClassPathResource;
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
 * 隔离 MySQL 测试库。
 *
 * <p>容器只在测试 JVM 内启动，数据库名、账号和端口均由容器分配，不连接本机现有 MySQL 服务。</p>
 *
 * @author YONGJIE.PENG
 */
final class WmsCompanyTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_test";

    private static final String DATABASE_USER = "iws_wms_test";

    private static final String DATABASE_PASSWORD = "iws_wms_test";

    private static final MySQLContainer MYSQL = startContainer();

    static {
        initializeSchema();
    }

    private WmsCompanyTestDatabase() {
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
                "隔离集成测试需要可用的 Docker 与 mysql:8.0.45 镜像，当前环境无法启动测试库",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "wms-test-mysql-stop"));
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
                new ClassPathResource("mysql/wms-company-schema.sql")
            );
            String registrationSql = Files.readString(
                scriptPath("script/sql/iws_inventory_direct_write_fence_register.sql"),
                StandardCharsets.UTF_8
            ).replace(
                "SET @target_schema = 'innolux-iws';",
                "SET @target_schema = '" + DATABASE_NAME + "';"
            );
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ByteArrayResource(
                registrationSql.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("WMS 直接写入隔离结构初始化失败", error);
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
            throw new IllegalStateException("WMS 隔离验收账号元数据授权失败", error);
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
