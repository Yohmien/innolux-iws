/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 组装规则服务、审计与真实 MySQL 的最小 Spring 上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.system.service.JdbcIamIdentityFactReader;
import com.innolux.iws.wms.rule.binding.RuleAttributeSourceProvider;
import com.innolux.iws.wms.rule.binding.RuleAttributeSourceRegistry;
import com.innolux.iws.wms.rule.binding.RulePurposeSelection;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.dsl.RuleAttributeBinding;
import com.innolux.iws.wms.rule.dsl.RuleDslEvaluator;
import com.innolux.iws.wms.rule.dsl.RulePurposeContract;
import com.innolux.iws.wms.rule.dsl.RulePurposeRegistry;
import com.innolux.iws.wms.rule.dsl.RuleResult;
import com.innolux.iws.wms.rule.service.impl.WmsRuleDraftServiceImpl;
import com.innolux.iws.wms.rule.service.impl.WmsRulePublishServiceImpl;
import com.innolux.iws.wms.rule.service.impl.WmsRuleQueryServiceImpl;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.rule.support.WmsRuleCommandExecutor;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

/**
 * 在库管理规则隔离测试上下文。
 *
 * @author YONGJIE.PENG
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({"com.innolux.iws.wms.rule.mapper", "com.innolux.iws.wms.mapper"})
@Import({
    SpringUtils.class,
    WmsCompanyAccessPolicy.class,
    WmsRuleAuditService.class,
    WmsRuleCommandExecutor.class,
    WmsRuleScopeGuard.class,
    WmsRuleBindingService.class,
    WmsRuleQueryServiceImpl.class,
    WmsRuleDraftServiceImpl.class,
    WmsRulePublishServiceImpl.class
})
class WmsRuleTestContext {

    @Bean("testPhysicalDataSource")
    DataSource testPhysicalDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(WmsRuleTestDatabase.jdbcUrl());
        dataSource.setUsername(WmsRuleTestDatabase.username());
        dataSource.setPassword(WmsRuleTestDatabase.password());
        return dataSource;
    }

    @Bean
    @Primary
    DynamicRoutingDataSource dataSource(DataSource testPhysicalDataSource) {
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(List.of());
        routing.addDataSource("master", testPhysicalDataSource);
        routing.addDataSource("identity", testPhysicalDataSource);
        routing.setPrimary("master");
        routing.setStrict(true);
        routing.afterPropertiesSet();
        return routing;
    }

    @Bean
    IamIdentityFacts iamIdentityFacts(DynamicRoutingDataSource dataSource) {
        return new JdbcIamIdentityFactReader(dataSource);
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws IOException {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new InjectionMetaObjectHandler());

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(interceptor);
        factory.setMapperLocations(
            new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
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
    RulePurposeSelection rulePurposeSelection() {
        return new RulePurposeSelection(Map.of(
            "INBOUND_ACCEPT", Map.of(
                "COMPANY", "COMPANY-1",
                "WAREHOUSE", "WAREHOUSE-1",
                "MATERIAL_ZONE", "MATERIAL_ZONE-1",
                "DEVICE_ZONE", "DEVICE_ZONE-1"
            ),
            "OUTBOUND_ALLOCATE", Map.of("STORAGE", "STORAGE-1")
        ));
    }

    @Bean
    RulePurposeRegistry rulePurposeRegistry() {
        return new RulePurposeRegistry(List.of(
            contract("INBOUND_ACCEPT", "COMPANY-1", "COMPANY"),
            contract("INBOUND_ACCEPT", "WAREHOUSE-1", "WAREHOUSE"),
            contract("INBOUND_ACCEPT", "MATERIAL_ZONE-1", "MATERIAL_ZONE"),
            contract("INBOUND_ACCEPT", "DEVICE_ZONE-1", "DEVICE_ZONE"),
            contract("OUTBOUND_ALLOCATE", "STORAGE-1", "STORAGE")
        ));
    }

    @Bean
    RuleAttributeSourceProvider ruleAttributeSourceProvider() {
        return new TestRuleAttributeSourceProvider();
    }

    @Bean
    RuleAttributeSourceRegistry ruleAttributeSourceRegistry(
        RuleAttributeSourceProvider provider
    ) {
        return new RuleAttributeSourceRegistry(List.of(provider));
    }

    private static RulePurposeContract contract(String purpose, String version, String scopeType) {
        return new RulePurposeContract(
            purpose,
            version,
            scopeType,
            RuleResult.Type.FILTER,
            Set.of(),
            Set.of()
        );
    }

    private static final class TestRuleAttributeSourceProvider
        implements RuleAttributeSourceProvider {

        private static final Map<String, RuleAttributeBinding> BINDINGS = Map.of(
            "materialCode", binding("materialCode"),
            "batchNo", binding("batchNo"),
            "supplierCode", binding("supplierCode")
        );

        @Override
        public String sourceType() {
            return "MATERIAL";
        }

        @Override
        public String contractVersion() {
            return "ATTR-1";
        }

        @Override
        public Map<String, RuleAttributeBinding> bindings() {
            return BINDINGS;
        }

        @Override
        public Set<String> sortableAttributes() {
            return Set.of();
        }

        @Override
        public Map<String, RuleDslEvaluator.Fact> readFacts(
            long storageId,
            Set<String> attributeKeys
        ) {
            return Map.of();
        }

        private static RuleAttributeBinding binding(String key) {
            return new RuleAttributeBinding(
                key,
                "MATERIAL",
                "STRING",
                "SCALAR",
                List.of("EQ", "NE"),
                null,
                null,
                true,
                "ACTIVE",
                "ATTR-1",
                1
            );
        }
    }
}
