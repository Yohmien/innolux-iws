/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 真实 HTTP 隔离测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.doc.config.SpringDocConfig;
import com.innolux.iws.common.log.aspect.LogAspect;
import com.innolux.iws.common.mybatis.aspect.DataPermissionPointcutAdvisor;
import com.innolux.iws.common.mybatis.config.MybatisPlusConfig;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.common.mybatis.interceptor.PlusDataPermissionInterceptor;
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
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * process 真实 HTTP 隔离测试宿主。
 *
 * <p>注册真实 process 控制器、服务、MyBatis 事务和 endpoint 目录读取，SaToken 使用测试内存
 * 会话；不启动 Redis、调度平台、生产用户表或任何外部系统。</p>
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
    LogAspect.class,
    com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class,
    com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration.class,
    com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePoolMetricsAutoConfiguration.class
}, excludeName = {
    "com.baomidou.lock.spring.boot.autoconfigure.LockAutoConfiguration",
    "com.baomidou.lock.spring.boot.autoconfigure.RedissonLockAutoConfiguration"
})
@EnableTransactionManagement(proxyTargetClass = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@MapperScan("com.innolux.iws.process.mapper")
@ComponentScan(basePackages = {"com.innolux.iws.process", "com.innolux.iws.endpoint"},
    excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
        classes = ProcessHttpFixtureApplication.TestSourceFilter.class))
@Import({
    SecurityConfig.class,
    AllUrlHandler.class,
    SaTokenExceptionHandler.class,
    ProcessHttpFixtureCapabilities.class,
    ProcessHttpFixtureAuthController.class,
    ProcessHttpFixtureSessionController.class,
    ProcessHttpFixtureShellController.class,
    ProcessHttpFixtureApplication.FixtureDataSourceConfiguration.class,
    ProcessHttpFixtureApplication.FixtureMybatisConfiguration.class,
    ProcessHttpFixtureApplication.FixtureSecurityConfiguration.class
})
public class ProcessHttpFixtureApplication {

    /**
     * 启动隔离 HTTP fixture 并输出随机端口连接信息。
     *
     * @param args 启动参数
     * @throws InterruptedException 等待进程关闭时中断
     */
    public static void main(String[] args) throws InterruptedException {
        copyTestArguments(args);
        requireOptIn(args);
        SpringApplication application = new SpringApplication(ProcessHttpFixtureApplication.class);
        application.setDefaultProperties(defaultProperties());
        ConfigurableApplicationContext context = application.run(args);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        System.out.println("IWS_PROCESS_HTTP_FIXTURE_URL=" + baseUrl);
        System.out.println("IWS_PROCESS_HTTP_FIXTURE_LOGIN=POST " + baseUrl + "/test-auth/login");
        System.out.println("IWS_PROCESS_HTTP_FIXTURE_BODY={\"username\":\"fixture-admin\",\"password\":\"fixture\"}");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        new CountDownLatch(1).await();
    }

    private static void copyTestArguments(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--iws.process.test.") && arg.contains("=")) {
                int separator = arg.indexOf('=');
                System.setProperty(arg.substring(2, separator), arg.substring(separator + 1));
            }
        }
    }

    /**
     * 在任何数据库初始化前强制 opt-in。
     *
     * @param args 启动参数
     */
    private static void requireOptIn(String[] args) {
        boolean enabled = Boolean.parseBoolean(System.getProperty("iws.process.test.enabled", "false"));
        if (!enabled) {
            for (String arg : args) {
                if ("--iws.process.test.enabled=true".equals(arg)) {
                    enabled = true;
                    System.setProperty("iws.process.test.enabled", "true");
                    break;
                }
            }
        }
        if (!enabled) {
            throw new IllegalStateException("process HTTP fixture 未启用：必须显式设置 -Diws.process.test.enabled=true");
        }
    }

    private static Map<String, Object> defaultProperties() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("server.address", "127.0.0.1");
        defaults.put("server.port", "0");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("spring.jmx.enabled", "false");
        defaults.put("springdoc.api-docs.enabled", "false");
        defaults.put("xss.enabled", "false");
        defaults.put("iws.endpoint.enabled", "true");
        defaults.put("iws.endpoint.current-environment", "HTTP_ENV");
        defaults.put("iws.process.enabled", "true");
        defaults.put("iws.process.scheduler.enabled", "false");
        defaults.put("iws.process.workflow.enabled", "false");
        defaults.put("sa-token.token-name", "Authorization");
        defaults.put("sa-token.is-concurrent", "true");
        defaults.put("sa-token.is-share", "false");
        defaults.put("sa-token.token-prefix", "Bearer");
        defaults.put("sa-token.jwt-secret-key", "process-http-fixture-secret");
        defaults.put("security.excludes", "/auth/**,/test-auth/**,/error");
        defaults.put("mybatis-plus.mapper-locations", "classpath*:mapper/**/*Mapper.xml");
        defaults.put("logging.level.root", "warn");
        return defaults;
    }

    /** 排除 test-classes，避免历史 fixture 配置被完整扫描。 */
    public static class TestSourceFilter implements TypeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            String resource = reader.getResource().toString().replace('\\', '/');
            return resource.contains("/test-classes/")
                || reader.getClassMetadata().getClassName().startsWith("com.innolux.iws.processfixture");
        }
    }

    /** 隔离数据源、JDBC 模板与事务。 */
    @Configuration(proxyBeanMethods = false)
    static class FixtureDataSourceConfiguration {

        /**
         * 创建经过端口、datadir 和 _test 库门禁的数据源。
         *
         * @return 数据源
         * @throws SQLException 初始化失败
         */
        @Bean
        DataSource dataSource() throws SQLException {
            return ProcessHttpFixtureDatabase.dataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    /** 真实 MyBatis-Plus 映射、分页和数据权限切面。 */
    @Configuration(proxyBeanMethods = false)
    static class FixtureMybatisConfiguration {

        @Bean
        JsonMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MetaObjectHandler metaObjectHandler() {
            return new InjectionMetaObjectHandler();
        }

        @Bean
        DataPermissionPointcutAdvisor dataPermissionPointcutAdvisor() {
            return new DataPermissionPointcutAdvisor();
        }

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PlusDataPermissionInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            return interceptor;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler,
                                            MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*Mapper.xml"));
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addInterceptor(mybatisPlusInterceptor);
            factory.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            return factory.getObject();
        }
    }

    /** 测试专用 SaToken 内存会话与真实权限接口。 */
    @Configuration(proxyBeanMethods = false)
    static class FixtureSecurityConfiguration {

        @Bean
        StpLogic stpLogic() {
            return new StpLogicJwtForSimple();
        }

        @Bean
        StpInterface stpInterface() {
            return new SaPermissionImpl();
        }

        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }
}
