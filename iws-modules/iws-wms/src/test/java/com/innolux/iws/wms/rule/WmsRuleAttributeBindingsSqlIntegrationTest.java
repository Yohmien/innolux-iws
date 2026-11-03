/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布绑定
 * @Version      : V1.0
 * @Description  : 验证规则绑定列首次升级、幂等重跑、历史行守恒和冲突前置拒绝；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 规则属性绑定列 SQL 隔离验收。 */
@Tag("local")
class WmsRuleAttributeBindingsSqlIntegrationTest {

    private static final String DATABASE_NAME = "iws_rule_binding_sql_test";

    private static final String DATABASE_USER = "iws_rule_binding_sql_test";

    private static final String DATABASE_PASSWORD = "iws_rule_binding_sql_test";

    private static final MySQLContainer MYSQL = startContainer();

    @Test
    void addsNullableColumnPreservesHistoryAndRetryIsIdempotent() throws Exception {
        initializeRuleSchema();
        insertLegacyVersion();
        String before = legacyRowDigest();

        executeScript(DATABASE_NAME, "script/sql/iws_inventory_rule_attribute_bindings.sql");
        assertExactColumn(DATABASE_NAME);
        assertThat(legacyRowDigest()).isEqualTo(before);
        assertThat(bindingValue()).isNull();

        executeScript(DATABASE_NAME, "script/sql/iws_inventory_rule_attribute_bindings.sql");
        executeScript(DATABASE_NAME, "script/sql/iws_inventory_rule_attribute_bindings_verify.sql");
        assertExactColumn(DATABASE_NAME);
        assertThat(legacyRowDigest()).isEqualTo(before);
        assertThat(bindingValue()).isNull();
    }

    @Test
    void incompatibleExistingColumnFailsBeforeDdl() {
        String schema = "iws_rule_binding_conflict_test";
        recreateSchema(schema);
        JdbcTemplate jdbc = jdbc(schema);
        jdbc.execute("""
            create table wms_rule_version (
                rule_version_id bigint primary key,
                rule_definition_id bigint not null,
                version_no int not null,
                condition_json json not null,
                result_json json not null,
                attribute_bindings_json text null,
                canonical_digest char(64) not null
            )
            """);

        assertThatThrownBy(() -> executeScript(
            schema,
            "script/sql/iws_inventory_rule_attribute_bindings.sql"
        )).isInstanceOf(RuntimeException.class)
            .hasMessageContaining("invalid rule binding preflight");
        assertThat(columnType(schema)).isEqualTo("text");
        assertThat(columnNullable(schema)).isEqualTo("YES");
    }

    @Test
    void verifyRejectsMissingColumn() {
        String schema = "iws_rule_binding_missing_test";
        recreateSchema(schema);
        JdbcTemplate jdbc = jdbc(schema);
        jdbc.execute("""
            create table wms_rule_version (
                rule_version_id bigint primary key,
                rule_definition_id bigint not null,
                version_no int not null,
                condition_json json not null,
                result_json json not null,
                canonical_digest char(64) not null
            )
            """);

        assertThatThrownBy(() -> executeScript(
            schema,
            "script/sql/iws_inventory_rule_attribute_bindings_verify.sql"
        )).isInstanceOf(RuntimeException.class)
            .hasMessageContaining("invalid rule binding schema");
        assertThat(columnCount(schema)).isZero();
    }

    private static void initializeRuleSchema() {
        recreateSchema(DATABASE_NAME);
        jdbc(DATABASE_NAME).execute("""
            create table wms_rule_version (
                rule_version_id bigint not null auto_increment,
                rule_definition_id bigint not null,
                version_no int not null,
                condition_json json not null,
                result_json json not null,
                canonical_digest char(64) not null,
                status varchar(16) not null default 'DRAFT',
                published_at datetime(3) null,
                published_by bigint null,
                create_dept bigint null,
                create_by bigint null,
                create_time datetime(3) not null default current_timestamp(3),
                update_by bigint null,
                update_time datetime(3) null default null on update current_timestamp(3),
                primary key (rule_version_id)
            )
            """);
    }

    private static void insertLegacyVersion() {
        JdbcTemplate jdbc = jdbc(DATABASE_NAME);
        jdbc.update("""
            insert into wms_rule_version (
                rule_version_id, rule_definition_id, version_no, condition_json,
                result_json, canonical_digest, status, published_at, published_by
            ) values (1, 1, 1, '{}', '{}', ?, 'ACTIVE', current_timestamp(3), 1)
            """, "a".repeat(64));
    }

