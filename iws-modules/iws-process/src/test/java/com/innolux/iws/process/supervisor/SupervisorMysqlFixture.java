package com.innolux.iws.process.supervisor;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 监督隔离 MySQL；只允许 13307 专用 _test 库。 */
final class SupervisorMysqlFixture {

    static final String SERVER_URL = property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/");
    static final String DATABASE = property("iws.process.test.supervisor-database", "iws_process_supervisor_test");
    static final String USERNAME = property("iws.process.test.username", "root");
    static final String PASSWORD = property("iws.process.test.password", "");
    static final int PORT = Integer.parseInt(property("iws.process.test.mysql-port", "13307"));
    static final String DATADIR = property("iws.process.test.mysql-datadir", "").trim();

    private SupervisorMysqlFixture() {
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
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("process-runtime-test-schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("process-supervisor-test-schema.sql"));
        }
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    private static void verify() throws SQLException {
        if (DATADIR.isBlank()) {
            throw new IllegalStateException("必须显式传入 -Diws.process.test.mysql-datadir");
        }
        if (!DATABASE.endsWith("_test") || "innolux_iws".equalsIgnoreCase(DATABASE)
            || "innolux_iws_dev".equalsIgnoreCase(DATABASE) || PORT == 3306 || PORT == 3307) {
            throw new IllegalStateException("拒绝业务库、非 _test 库或 3306/3307");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("监督测试库名只允许字母、数字、下划线且以 _test 结尾");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + PORT + "/")) {
            throw new IllegalStateException("JDBC 地址必须指向声明端口");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT @@port, @@datadir");
            rs.next();
            int actualPort = rs.getInt(1);
            if (actualPort != PORT || actualPort == 3306 || actualPort == 3307) {
                throw new IllegalStateException("MySQL @@port 不匹配: " + actualPort);
            }
            String actual = Path.of(rs.getString(2)).toAbsolutePath().normalize().toString().replace('\\', '/');
            String expected = Path.of(DATADIR).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (expected.isBlank() || expected.equals("/") || !actual.equalsIgnoreCase(expected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务隔离 fixture: " + actual);
            }
        }
    }

    private static void ensureDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
