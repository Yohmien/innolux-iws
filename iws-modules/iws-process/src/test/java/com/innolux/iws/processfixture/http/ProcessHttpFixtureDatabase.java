/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 隔离数据库门禁与升级脚本装载；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * process HTTP fixture 专用数据库。
 *
 * <p>只接受显式启用的本机 13307 实例、显式 datadir 和 {@code _test} 库。结构直接装载当前
 * endpoint/process 升级脚本，不在测试类中复制业务表定义。</p>
 */
final class ProcessHttpFixtureDatabase {

    static final String SERVER_URL = property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/");
    static final String DATABASE = property("iws.process.test.http-database", "iws_process_http_test");
    static final String USERNAME = property("iws.process.test.username", "root");
    static final String PASSWORD = property("iws.process.test.password", "");
    static final int MYSQL_PORT = Integer.parseInt(property("iws.process.test.mysql-port", "13307"));
    static final String MYSQL_DATADIR = property("iws.process.test.mysql-datadir", "").trim();

    private static final String ENDPOINT_SCHEMA = "iws_plan_event_endpoint_schema.sql";
    private static final String PROCESS_SCHEMA = "iws_plan_event_process_schema.sql";

    private static final List<String> TABLES = List.of(
        "iws_process_action_audit",
        "iws_process_request_detail",
        "iws_process_trigger_receipt",
        "iws_process_attempt",
        "iws_process_event",
        "iws_process_token",
        "iws_process_system_context",
        "iws_process_schedule_control",
        "iws_process_instance",
        "iws_process_edge",
        "iws_process_event_definition",
        "iws_process_state",
        "iws_process_system_policy",
        "iws_process_definition",
        "iws_business_schedule_plan_type",
        "iws_business_schedule",
        "iws_endpoint_audit",
        "iws_endpoint_system_address",
        "iws_endpoint_address",
        "iws_endpoint_environment",
        "iws_endpoint_system",
        "iws_endpoint_catalog_control"
    );

    private ProcessHttpFixtureDatabase() {
    }

    /**
     * 创建经过门禁的隔离数据源，并重建当前升级脚本结构。
     *
     * @return 数据源
     * @throws SQLException 初始化失败
     */
    static DataSource dataSource() throws SQLException {
        resetSchema();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(databaseUrl());
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    /**
     * 清空隔离库并顺序装载 endpoint、process 当前升级脚本。
     *
     * @throws SQLException 初始化失败
     */
    static synchronized void resetSchema() throws SQLException {
        requireOptIn();
        verifyFixture();
        ensureDatabase();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            dropTables(connection);
            executeUpgradeScript(connection, ENDPOINT_SCHEMA);
            executeUpgradeScript(connection, PROCESS_SCHEMA);
            seedCatalog(connection);
        }
    }

    /**
     * 删除 fixture 表，供测试结束清理。
     *
     * @throws SQLException 清理失败
     */
    static void dropSchema() throws SQLException {
        requireOptIn();
        verifyFixture();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            dropTables(connection);
        }
    }

    /**
     * 隔离库连接地址。
     *
     * @return JDBC 地址
     */
    static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    private static void requireOptIn() {
        if (!Boolean.parseBoolean(property("iws.process.test.enabled", "false"))) {
            throw new IllegalStateException("process HTTP fixture 未启用：必须显式设置 -Diws.process.test.enabled=true");
        }
    }

    private static void verifyFixture() throws SQLException {
        requireOptIn();
        if (MYSQL_DATADIR.isBlank()) {
            throw new IllegalStateException("必须显式传入 -Diws.process.test.mysql-datadir，禁止默认本机绝对路径");
        }
        if (!DATABASE.endsWith("_test") || "innolux_iws".equalsIgnoreCase(DATABASE)
            || "innolux_iws_dev".equalsIgnoreCase(DATABASE) || MYSQL_PORT == 3306 || MYSQL_PORT == 3307) {
            throw new IllegalStateException("拒绝在业务库、非 _test 库或 3306/3307 端口执行 process HTTP fixture DDL");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("HTTP fixture 库名只允许字母、数字、下划线且必须以 _test 结尾");
        }
        String local = "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/";
        String localhost = "jdbc:mysql://localhost:" + MYSQL_PORT + "/";
        if (!SERVER_URL.equals(local) && !SERVER_URL.equals(localhost)) {
            throw new IllegalStateException("HTTP fixture JDBC 地址必须指向已声明端口的本机隔离实例");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            int actualPort;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT @@port")) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("无法读取 MySQL @@port");
                }
                actualPort = resultSet.getInt(1);
            }
            String actualDatadir;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT @@datadir")) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("无法读取 MySQL @@datadir");
                }
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
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }

    private static void dropTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    private static void executeUpgradeScript(Connection connection, String fileName) throws SQLException {
        Path script = scriptDirectory().resolve(fileName);
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取当前升级脚本: " + script, exception);
        }
        String target = "SET @upgrade_schema = 'innolux_iws';";
        if (!sql.contains(target)) {
            throw new IllegalStateException("升级脚本缺少目标库参数: " + script);
        }
        sql = sql.replaceFirst(java.util.regex.Pattern.quote(target),
            "SET @upgrade_schema = '" + DATABASE + "';");
        try (Statement statement = connection.createStatement()) {
            boolean result = statement.execute(sql);
            while (true) {
                if (result) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        while (resultSet != null && resultSet.next()) {
                            // 升级脚本的核验 SELECT 只用于执行器门禁，fixture 丢弃结果集。
                        }
                    }
                } else if (statement.getUpdateCount() == -1) {
                    break;
                }
                result = statement.getMoreResults();
            }
        }
    }

    private static Path scriptDirectory() {
        String configured = property("iws.process.test.sql-dir", "").trim();
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path.resolve(PROCESS_SCHEMA))) {
                return path;
            }
            throw new IllegalStateException("iws.process.test.sql-dir 不包含当前升级脚本: " + path);
        }
        Path current = Path.of(property("user.dir", ".")).toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("script").resolve("sql");
            if (Files.isRegularFile(candidate.resolve(PROCESS_SCHEMA))
                && Files.isRegularFile(candidate.resolve(ENDPOINT_SCHEMA))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到 script/sql 当前升级脚本，请从仓库内启动或设置 "
            + "-Diws.process.test.sql-dir");
    }

    private static void seedCatalog(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO iws_endpoint_environment"
                + " (environment_code, display_name, description, enabled, revision, create_by, create_time,"
                + " update_by, update_time) VALUES"
                + " ('HTTP_ENV', 'HTTP 隔离环境', 'process HTTP fixture', 1, 1, 9001, CURRENT_TIMESTAMP,"
                + " 9001, CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO iws_endpoint_system"
                + " (system_code, display_name, description, enabled, revision, create_by, create_time,"
                + " update_by, update_time) VALUES"
                + " ('IWS', 'IWS 隔离本系统', 'process HTTP fixture', 1, 1, 9001, CURRENT_TIMESTAMP,"
                + " 9001, CURRENT_TIMESTAMP)");
            statement.executeUpdate("UPDATE iws_endpoint_catalog_control"
                + " SET revision = 2, update_time = CURRENT_TIMESTAMP WHERE singleton_key = 'CATALOG'");
        }
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
