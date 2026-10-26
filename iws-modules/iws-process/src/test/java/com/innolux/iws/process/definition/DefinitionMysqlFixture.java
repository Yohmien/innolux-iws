/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划定义隔离数据库门禁与结构重建；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 计划定义真实 MySQL 隔离库。
 *
 * <p>执行 DDL 前核验 @@port、@@datadir 和 _test 库名，拒绝 3306/3307 与业务库。</p>
 */
final class DefinitionMysqlFixture {

    static final String SERVER_URL = property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/");

    static final String DATABASE = property("iws.process.test.database", "iws_process_definition_test");

    static final String USERNAME = property("iws.process.test.username", "root");

    static final String PASSWORD = property("iws.process.test.password", "");

    static final int MYSQL_PORT = Integer.parseInt(property("iws.process.test.mysql-port", "13307"));

    /**
     * 隔离实例数据目录必须由执行者显式传入，仓库不保存本机绝对路径。
     */
    static final String MYSQL_DATADIR = property("iws.process.test.mysql-datadir", "").trim();

    private DefinitionMysqlFixture() {
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
        ensureDatabase();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("process-definition-test-schema.sql"));
        }
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    static void verify() throws SQLException {
        if (MYSQL_DATADIR.isBlank()) {
            throw new IllegalStateException("必须显式传入 -Diws.process.test.mysql-datadir，禁止默认本机绝对路径");
        }
        if (!DATABASE.endsWith("_test") || "innolux_iws".equalsIgnoreCase(DATABASE)
            || "innolux_iws_dev".equalsIgnoreCase(DATABASE) || MYSQL_PORT == 3306 || MYSQL_PORT == 3307) {
            throw new IllegalStateException("拒绝在业务库、非 _test 库或 3306/3307 端口执行计划定义 DDL");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("计划定义测试库名只允许字母、数字、下划线且以 _test 结尾");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + MYSQL_PORT + "/")) {
            throw new IllegalStateException("计划定义 JDBC 地址必须指向已声明端口的本机隔离实例");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            int actualPort;
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT @@port")) {
                resultSet.next();
                actualPort = resultSet.getInt(1);
            }
            String actualDatadir;
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT @@datadir")) {
                resultSet.next();
                actualDatadir = resultSet.getString(1);
            }
            if (actualPort != MYSQL_PORT || actualPort == 3306 || actualPort == 3307) {
                throw new IllegalStateException("MySQL @@port 与声明端口不一致或属于业务端口: " + actualPort);
            }
            String actual = Path.of(actualDatadir).toAbsolutePath().normalize().toString().replace('\\', '/');
            String expected = Path.of(MYSQL_DATADIR).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (expected.isBlank() || expected.equals("/") || !actual.equalsIgnoreCase(expected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务隔离 fixture: " + actualDatadir);
            }
        }
    }

    private static void ensureDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            connection.createStatement().execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
