/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录真实 MySQL 事务、并发与幂等测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.catalog.EndpointCatalogConflictException;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReaderImpl;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.domain.bo.EndpointAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemBo;
import com.innolux.iws.endpoint.domain.vo.EndpointAddressVo;
import com.innolux.iws.endpoint.domain.vo.EndpointChangeResultVo;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import com.innolux.iws.endpoint.util.EndpointValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端点目录真实 MySQL 事务、并发与幂等测试。
 *
 * <p>默认连接本任务隔离实例 127.0.0.1:13307；可通过同名系统属性覆盖，
 * 测试只重建 iws_endpoint_* 表，不触碰业务库其他对象。</p>
 */
@Tag("dev")
@EnabledIf("mysqlFixtureEnabled")
@SpringJUnitConfig(EndpointCatalogMysqlIntegrationTest.TestConfig.class)
class EndpointCatalogMysqlIntegrationTest {

    private static final boolean TEST_ENABLED = Boolean.parseBoolean(property("iws.endpoint.test.enabled", "false"));
    private static final int MYSQL_PORT = Integer.parseInt(property("iws.endpoint.test.mysql-port", "13307"));
    private static final String SERVER_URL = property("iws.endpoint.test.server-url",
        "jdbc:mysql://127.0.0.1:13307/");
    private static final String DATABASE = property("iws.endpoint.test.database", "innolux_iws_endpoint_test");
    private static final String USERNAME = property("iws.endpoint.test.username", "root");
    private static final String PASSWORD = property("iws.endpoint.test.password", "");
    private static final String MYSQL_DATADIR = property("iws.endpoint.test.mysql-datadir", "");

    @Autowired
    private EndpointCatalogService catalogService;

