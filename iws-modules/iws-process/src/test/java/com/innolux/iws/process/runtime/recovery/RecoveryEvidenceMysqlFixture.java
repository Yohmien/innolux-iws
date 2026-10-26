package com.innolux.iws.process.runtime.recovery;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.service.ProcessAuditService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** QUERY 恢复证据服务的独立 MySQL 隔离库。 */
final class RecoveryEvidenceMysqlFixture {

    static final String SERVER_URL = property("iws.process.test.server-url", "jdbc:mysql://127.0.0.1:13307/");
    static final String DATABASE = property("iws.process.test.recovery-database",
        "iws_process_recovery_evidence_test");
    static final String USERNAME = property("iws.process.test.username", "root");
    static final String PASSWORD = property("iws.process.test.password", "");
    static final int PORT = Integer.parseInt(property("iws.process.test.mysql-port", "13307"));
    static final String DATADIR = property("iws.process.test.mysql-datadir", "").trim();

    private RecoveryEvidenceMysqlFixture() {
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
            ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8), false, false,
                "--", ";", "/*", "*/");
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

/** 只装配 QUERY 恢复证据服务和真实 Mapper 的测试宿主。 */
@SpringBootConfiguration
@EnableTransactionManagement
@MapperScan("com.innolux.iws.process.mapper")
@Import({RecoveryEvidenceService.class, ProcessAuditService.class})
class RecoveryEvidenceFixtureApplication {

    @Bean
    DataSource dataSource() throws SQLException {
        return RecoveryEvidenceMysqlFixture.dataSource();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JsonMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
