package com.innolux.iws.process.runtime;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 运行层真实 MySQL 隔离库。 */
final class RuntimeMysqlFixture {

    static final String SERVER_URL = property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/");
    static final String DATABASE = property("iws.process.test.runtime-database", "iws_process_runtime_v2_test");
    static final String USERNAME = property("iws.process.test.username", "root");
    static final String PASSWORD = property("iws.process.test.password", "");
    static final int PORT = Integer.parseInt(property("iws.process.test.mysql-port", "13307"));
    static final String DATADIR = property("iws.process.test.mysql-datadir", "").trim();

    private RuntimeMysqlFixture() {
    }

    static DataSource dataSource() throws SQLException {
        verify();
        ensureDatabase();
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            executeUpgradeScript(connection, "iws_plan_event_endpoint_schema.sql");
            executeUpgradeScript(connection, "iws_plan_event_process_schema.sql");
            connection.createStatement().execute("CREATE TABLE IF NOT EXISTS runtime_test_effect ("
                + "id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, event_id bigint NOT NULL, "
                + "effect_key varchar(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
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
            throw new IllegalStateException("必须显式传入 -Diws.process.test.mysql-datadir");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test") || PORT == 3306 || PORT == 3307) {
            throw new IllegalStateException("拒绝业务库、非 _test 库或 3306/3307");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + PORT + "/")) {
            throw new IllegalStateException("JDBC 地址必须指向声明端口");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT @@port, @@datadir");
            rs.next();
            if (rs.getInt(1) != PORT || rs.getInt(1) == 3306 || rs.getInt(1) == 3307) {
                throw new IllegalStateException("MySQL @@port 不匹配: " + rs.getInt(1));
            }
            String actual = Path.of(rs.getString(2)).toAbsolutePath().normalize().toString().replace('\\', '/');
            String expected = Path.of(DATADIR).toAbsolutePath().normalize().toString().replace('\\', '/');
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
            String raw = Files.readString(script);
            String anchor = "SET @upgrade_schema = 'innolux_iws';";
            int anchorIndex = raw.indexOf(anchor);
            if (anchorIndex < 0) {
                throw new SQLException("升级脚本缺少唯一 schema 参数锚点: " + fileName);
            }
            String sql = raw.substring(0, anchorIndex) + "SET @upgrade_schema = '" + DATABASE + "';"
                + raw.substring(anchorIndex + anchor.length());
            StringBuilder preflight = new StringBuilder();
            List<String> statements = splitStatements(sql);
            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i).trim();
                if (statement.isBlank()) {
                    continue;
                }
                try (var cursor = connection.createStatement()) {
                    boolean result = cursor.execute(statement);
                    consume(cursor, result, preflight);
                } catch (SQLException ex) {
                    throw new SQLException("升级脚本失败 [" + fileName + " 第" + (i + 1) + "条]: "
                        + ex.getMessage() + "\n已读取的前置结果:\n" + preflight, ex);
                }
            }
        } catch (java.io.IOException ex) {
            throw new SQLException("读取升级脚本失败: " + script, ex);
        }
    }

    private static void consume(java.sql.Statement statement, boolean hasResult, StringBuilder log)
        throws SQLException {
        boolean current = hasResult;
        while (true) {
            if (current) {
                try (var rs = statement.getResultSet()) {
                    if (rs != null) {
                        int columns = rs.getMetaData().getColumnCount();
                        int rows = 0;
                        while (rs.next() && rows < 20) {
                            StringBuilder line = new StringBuilder();
                            for (int i = 1; i <= columns; i++) {
                                if (i > 1) {
                                    line.append(" | ");
                                }
                                line.append(rs.getMetaData().getColumnLabel(i)).append('=')
                                    .append(rs.getString(i));
                            }
                            log.append(line).append('\n');
                            rows++;
                        }
                    }
                }
            }
            int updateCount = statement.getUpdateCount();
            if (!current && updateCount == -1) {
                return;
            }
            current = statement.getMoreResults();
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                current.append(ch);
                if (ch == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (!single && !dbl && !backtick && ch == '-' && next == '-') {
                lineComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }
            if (!single && !dbl && !backtick && ch == '/' && next == '*') {
                blockComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }
            if (ch == '\'' && !dbl && !backtick) {
                single = !single;
            } else if (ch == '"' && !single && !backtick) {
                dbl = !dbl;
            } else if (ch == '`' && !single && !dbl) {
                backtick = !backtick;
            }
            if (ch == ';' && !single && !dbl && !backtick) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().isBlank()) {
            result.add(current.toString());
        }
        return result;
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