    private static String legacyRowDigest() {
        return jdbc(DATABASE_NAME).queryForObject("""
            select sha2(concat_ws('|', rule_version_id, rule_definition_id, version_no,
                                  condition_json, result_json, canonical_digest, status,
                                  date_format(published_at, '%Y-%m-%d %H:%i:%s.%f'), published_by), 256)
              from wms_rule_version
             where rule_version_id = 1
            """, String.class);
    }

    private static Object bindingValue() {
        return jdbc(DATABASE_NAME).queryForObject("""
            select attribute_bindings_json
              from wms_rule_version
             where rule_version_id = 1
            """, Object.class);
    }

    private static void assertExactColumn(String schema) {
        assertThat(columnType(schema)).isEqualTo("json");
        assertThat(columnNullable(schema)).isEqualTo("YES");
        assertThat(columnPosition(schema)).isEqualTo(6);
        assertThat(columnComment(schema)).isEqualTo("服务端属性绑定、用途和限额快照");
    }

    private static String columnType(String schema) {
        return scalar(schema, "select column_type from information_schema.columns "
            + "where table_schema = ? and table_name = 'wms_rule_version' "
            + "and column_name = 'attribute_bindings_json'", String.class);
    }

    private static String columnNullable(String schema) {
        return scalar(schema, "select is_nullable from information_schema.columns "
            + "where table_schema = ? and table_name = 'wms_rule_version' "
            + "and column_name = 'attribute_bindings_json'", String.class);
    }

    private static Integer columnPosition(String schema) {
        return scalar(schema, "select ordinal_position from information_schema.columns "
            + "where table_schema = ? and table_name = 'wms_rule_version' "
            + "and column_name = 'attribute_bindings_json'", Integer.class);
    }

    private static String columnComment(String schema) {
        return scalar(schema, "select column_comment from information_schema.columns "
            + "where table_schema = ? and table_name = 'wms_rule_version' "
            + "and column_name = 'attribute_bindings_json'", String.class);
    }

    private static Integer columnCount(String schema) {
        JdbcTemplate jdbc = jdbc(schema);
        return jdbc.queryForObject("""
            select count(*) from information_schema.columns
             where table_schema = ? and table_name = 'wms_rule_version'
               and column_name = 'attribute_bindings_json'
            """, Integer.class, schema);
    }

    private static <T> T scalar(String schema, String sql, Class<T> type) {
        return jdbc(schema).queryForObject(sql, type, schema);
    }

    private static void executeScript(String schema, String relativePath) {
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(schema), DATABASE_USER, DATABASE_PASSWORD
        )) {
            ScriptUtils.executeSqlScript(connection, resource(schema, relativePath));
        } catch (SQLException | IOException error) {
            throw new IllegalStateException("规则绑定 SQL 执行连接失败", error);
        }
    }

    private static ByteArrayResource resource(String schema, String relativePath)
        throws IOException {
        String text = Files.readString(scriptPath(relativePath), StandardCharsets.UTF_8)
            .replace(
                "SET @target_schema = 'innolux-iws';",
                "SET @target_schema = '" + schema + "';"
            );
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void recreateSchema(String schema) {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", DATABASE_PASSWORD
        ); var statement = connection.createStatement()) {
            statement.execute("drop database if exists `" + schema + "`");
            statement.execute("create database `" + schema + "` "
                + "character set utf8mb4 collate utf8mb4_0900_ai_ci");
            statement.execute("grant all privileges on `" + schema + "`.* to '"
                + DATABASE_USER + "'@'%'");
            statement.execute("grant select, trigger on *.* to '"
                + DATABASE_USER + "'@'%'");
        } catch (SQLException error) {
            throw new IllegalStateException("规则绑定隔离库创建失败", error);
        }
    }

    private static JdbcTemplate jdbc(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(jdbcUrl(schema));
        dataSource.setUsername(DATABASE_USER);
        dataSource.setPassword(DATABASE_PASSWORD);
        return new JdbcTemplate(dataSource);
    }

    private static String jdbcUrl(String schema) {
        String url = MYSQL.getJdbcUrl();
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    private static Path scriptPath(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth <= 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到项目脚本: " + relativePath);
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
                "规则绑定 SQL 验收需要可用的 Docker 与 mysql:8.0.45 镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-rule-binding-sql-test-mysql-stop")
        );
        return container;
    }
}