    @Autowired
    private EndpointCatalogReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetSchema() throws SQLException {
        verifyFixture();
        for (String table : List.of("iws_endpoint_audit", "iws_endpoint_system_address", "iws_endpoint_address",
            "iws_endpoint_environment", "iws_endpoint_system", "iws_endpoint_catalog_control")) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("endpoint-test-schema.sql"));
        }
        jdbcTemplate.update("INSERT INTO iws_endpoint_catalog_control"
            + " (singleton_key, revision, update_time) VALUES ('CATALOG', 0, CURRENT_TIMESTAMP)");
    }

    @Test
    void emptyCatalogReaderDoesNotRequireCurrentEnvironment() {
        EndpointCatalogSnapshot snapshot = reader.snapshot();

        assertThat(reader.currentEnvironmentCode()).isNull();
        assertThat(snapshot.catalogRevision()).isEqualTo("0");
        assertThat(snapshot.systems()).isEmpty();
        assertThat(snapshot.environments()).isEmpty();
        assertThat(snapshot.addresses()).isEmpty();
        assertThat(snapshot.systemAddresses()).isEmpty();
    }

    @Test
    void addressUsesSafeDtoAndBuildsIpv6BaseUri() {
        createEnvironment("INT");
        EndpointChangeResultVo created = createAddress("INT", "SAP_GW", "https", "2001:db8::1", 8443,
            "/gateway/", "vault://iws/sap-gateway");

        EndpointAddressVo detail = catalogService.queryAddress(created.objectKey());
        assertThat(detail.getBaseUri()).isEqualTo("https://[2001:db8::1]:8443/gateway");
        assertThat(detail.getCredentialRef()).isEqualTo("vault://iws/sap-gateway");
        assertThat(detail.getHasCredential()).isTrue();
        assertThat(detail.getRevision()).isEqualTo("1");
        assertThat(detail.getSystemCodes()).isEmpty();
    }

    @Test
    void addressCreateWithSystemsRequiresPermissionAndAdvancesSystemRevision() {
        createEnvironment("INT2");
        EndpointChangeResultVo system = createSystem("SAP");
        EndpointAddressBo bo = new EndpointAddressBo();
        bo.setEnvironmentCode("INT2");
        bo.setEndpointRef("SAP_GW");
        bo.setProtocol("https");
        bo.setHost("gateway.example.com");
        bo.setPort(443);
        bo.setBasePath("/sap");
        bo.setEnabled(true);
        bo.setSystemCodes(List.of("SAP"));
        bo.setCommandKey(UUID.randomUUID().toString());
        bo.setExpectedVersion(EndpointValidator.ABSENT_VERSION);
        bo.setReason("创建共享网关并关联系统");

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            EndpointChangeResultVo address = catalogService.createAddress(bo);
            stp.verify(() -> StpUtil.checkPermission("endpoint:system:edit"));
            assertThat(catalogService.queryAddress(address.objectKey()).getSystemCodes()).containsExactly("SAP");
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT revision FROM iws_endpoint_system WHERE system_code = 'SAP'", Long.class))
            .isEqualTo(Long.parseLong(system.revision()) + 1);
        assertThat(activeRelationCount("SAP")).isEqualTo(1L);
    }

    @Test
    void sameCommandReplaysResultAndDifferentPayloadConflicts() {
        createEnvironment("DEV");
        EndpointChangeResultVo created = createAddress("DEV", "MID_GW", "http", "127.0.0.1", 8080,
            "/mid", null);
        String commandKey = UUID.randomUUID().toString();

        EndpointChangeResultVo first = catalogService.updateAddress(created.objectKey(), addressUpdate(
            commandKey, "1", "http", "127.0.0.2", 8081, "/mid-v2", null, "调整隔离测试地址"));
        EndpointChangeResultVo replay = catalogService.updateAddress(created.objectKey(), addressUpdate(
            commandKey, "1", "http", "127.0.0.2", 8081, "/mid-v2", null, "调整隔离测试地址"));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_endpoint_audit WHERE command_key = ?", Long.class, commandKey)).isEqualTo(1L);
        assertThatThrownBy(() -> catalogService.updateAddress(created.objectKey(), addressUpdate(
            commandKey, "2", "http", "127.0.0.3", 8081, "/mid-v2", null, "异参请求")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("命令键已被不同请求使用");
    }

    @Test
    void systemAddressReplaceIsAtomicAndRetainsInactiveRows() {
        String environmentCode = createEnvironment("QA");
        EndpointChangeResultVo system = createSystem("MID_WMS");
        EndpointChangeResultVo firstAddress = createAddress(environmentCode, "MID_A", "http", "10.0.0.10",
            8080, "/a", null);
        EndpointChangeResultVo secondAddress = createAddress(environmentCode, "MID_B", "http", "10.0.0.11",
            8080, "/b", null);

        EndpointChangeResultVo linked = catalogService.replaceSystemAddresses("MID_WMS",
            systemAddresses(UUID.randomUUID().toString(), system.revision(),
                List.of(firstAddress.objectKey(), secondAddress.objectKey())));
        long auditBefore = auditCount();
        String catalogBefore = catalogRevision();

        assertThatThrownBy(() -> catalogService.replaceSystemAddresses("MID_WMS",
            systemAddresses(UUID.randomUUID().toString(), linked.revision(), List.of(firstAddress.objectKey(), "999999"))))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("端点地址不存在");
        assertThat(auditCount()).isEqualTo(auditBefore);
        assertThat(catalogRevision()).isEqualTo(catalogBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT revision FROM iws_endpoint_system WHERE system_code = ?", Long.class, "MID_WMS"))
            .isEqualTo(Long.parseLong(linked.revision()));
        assertThat(activeRelationCount("MID_WMS")).isEqualTo(2L);

        EndpointChangeResultVo reduced = catalogService.replaceSystemAddresses("MID_WMS",
            systemAddresses(UUID.randomUUID().toString(), linked.revision(), List.of(firstAddress.objectKey())));
        assertThat(reduced.revision()).isEqualTo(Long.toString(Long.parseLong(linked.revision()) + 1));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_endpoint_system_address WHERE system_code = ?", Long.class, "MID_WMS"))
            .isEqualTo(2L);
        assertThat(activeRelationCount("MID_WMS")).isEqualTo(1L);
    }

    @Test
    void concurrentStaleAddressUpdatesAllowExactlyOneCommit() throws Exception {
        createEnvironment("PROD");
        EndpointChangeResultVo created = createAddress("PROD", "TARGET", "http", "10.1.1.1", 80, "", null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String host = "10.1.1." + (index + 2);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return new Attempt(catalogService.updateAddress(created.objectKey(), addressUpdate(
                            UUID.randomUUID().toString(), "1", "http", host, 80, "", null, "并发更新")), null);
                    } catch (Throwable throwable) {
                        return new Attempt(null, throwable);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(attempts).filteredOn(attempt -> attempt.result() != null).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> attempt.error() instanceof ServiceException).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT revision FROM iws_endpoint_address WHERE address_id = ?", Long.class,
                Long.parseLong(created.objectKey()))).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sharedCatalogGateBlocksWriterUntilConsumerTransactionEnds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> consumer = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                EndpointCatalogSnapshot snapshot = reader.lockSnapshot("0");
                assertThat(snapshot.catalogRevision()).isEqualTo("0");
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待释放共享锁超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> writer = executor.submit(() -> createEnvironment("LOCKED"));

            Thread.sleep(300L);
            assertThat(writer).isNotDone();
            release.countDown();
            consumer.get(10, TimeUnit.SECONDS);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo("LOCKED");
            assertThat(catalogRevision()).isEqualTo("1");

            assertThatThrownBy(() -> transactionTemplate.execute(status -> reader.lockSnapshot("0")))
                .isInstanceOf(EndpointCatalogConflictException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validationRejectsUnsafeAddressAndStopsBeforeAudit() {
        assertThatThrownBy(() -> EndpointValidator.host("https://example.com/path"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> EndpointValidator.basePath("/api?token=x"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> EndpointValidator.port(70000))
            .isInstanceOf(ServiceException.class);

        EndpointEnvironmentBo invalid = new EndpointEnvironmentBo();
        invalid.setEnvironmentCode("bad code");
        invalid.setDisplayName("非法环境");
        invalid.setEnabled(true);
        invalid.setCommandKey(UUID.randomUUID().toString());
        invalid.setExpectedVersion(EndpointValidator.ABSENT_VERSION);
        invalid.setReason("校验测试");
        assertThatThrownBy(() -> catalogService.createEnvironment(invalid))
            .isInstanceOf(ServiceException.class);
        assertThat(auditCount()).isZero();
        assertThat(catalogRevision()).isEqualTo("0");
    }

    @Test
    void pagingReturnsRepositoryPageShape() {
        createSystem("SYS_A");
        createSystem("SYS_B");

        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(1);
        PageResult<com.innolux.iws.endpoint.domain.vo.EndpointSystemVo> page = catalogService.pageSystems(
            new com.innolux.iws.endpoint.domain.bo.EndpointSystemPageBo(), pageQuery);

        assertThat(page.getTotal()).isEqualTo(2L);
        assertThat(page.getRows()).hasSize(1);
    }

    private String createEnvironment(String code) {
        EndpointEnvironmentBo bo = new EndpointEnvironmentBo();
        bo.setEnvironmentCode(code);
        bo.setDisplayName(code + "环境");
        bo.setDescription("隔离事务测试");
        bo.setEnabled(true);
        bo.setCommandKey(UUID.randomUUID().toString());
        bo.setExpectedVersion(EndpointValidator.ABSENT_VERSION);
        bo.setReason("准备隔离测试环境");
        catalogService.createEnvironment(bo);
        return code;
    }

    private EndpointChangeResultVo createSystem(String code) {
        EndpointSystemBo bo = new EndpointSystemBo();
        bo.setSystemCode(code);
        bo.setDisplayName(code + "系统");
        bo.setDescription("隔离事务测试");
        bo.setEnabled(true);
        bo.setCommandKey(UUID.randomUUID().toString());
        bo.setExpectedVersion(EndpointValidator.ABSENT_VERSION);
        bo.setReason("准备隔离测试系统");
        return catalogService.createSystem(bo);
    }

    private EndpointChangeResultVo createAddress(String environmentCode, String endpointRef, String protocol,
                                                  String host, Integer port, String basePath,
                                                  String credentialRef) {
        EndpointAddressBo bo = new EndpointAddressBo();
        bo.setEnvironmentCode(environmentCode);
        bo.setEndpointRef(endpointRef);
        bo.setProtocol(protocol);
        bo.setHost(host);
        bo.setPort(port);
        bo.setBasePath(basePath);
        bo.setCredentialRef(credentialRef);
        bo.setEnabled(true);
        bo.setCommandKey(UUID.randomUUID().toString());
        bo.setExpectedVersion(EndpointValidator.ABSENT_VERSION);
        bo.setReason("准备隔离测试地址");
        return catalogService.createAddress(bo);
    }

    private EndpointAddressBo addressUpdate(String commandKey, String expectedVersion, String protocol,
                                             String host, Integer port, String basePath, String credentialRef,
                                             String reason) {
        EndpointAddressBo bo = new EndpointAddressBo();
        bo.setCommandKey(commandKey);
        bo.setExpectedVersion(expectedVersion);
        bo.setProtocol(protocol);
        bo.setHost(host);
        bo.setPort(port);
        bo.setBasePath(basePath);
        bo.setCredentialRef(credentialRef);
        bo.setEnabled(true);
        bo.setReason(reason);
        return bo;
    }

    private EndpointSystemAddressBo systemAddresses(String commandKey, String expectedVersion,
                                                     List<String> addressIds) {
        EndpointSystemAddressBo bo = new EndpointSystemAddressBo();
        bo.setCommandKey(commandKey);
        bo.setExpectedVersion(expectedVersion);
        bo.setAddressIds(addressIds);
        bo.setReason("更新系统地址集合");
        return bo;
    }

    private long auditCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_endpoint_audit", Long.class);
    }

    private String catalogRevision() {
        return Long.toString(jdbcTemplate.queryForObject(
            "SELECT revision FROM iws_endpoint_catalog_control WHERE singleton_key = 'CATALOG'", Long.class));
    }

    private long activeRelationCount(String systemCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_endpoint_system_address WHERE system_code = ? AND enabled = 1",
            Long.class, systemCode);
    }

    private record Attempt(EndpointChangeResultVo result, Throwable error) {
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    /**
     * 仅显式指定测试开关时运行写库测试。
     *
     * @return true 表示允许执行隔离 MySQL fixture
     */
    static boolean mysqlFixtureEnabled() {
        return TEST_ENABLED;
    }

    private static String databaseUrl() {
        return SERVER_URL + DATABASE
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    /**
     * 聚焦测试容器，仅注入端点目录所需事务与 JDBC 能力。
     */
    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        DataSource dataSource() throws SQLException {
            ensureDatabase();
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(databaseUrl());
            dataSource.setUsername(USERNAME);
            dataSource.setPassword(PASSWORD);
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        EndpointProperties endpointProperties() {
            EndpointProperties properties = new EndpointProperties();
            properties.setEnabled(true);
            return properties;
        }

        @Bean
        EndpointCatalogRepository endpointCatalogRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
            return new EndpointCatalogRepository(jdbcTemplate, dataSource);
        }

        @Bean
        EndpointCatalogService endpointCatalogService(EndpointCatalogRepository repository,
                                                      EndpointProperties properties) {
            return new EndpointCatalogService(repository, properties);
        }

        @Bean
        EndpointCatalogReader endpointCatalogReader(EndpointCatalogRepository repository,
                                                    EndpointProperties properties) {
            return new EndpointCatalogReaderImpl(repository, properties);
        }

        private static void ensureDatabase() throws SQLException {
            verifyFixture();
            try (Connection connection = DriverManager.getConnection(SERVER_URL, USERNAME, PASSWORD)) {
                connection.createStatement().execute("CREATE DATABASE IF NOT EXISTS `" + DATABASE
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        }
    }

    private static void verifyFixture() throws SQLException {
        if (!DATABASE.endsWith("_test") || "innolux_iws".equalsIgnoreCase(DATABASE)
            || "innolux_iws_dev".equalsIgnoreCase(DATABASE) || MYSQL_PORT == 3306 || MYSQL_PORT == 3307) {
            throw new IllegalStateException("拒绝在业务库、非 _test 库或 3306/3307 端口执行端点测试 DDL");
        }
        if (!DATABASE.matches("[A-Za-z0-9_]+_test")) {
            throw new IllegalArgumentException("测试库名只允许字母、数字、下划线且必须以 _test 结尾");
        }
        if (!SERVER_URL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + MYSQL_PORT + "/")) {
            throw new IllegalStateException("测试 JDBC 地址必须指向已声明端口的本机隔离实例");
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
                throw new IllegalStateException("MySQL @@port 与测试声明端口不一致或属于业务端口：" + actualPort);
            }
            String normalizedActual = normalizePath(actualDatadir);
            String normalizedExpected = normalizePath(MYSQL_DATADIR);
            if (MYSQL_DATADIR.isBlank() || normalizedExpected.equals("/")
                || !normalizedActual.equalsIgnoreCase(normalizedExpected)) {
                throw new IllegalStateException("MySQL @@datadir 不属于本任务隔离 fixture：" + actualDatadir);
            }
        }
    }

    private static String normalizePath(String value) {
        return Path.of(value).toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
