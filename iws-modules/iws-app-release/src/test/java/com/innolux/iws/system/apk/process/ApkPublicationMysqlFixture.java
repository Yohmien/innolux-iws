/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布集成测试隔离库门禁与定向清理；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 真实 MySQL 隔离门禁与 APK 定向清理。
 *
 * <p>测试默认关闭。只有显式开启且端口、数据目录和库名全部匹配时才允许连接和写入；
 * 清理仅涉及 APK 计划编码、APK 业务编码、对应实例和专用测试 release。</p>
 */
final class ApkPublicationMysqlFixture {

    static final String ENABLED_PROPERTY = "iws.apk.test.enabled";

    static final String DATADIR_PROPERTY = "iws.apk.test.mysql-datadir";

    static final String SERVER_URL = "jdbc:mysql://127.0.0.1:13309/";

    static final String DATABASE = "iws_apk_test";

    static final String USERNAME = "root";

    static final String PASSWORD = "";

    static final int EXPECTED_PORT = 13309;

    static final String DATADIR_MARKER = "apk-mysql-test";

    static final long TEST_RELEASE_ID = 1_761_500_000_000_099_001L;

    static final String TEST_PACKAGE = "com.innolux.iws.apk.integration";

    static final long TEST_VERSION_CODE = 10_001L;

    static final String TEST_INPUT_HASH = "apk-publication-integration-input-hash-v1";

    private static final String[] REQUIRED_TABLES = {
        "sys_apk_release", "iws_business_schedule", "iws_business_schedule_plan_type",
        "iws_process_definition", "iws_process_system_policy", "iws_process_state",
        "iws_process_event_definition", "iws_process_edge", "iws_process_instance",
        "iws_process_system_context", "iws_process_token", "iws_process_event", "iws_process_attempt",
        "iws_process_trigger_receipt", "iws_process_request_detail", "iws_process_action_audit",
        "iws_process_schedule_control"
    };

    private ApkPublicationMysqlFixture() {
    }

    static DataSource dataSource() throws SQLException {
        verify();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(databaseUrl());
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    static void verify() throws SQLException {
        if (!"true".equalsIgnoreCase(property(ENABLED_PROPERTY, ""))) {
            throw new IllegalStateException("必须显式传入 -D" + ENABLED_PROPERTY + "=true");
        }
        String declaredDatadir = property(DATADIR_PROPERTY, "").trim();
        if (declaredDatadir.isBlank()) {
            throw new IllegalStateException("必须显式传入 -D" + DATADIR_PROPERTY);
        }
        if (!DATABASE.endsWith("_test") || !DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalStateException("APK 集成测试只允许专用 _test 库: " + DATABASE);
        }
        if (EXPECTED_PORT == 3306 || EXPECTED_PORT != 13309) {
            throw new IllegalStateException("APK 集成测试端口必须固定为 13309");
        }
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
            String[] identity = serverIdentity(connection);
            int actualPort = Integer.parseInt(identity[0]);
            String actualDatadir = normalized(identity[1]);
            String expectedDatadir = normalized(declaredDatadir);
            if (actualPort != EXPECTED_PORT || actualPort == 3306) {
                throw new IllegalStateException("MySQL @@port 不是 APK 隔离端口: " + actualPort);
            }
            if (!actualDatadir.toLowerCase(Locale.ROOT).contains(DATADIR_MARKER)) {
                throw new IllegalStateException("MySQL @@datadir 不属于 APK 隔离实例: " + identity[1]);
            }
            if (!actualDatadir.equalsIgnoreCase(expectedDatadir)) {
                throw new IllegalStateException("声明的 APK 测试 datadir 与实际实例不一致: " + identity[1]);
            }
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            int ready = queryInt(connection, "SELECT COUNT(*) FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = '" + DATABASE + "' AND TABLE_NAME IN (" + quotedTables() + ")");
            if (ready != REQUIRED_TABLES.length) {
                throw new IllegalStateException("iws_apk_test 缺少 APK/process 必需表，实际匹配数=" + ready);
            }
        }
    }

