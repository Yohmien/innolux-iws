/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划隔离 MySQL 门禁与结构重建；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 即时计划真实 MySQL 隔离库。
 *
 * <p>执行 DDL 前核验 @@port、@@datadir 和 _test 库名，拒绝 3306/3307 与业务库。</p>
 */
final class ImmediateMysqlFixture {

    static final String SERVER_URL = property("iws.immediate.test.server-url", "jdbc:mysql://127.0.0.1:13307/");

    static final String DATABASE = property("iws.immediate.test.database", "iws_process_immediate_test");

    static final String USERNAME = property("iws.immediate.test.username", "root");

    static final String PASSWORD = property("iws.immediate.test.password", "");

    static final int MYSQL_PORT = Integer.parseInt(property("iws.immediate.test.mysql-port", "13307"));

    static final String MYSQL_DATADIR = property("iws.immediate.test.mysql-datadir", "").trim();

    private ImmediateMysqlFixture() {
    }

    static DataSource dataSource() throws SQLException {
        resetSchema();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(databaseUrl());
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    static void resetSchema() throws SQLException {
        verify();
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            connection.createStatement().execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
            connection.createStatement().execute("CREATE DATABASE `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            executeScript(connection, "iws_plan_event_endpoint_schema.sql");
            executeScript(connection, "iws_plan_event_process_schema.sql");
        }
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    static void verify() throws SQLException {
        if (MYSQL_DATADIR.isBlank()) {
            throw new IllegalStateException("必须显式传入 -Diws.immediate.test.mysql-datadir");
        }
        if (!DATABASE.endsWith("_test") || MYSQL_PORT == 3306 || MYSQL_PORT == 3307) {
            throw new IllegalStateException("拒绝在业务库、非 _test 库或 3306/3307 端口执行即时测试 DDL");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("即时测试库名只允许字母、数字、下划线且以 _test 结尾");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + MYSQL_PORT + "/")) {
            throw new IllegalStateException("即时 JDBC 地址必须指向已声明端口的本机隔离实例");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            int actualPort = queryInt(connection, "SELECT @@port");
            if (actualPort != MYSQL_PORT || actualPort == 3306 || actualPort == 3307) {
                throw new IllegalStateException("MySQL @@port 与声明端口不一致或属于业务端口: " + actualPort);
            }
            String actualDatadir = queryString(connection, "SELECT @@datadir");
            String actual = Path.of(actualDatadir).toAbsolutePath().normalize().toString().replace('\\', '/');
            String expected = Path.of(MYSQL_DATADIR).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务隔离 fixture: " + actualDatadir);
            }
        }
    }

    private static void executeScript(Connection connection, String fileName) throws SQLException {
        Path file = projectRoot().resolve("script/sql").resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("测试结构脚本不存在: " + file);
        }
        try {
            String sql = Files.readString(file, StandardCharsets.UTF_8)
                .replace("SET @upgrade_schema = 'innolux_iws';", "SET @upgrade_schema = '" + DATABASE + "';");
            ScriptUtils.executeSqlScript(connection,
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取测试结构脚本失败: " + file, ex);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && current != null; i++) {
            if (Files.isRegularFile(current.resolve("script/sql/iws_plan_event_process_schema.sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位后端仓库 script/sql 目录");
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
