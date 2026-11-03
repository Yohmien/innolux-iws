/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询集成测试
 * @Version      : V1.0
 * @Description  : 组装只读 Mapper、规则绑定、trace 与真实 MySQL 的最小测试上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.mybatis.aspect.DataPermissionPointcutAdvisor;
import com.innolux.iws.common.mybatis.interceptor.PlusDataPermissionInterceptor;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScopeResolver;
import com.innolux.iws.wms.inventory.query.service.impl.WmsInventoryReadServiceImpl;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectInvestigationServiceImpl;
import com.innolux.iws.wms.material.trace.service.impl.WmsMaterialTraceServiceImpl;
import com.innolux.iws.wms.rule.binding.RuleAttributeSourceProvider;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingConfiguration;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.dsl.RuleAttributeBinding;
import com.innolux.iws.wms.rule.dsl.RuleDslEvaluator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P7 只读查询隔离测试上下文。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@MapperScan({
    "com.innolux.iws.wms.inventory.query.mapper",
    "com.innolux.iws.wms.allocation.effect.investigation.mapper",
    "com.innolux.iws.wms.material.trace.mapper",
    "com.innolux.iws.wms.rule.mapper",
    "com.innolux.iws.wms.mapper"
})
@Import({
    SpringUtils.class,
    DataPermissionPointcutAdvisor.class,
    WmsInventoryReadScopeResolver.class,
    WmsAllocationEffectInvestigationServiceImpl.class,
    WmsInventoryReadServiceImpl.class,
    WmsMaterialTraceServiceImpl.class,
    WmsRuleBindingService.class,
    WmsRuleBindingConfiguration.class
})
class WmsInventoryReadTestContext {

    @Bean
    DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(WmsInventoryReadTestDatabase.jdbcUrl());
        dataSource.setUsername(WmsInventoryReadTestDatabase.username());
        dataSource.setPassword(WmsInventoryReadTestDatabase.password());
        return dataSource;
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws IOException {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PlusDataPermissionInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml")
        );
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        AtomicLong sequence = new AtomicLong(100_000L);
        return new IdentifierGenerator() {
            @Override
            public Number nextId(Object entity) {
                return sequence.incrementAndGet();
            }
        };
    }

    @Bean
    TestPermissionGuard permissionGuard() {
        return new TestPermissionGuard();
    }

    @Bean
    RuleAttributeSourceProvider ruleAttributeSourceProvider() {
        return new TestAttributeSourceProvider();
    }

    static final class TestPermissionGuard implements WmsInventoryPermissionGuard {

        private volatile boolean allowed = true;

        private volatile Set<String> allowedPermissions;

        void setAllowed(boolean allowed) {
            this.allowed = allowed;
            this.allowedPermissions = null;
        }

        void setAllowedPermissions(String... permissions) {
            this.allowed = true;
            this.allowedPermissions = Set.of(permissions);
        }

        @Override
        public boolean hasPermission(String permission) {
            return allowed && (allowedPermissions == null || allowedPermissions.contains(permission));
        }

        @Override
        public void requirePermission(String permission) {
            if (!hasPermission(permission)) {
                throw new ServiceException("NO_PERMISSION: " + permission, 403);
            }
        }
    }

    static final class TestAttributeSourceProvider implements RuleAttributeSourceProvider {

        private static final RuleAttributeBinding MATERIAL_CODE =
            new RuleAttributeBinding(
                "materialCode",
                "MATERIAL",
                "STRING",
                "SCALAR",
                List.of("EQ", "NE"),
                null,
                null,
                true,
                "ACTIVE",
                "TEST-1",
                1
            );

        @Override
        public String sourceType() {
            return "MATERIAL";
        }

        @Override
        public String contractVersion() {
            return "TEST-1";
        }

        @Override
        public Map<String, RuleAttributeBinding> bindings() {
            return Map.of("materialCode", MATERIAL_CODE);
        }

        @Override
        public Set<String> sortableAttributes() {
            return Set.of("materialCode");
        }

        @Override
        public Map<String, RuleDslEvaluator.Fact> readFacts(
            long storageId,
            Set<String> attributeKeys
        ) {
            return Map.of();
        }
    }
}
