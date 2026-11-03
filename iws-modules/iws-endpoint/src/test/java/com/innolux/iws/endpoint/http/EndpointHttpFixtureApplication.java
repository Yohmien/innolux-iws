/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint 真实 HTTP 隔离测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.http;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import com.innolux.iws.common.doc.config.SpringDocConfig;
import com.innolux.iws.common.log.aspect.LogAspect;
import com.innolux.iws.common.mybatis.config.MybatisPlusConfig;
import com.innolux.iws.common.redis.config.CacheConfig;
import com.innolux.iws.common.redis.config.IdempotentConfig;
import com.innolux.iws.common.redis.config.Lock4jConfig;
import com.innolux.iws.common.redis.config.RateLimiterConfig;
import com.innolux.iws.common.redis.config.RedisConfig;
import com.innolux.iws.common.satoken.config.SaTokenConfig;
import com.innolux.iws.common.satoken.core.service.SaPermissionImpl;
import com.innolux.iws.common.satoken.handler.SaTokenExceptionHandler;
import com.innolux.iws.common.security.config.SecurityConfig;
import com.innolux.iws.common.security.handler.AllUrlHandler;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReaderImpl;
import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.controller.EndpointAddressController;
import com.innolux.iws.endpoint.controller.EndpointAuditController;
import com.innolux.iws.endpoint.controller.EndpointEnvironmentController;
import com.innolux.iws.endpoint.controller.EndpointSystemController;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

/**
 * endpoint 真实 HTTP 隔离测试宿主。
 *
 * <p>只注册 endpoint MVC、真实 SaToken 登录/权限链和测试专用会话应答。
 * 不启动 Redis、MyBatis、调度或生产业务模块。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    SaTokenConfig.class,
    RedisConfig.class,
    CacheConfig.class,
    IdempotentConfig.class,
    RateLimiterConfig.class,
    Lock4jConfig.class,
    RedissonAutoConfigurationV2.class,
    RedissonAutoConfigurationV4.class,
    MybatisPlusConfig.class,
    SpringDocConfig.class,
    LogAspect.class
}, excludeName = {
    "com.baomidou.lock.spring.boot.autoconfigure.LockAutoConfiguration",
    "com.baomidou.lock.spring.boot.autoconfigure.RedissonLockAutoConfiguration"
})
@EnableConfigurationProperties(EndpointProperties.class)
@EnableTransactionManagement
@Import({
    SecurityConfig.class,
    AllUrlHandler.class,
    SaTokenExceptionHandler.class,
    EndpointCatalogRepository.class,
    EndpointCatalogService.class,
    EndpointCatalogReaderImpl.class,
    EndpointSystemController.class,
    EndpointEnvironmentController.class,
    EndpointAddressController.class,
    EndpointAuditController.class,
    EndpointHttpFixtureAuthController.class,
    EndpointHttpFixtureSessionController.class,
    EndpointHttpFixtureShellController.class,
    EndpointHttpFixtureApplication.FixtureSecurityConfiguration.class
})
public class EndpointHttpFixtureApplication {

    /**
     * 启动隔离 HTTP fixture 并输出随机端口连接信息。
     *
     * @param args 启动参数
     * @throws InterruptedException 等待进程关闭时中断
     */
    public static void main(String[] args) throws InterruptedException {
        requireOptIn(args);
        ConfigurableApplicationContext context = SpringApplication.run(EndpointHttpFixtureApplication.class, args);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        System.out.println("IWS_ENDPOINT_HTTP_FIXTURE_URL=" + baseUrl);
        System.out.println("IWS_ENDPOINT_HTTP_FIXTURE_LOGIN=POST " + baseUrl + "/test-auth/login");
        System.out.println("IWS_ENDPOINT_HTTP_FIXTURE_BODY={\"username\":\"fixture-admin\",\"password\":\"fixture\"}");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        new CountDownLatch(1).await();
    }

    /**
     * 在任何数据库初始化前强制 opt-in。
     *
     * @param args 启动参数
     */
    private static void requireOptIn(String[] args) {
        boolean enabled = Boolean.parseBoolean(System.getProperty("iws.endpoint.test.enabled", "false"));
        if (!enabled) {
            for (String arg : args) {
                if ("--iws.endpoint.test.enabled=true".equals(arg)) {
                    enabled = true;
                    System.setProperty("iws.endpoint.test.enabled", "true");
                    break;
                }
            }
        }
        if (!enabled) {
            throw new IllegalStateException("HTTP fixture 未启用：必须显式设置 -Diws.endpoint.test.enabled=true");
        }
    }

    /**
     * 隔离事务与数据源。
     */
    @Configuration(proxyBeanMethods = false)
    static class FixtureDataSourceConfiguration {

        /**
         * 创建经过端口与 datadir 核验的隔离数据源。
         *
         * @return 数据源
         * @throws SQLException 数据库初始化失败
         */
        @Bean
        DataSource dataSource() throws SQLException {
            return EndpointHttpFixtureDatabase.dataSource();
        }

        /**
         * 创建 JDBC 模板。
         *
         * @param dataSource 数据源
         * @return JDBC 模板
         */
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        /**
         * 创建同库事务管理器。
         *
         * @param dataSource 数据源
         * @return 事务管理器
         */
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    /**
     * 测试专用 SaToken 内存会话与真实权限接口。
     */
    @Configuration(proxyBeanMethods = false)
    static class FixtureSecurityConfiguration {

        /**
         * 与生产一致的 JWT 登录逻辑。
         *
         * @return JWT StpLogic
         */
        @Bean
        StpLogic stpLogic() {
            return new StpLogicJwtForSimple();
        }

        /**
         * 从真实登录会话读取权限。
         *
         * @return 权限接口
         */
        @Bean
        StpInterface stpInterface() {
            return new SaPermissionImpl();
        }

        /**
         * 测试进程内 SaToken 存储，生产实现仍使用 Redis 多级缓存。
         *
         * @return 内存存储
         */
        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }
}
