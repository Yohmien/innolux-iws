/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 分支与汇合隔离测试专用 MySQL 数据库；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.branch;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 只允许显式 datadir、专用 _test 库且拒绝 3306/3307 的真实 DDL 隔离库。
 */
final class BranchMysqlFixture {

    static final String SERVER_URL = property("iws.branch.test.server-url",
        property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/"));

    static final String DATABASE = property("iws.branch.test.database", "iws_branch_test");

    static final String USERNAME = property("iws.branch.test.username",
        property("iws.process.test.username", "root"));

    static final String PASSWORD = property("iws.branch.test.password",
        property("iws.process.test.password", ""));

    static final int PORT = Integer.parseInt(property("iws.branch.test.mysql-port",
        property("iws.process.test.mysql-port", "13307")));

    static final String DATADIR = property("iws.branch.test.mysql-datadir",
        property("iws.process.test.mysql-datadir", "")).trim();

    private BranchMysqlFixture() {
    }

    static DataSource dataSource() throws SQLException {
        verify();
        ensureDatabase();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            executeUpgradeScript(connection, "iws_plan_event_endpoint_schema.sql");
            executeUpgradeScript(connection, "iws_plan_event_process_schema.sql");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(databaseUrl());
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    private static void verify() throws SQLException {
        if (DATADIR.isBlank()) {
            throw new IllegalStateException("必须显式传入 -Diws.branch.test.mysql-datadir");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test") || PORT == 3306 || PORT == 3307) {
            throw new IllegalStateException("拒绝业务库、非 _test 库或 3306/3307");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + PORT + "/")) {
            throw new IllegalStateException("JDBC 地址必须指向声明端口");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT @@port, @@datadir");
            result.next();
            if (result.getInt(1) != PORT || result.getInt(1) == 3306 || result.getInt(1) == 3307) {
                throw new IllegalStateException("MySQL @@port 不匹配: " + result.getInt(1));
            }
            String actual = Path.of(result.getString(2)).toAbsolutePath().normalize()
                .toString().replace('\\', '/');
            String expected = Path.of(DATADIR).toAbsolutePath().normalize()
                .toString().replace('\\', '/');
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务 fixture: " + actual);
            }
        }
    }

    private static void ensureDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }

    private static void executeUpgradeScript(Connection connection, String fileName) throws SQLException {
        Path script = locateScript(fileName);
        try {
            String sql = Files.readString(script).replace("SET @upgrade_schema = 'innolux_iws';",
                "SET @upgrade_schema = '" + DATABASE + "';");
            connection.createStatement().execute(sql);
        } catch (java.io.IOException ex) {
            throw new SQLException("读取升级脚本失败: " + script, ex);
        }
    }

    private static Path locateScript(String fileName) throws SQLException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("script/sql").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new SQLException("找不到升级脚本: " + fileName);
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
