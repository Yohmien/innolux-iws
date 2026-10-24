/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint HTTP fixture 隔离数据库门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.http;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * endpoint HTTP fixture 专用数据库连接与安全门禁。
 *
 * <p>所有 DDL 前核验端口、数据目录与 _test 库名，拒绝业务库和 3306/3307。</p>
 */
final class EndpointHttpFixtureDatabase {

    static final String SERVER_URL = property("iws.endpoint.test.server-url", "jdbc:mysql://127.0.0.1:13307/");
    static final String DATABASE = property("iws.endpoint.test.http-database", "innolux_iws_endpoint_http_test");
    static final String USERNAME = property("iws.endpoint.test.username", "root");
    static final String PASSWORD = property("iws.endpoint.test.password", "");
    static final int MYSQL_PORT = Integer.parseInt(property("iws.endpoint.test.mysql-port", "13307"));
    static final String MYSQL_DATADIR = property("iws.endpoint.test.mysql-datadir", "");

    private static final List<String> TABLES = List.of(
        "iws_endpoint_audit",
        "iws_endpoint_system_address",
        "iws_endpoint_address",
        "iws_endpoint_environment",
        "iws_endpoint_system",
        "iws_endpoint_catalog_control"
    );

    private EndpointHttpFixtureDatabase() {
    }

    static DataSource dataSource() throws SQLException {
        requireOptIn();
        resetSchema();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(databaseUrl());
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    static void resetSchema() throws SQLException {
        requireOptIn();
        verifyFixture();
        ensureDatabase();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            for (String table : TABLES) {
                connection.createStatement().execute("DROP TABLE IF EXISTS " + table);
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("endpoint-test-schema.sql"));
            connection.createStatement().execute("INSERT INTO iws_endpoint_catalog_control"
                + " (singleton_key, revision, update_time) VALUES ('CATALOG', 0, CURRENT_TIMESTAMP)");
        }
    }

    static void dropSchema() throws SQLException {
        requireOptIn();
        verifyFixture();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            for (String table : TABLES) {
                connection.createStatement().execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    static void verifyFixture() throws SQLException {
        requireOptIn();
        if (!DATABASE.endsWith("_test") || "innolux_iws".equalsIgnoreCase(DATABASE)
            || "innolux_iws_dev".equalsIgnoreCase(DATABASE) || MYSQL_PORT == 3306 || MYSQL_PORT == 3307) {
            throw new IllegalStateException("拒绝在业务库、非 _test 库或 3306/3307 端口执行 endpoint HTTP fixture DDL");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("HTTP fixture 库名只允许字母、数字、下划线且必须以 _test 结尾");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + MYSQL_PORT + "/")) {
            throw new IllegalStateException("HTTP fixture JDBC 地址必须指向已声明端口的本机隔离实例");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            int actualPort;
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT @@port")) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("无法读取 MySQL @@port");
                }
                actualPort = resultSet.getInt(1);
            }
            String actualDatadir;
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT @@datadir")) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("无法读取 MySQL @@datadir");
                }
                actualDatadir = resultSet.getString(1);
            }
            if (actualPort != MYSQL_PORT || actualPort == 3306 || actualPort == 3307) {
                throw new IllegalStateException("MySQL @@port 与 HTTP fixture 声明端口不一致或属于业务端口：" + actualPort);
            }
            String normalizedActual = Path.of(actualDatadir).toAbsolutePath().normalize().toString().replace('\\', '/');
            String normalizedExpected = Path.of(MYSQL_DATADIR).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (MYSQL_DATADIR.isBlank() || normalizedExpected.equals("/")
                || !normalizedActual.equalsIgnoreCase(normalizedExpected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务隔离 fixture：" + actualDatadir);
            }
        }
    }

    private static void ensureDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            connection.createStatement().execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static void requireOptIn() {
        if (!Boolean.parseBoolean(property("iws.endpoint.test.enabled", "false"))) {
            throw new IllegalStateException("HTTP fixture 未启用：必须显式设置 -Diws.endpoint.test.enabled=true");
        }
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