    /**
     * 只删除 APK 计划、APK 调度和本测试 release 关联的数据。
     *
     * @param jdbc JDBC 模板
     * @throws SQLException 再次门禁失败
     */
    static void clean(JdbcTemplate jdbc) throws SQLException {
        verify();
        jdbc.update("DELETE FROM sys_apk_release WHERE release_id = ? OR package_name = ?",
            TEST_RELEASE_ID, TEST_PACKAGE);

        List<Long> definitionIds = jdbc.queryForList(
            "SELECT id FROM iws_process_definition WHERE plan_code IN (?, ?) OR business_schedule_code = ?",
            Long.class, ApkProcessContract.PLAN_CODE, ApkProcessContract.SUPERVISOR_PLAN_CODE,
            ApkProcessContract.BUSINESS_CODE);
        List<Long> scheduleIds = jdbc.queryForList(
            "SELECT id FROM iws_business_schedule WHERE business_code = ?",
            Long.class, ApkProcessContract.BUSINESS_CODE);
        List<Long> instanceIds = definitionIds.isEmpty()
            ? jdbc.queryForList("SELECT id FROM iws_process_instance WHERE business_schedule_code = ?"
                + " OR plan_type = ?", Long.class, ApkProcessContract.BUSINESS_CODE,
                ApkProcessContract.PLAN_TYPE)
            : jdbc.queryForList("SELECT id FROM iws_process_instance WHERE business_schedule_code = ?"
                + " OR plan_type = ? OR definition_version_id IN (" + placeholders(definitionIds.size()) + ")",
                Long.class, concat(new Object[]{ApkProcessContract.BUSINESS_CODE,
                    ApkProcessContract.PLAN_TYPE}, definitionIds));
        List<Long> eventIds = instanceIds.isEmpty() ? List.of() : jdbc.queryForList(
            "SELECT id FROM iws_process_event WHERE instance_id IN (" + placeholders(instanceIds.size()) + ")",
            Long.class, instanceIds.toArray());
        List<Long> attemptIds = eventIds.isEmpty() ? List.of() : jdbc.queryForList(
            "SELECT id FROM iws_process_attempt WHERE event_id IN (" + placeholders(eventIds.size()) + ")",
            Long.class, eventIds.toArray());
        Set<Long> receiptIds = new LinkedHashSet<>();
        if (!instanceIds.isEmpty()) {
            receiptIds.addAll(jdbc.queryForList("SELECT id FROM iws_process_trigger_receipt"
                + " WHERE instance_id IN (" + placeholders(instanceIds.size()) + ")",
                Long.class, instanceIds.toArray()));
        }
        if (!eventIds.isEmpty()) {
            receiptIds.addAll(jdbc.queryForList("SELECT id FROM iws_process_trigger_receipt"
                + " WHERE event_id IN (" + placeholders(eventIds.size()) + ")",
                Long.class, eventIds.toArray()));
        }

        deleteIds(jdbc, "iws_process_request_detail", "instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_request_detail", "event_id", eventIds);
        deleteIds(jdbc, "iws_process_request_detail", "attempt_id", attemptIds);
        deleteIds(jdbc, "iws_process_request_detail", "receipt_id", receiptIds);
        deleteIds(jdbc, "iws_process_trigger_receipt", "instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_trigger_receipt", "event_id", eventIds);
        deleteIds(jdbc, "iws_process_attempt", "event_id", eventIds);
        deleteIds(jdbc, "iws_process_event", "instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_token", "instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_system_context", "instance_id", instanceIds);

        deleteIds(jdbc, "iws_process_action_audit", "schedule_id", scheduleIds);
        deleteIds(jdbc, "iws_process_action_audit", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_action_audit", "instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_action_audit", "event_id", eventIds);
        deleteIds(jdbc, "iws_process_action_audit", "attempt_id", attemptIds);
        deleteIds(jdbc, "iws_process_action_audit", "supervisor_instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_action_audit", "child_instance_id", instanceIds);
        jdbc.update("DELETE FROM iws_process_action_audit WHERE command_key LIKE 'APK_PUBLISH:%'"
            + " OR command_key LIKE 'APK_IT:%'");

        deleteIds(jdbc, "iws_process_schedule_control", "supervisor_instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_schedule_control", "child_instance_id", instanceIds);
        deleteIds(jdbc, "iws_process_schedule_control", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_instance", "id", instanceIds);

        deleteIds(jdbc, "iws_process_system_policy", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_edge", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_event_definition", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_state", "definition_version_id", definitionIds);
        deleteIds(jdbc, "iws_process_definition", "id", definitionIds);
        deleteIds(jdbc, "iws_business_schedule_plan_type", "schedule_id", scheduleIds);
        deleteIds(jdbc, "iws_business_schedule", "id", scheduleIds);
    }

    private static String[] serverIdentity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@port, @@datadir")) {
            resultSet.next();
            return new String[]{resultSet.getString(1), resultSet.getString(2)};
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void deleteIds(JdbcTemplate jdbc, String table, String column, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(ids.size()) + ")",
            ids.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Object[] concat(Object[] first, Collection<Long> second) {
        List<Object> values = new ArrayList<>(first.length + second.size());
        values.addAll(List.of(first));
        values.addAll(second);
        return values.toArray();
    }

    private static String normalized(String value) {
        return Path.of(value).toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String quotedTables() {
        Set<String> values = new LinkedHashSet<>(List.of(REQUIRED_TABLES));
        return String.join(",", values.stream().map(item -> "'" + item + "'").toList());
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
