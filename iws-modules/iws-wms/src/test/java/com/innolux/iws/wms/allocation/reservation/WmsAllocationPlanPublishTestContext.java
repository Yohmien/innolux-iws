/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 组装真实计划定义服务、Maven 能力与隔离 MySQL；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanInstanceActionPolicy;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.PlanScopeResolver;
import com.innolux.iws.process.runtime.ProcessControlIntentService;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorRules;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.impl.PlanDefinitionServiceImpl;
import com.innolux.iws.process.service.impl.PlanInstanceServiceImpl;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionReceiptStore;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.support.EditCapabilityResolver;
import com.innolux.iws.wms.allocation.process.WmsAllocationCheckReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationEvaluateAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationLockStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationReserveAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationSupervisorStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsInventoryProcessConfiguration;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.catalog.WmsInventoryPlanMountGate;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogServiceImpl;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeCheckAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeStateValidator;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeBatchStateValidator;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCloseStateValidator;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergePlanContract;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.wms.rule.process.WmsRuleActivateAction;
import com.innolux.iws.wms.rule.process.WmsRulePublishStateValidator;
import com.innolux.iws.wms.rule.process.WmsRuleValidateAction;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeStateValidator;
import com.innolux.iws.wms.space.change.process.WmsSpacePlanContract;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 分配计划定义发布隔离上下文。 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({
    "com.innolux.iws.process.mapper",
    "com.innolux.iws.wms.catalog.mapper"
})
@Import({
    com.innolux.iws.wms.support.WmsTestIdentityFactsConfiguration.class,
    PlanDefinitionServiceImpl.class,
    PlanInstanceServiceImpl.class,
    SupervisorRuntimeService.class,
    RuntimeDao.class,
    DefinitionValidator.class,
    DefinitionGraphBuilder.class,
    EditCapabilityResolver.class,
    DefinitionReceiptStore.class,
    ProcessAuditService.class,
    CommandReceiptService.class,
    ProcessControlIntentService.class,
    WmsInventoryCatalogServiceImpl.class,
    WmsInventoryPlanMountGate.class
})
class WmsAllocationPlanPublishTestContext {

    @Bean
    DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(WmsAllocationReservationTestDatabase.jdbcUrl());
        dataSource.setUsername(WmsAllocationReservationTestDatabase.username());
        dataSource.setPassword(WmsAllocationReservationTestDatabase.password());
        return dataSource;
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new InjectionMetaObjectHandler());
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml")
        );
        return factory;
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        AtomicLong sequence = new AtomicLong(1_000_000L);
        return new IdentifierGenerator() {
            @Override
            public Number nextId(Object entity) {
                return sequence.incrementAndGet();
            }
        };
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
    ProcessProperties processProperties() {
        ProcessProperties properties = new ProcessProperties();
        properties.setEnabled(true);
        properties.getScheduler().setEnabled(true);
        return properties;
    }

    @Bean
    com.innolux.iws.process.api.SchedulerPlatformPort schedulerPlatformPort() {
        return mock(com.innolux.iws.process.api.SchedulerPlatformPort.class);
    }

    @Bean
    PlanMaterializer planMaterializer() {
        return mock(PlanMaterializer.class);
    }

    @Bean
    EventEligibilityService eventEligibilityService() {
        return mock(EventEligibilityService.class);
    }

    @Bean
    PlanActivationService planActivationService() {
        return mock(PlanActivationService.class);
    }

    @Bean
    PlanScopeResolver planScopeResolver() {
        PlanScopeResolver resolver = mock(PlanScopeResolver.class);
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("DEFAULT");
        return resolver;
    }

    @Bean
    ProcessObjectAuthorizer processObjectAuthorizer() {
        return mock(ProcessObjectAuthorizer.class);
    }

    @Bean
    PlanInstanceActionPolicy planInstanceActionPolicy() {
        return mock(PlanInstanceActionPolicy.class);
    }

    @Bean
    EndpointCatalogReader endpointCatalogReader() {
        EndpointCatalogReader reader = mock(EndpointCatalogReader.class);
        EndpointCatalogSnapshot snapshot = new EndpointCatalogSnapshot(
            "1",
            List.of(new EndpointSystemSnapshot("IWS", "IWS", true, "1")),
            List.of(new EndpointEnvironmentSnapshot("LOCAL", "本地", true, "1")),
            List.of(),
            List.of()
        );
        when(reader.snapshot()).thenReturn(snapshot);
        when(reader.lockSnapshot(anyString())).thenReturn(snapshot);
        when(reader.currentEnvironmentCode()).thenReturn("LOCAL");
        return reader;
    }

    @Bean
    EventTypeProvider wmsEventTypeProvider() {
        return () -> List.of(new EventTypeDescriptor(
            WmsInventoryProcessConfiguration.EVENT_TYPE_KEY,
            WmsInventoryProcessConfiguration.EVENT_TYPE_VERSION,
            "WMS内部业务动作",
            Set.of(com.innolux.iws.process.enums.EventDirection.INTERNAL)
        ));
    }

    @Bean
    CapabilityRegistry capabilityRegistry(
        EventTypeProvider eventTypeProvider,
        JsonMapper jsonMapper,
        JdbcTemplate jdbcTemplate
    ) {
        WmsAllocationReservationService reservationService =
            mock(WmsAllocationReservationService.class);
        WmsAllocationReleaseService releaseService =
            mock(WmsAllocationReleaseService.class);
        WmsLoadChangeService loadChangeService = mock(WmsLoadChangeService.class);
        WmsRulePublishService rulePublishService = mock(WmsRulePublishService.class);
        ProcessInstanceMapper instanceMapper = mock(ProcessInstanceMapper.class);
        LocalAction decisionAction = mock(LocalAction.class);
        when(decisionAction.describe()).thenReturn(new LocalActionDescriptor(
            SupervisorRules.ACTION_DECISION,
            "1",
            "监督候选决策",
            "SAME_TX",
            true
        ));
        LocalAction admitAction = mock(LocalAction.class);
        when(admitAction.describe()).thenReturn(new LocalActionDescriptor(
            SupervisorRules.ACTION_ADMIT,
            "1",
            "监督逐候选准入",
            "SAME_TX",
            true
        ));
        WmsFrozenActionInputReader inputReader =
            new WmsFrozenActionInputReader(jdbcTemplate, mock(IamIdentityFacts.class));
        return new CapabilityRegistry(
            List.of(eventTypeProvider),
            List.of(),
            List.of(
                new WmsAllocationEvaluateAction(
                    reservationService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationReserveAction(
                    reservationService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationVerifyAction(
                    reservationService, jsonMapper, inputReader),
                new WmsAllocationCheckReleaseAction(
                    releaseService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationReleaseAction(
                    releaseService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationVerifyReleaseAction(
                    releaseService, instanceMapper, jsonMapper, inputReader),
                new WmsLoadChangeCheckAction(
                    loadChangeService, instanceMapper, jsonMapper, inputReader),
                new WmsLoadChangeAction(
                    loadChangeService, instanceMapper, jsonMapper, inputReader),
                new WmsRuleValidateAction(
                    rulePublishService, instanceMapper, jsonMapper, inputReader),
                new WmsRuleActivateAction(
                    rulePublishService, instanceMapper, jsonMapper, inputReader),
                action(WmsSpacePlanContract.VALIDATE_EVENT, "空间变更校验"),
                action(WmsSpacePlanContract.APPLY_EVENT, "应用空间配置"),
                action(WmsSpacePlanContract.VERIFY_EVENT, "核验空间配置"),
                action(WmsSpacePlanContract.CHECK_RETIRE_EVENT, "检查空间退役阻碍"),
                action(WmsSpacePlanContract.RETIRE_EVENT, "退役空间对象"),
                action(WmsLogicalMergePlanContract.CAPTURE_BATCH, "冻结合并批次"),
                action(WmsLogicalMergePlanContract.CHECK_BATCH, "检查合并兼容性"),
                action(WmsLogicalMergePlanContract.APPLY_BATCH, "应用逻辑归属批次"),
                action(WmsLogicalMergePlanContract.CLOSE_BATCH, "关闭固定批次"),
                action(WmsLogicalMergePlanContract.CAPTURE_PARENT, "确认合并父范围"),
                action(WmsLogicalMergePlanContract.CLOSE_PARENT, "收敛并退役来源"),
                decisionAction,
                admitAction
            ),
            List.of(
                new WmsAllocationLockStateValidator(jdbcTemplate),
                new WmsAllocationReleaseStateValidator(jdbcTemplate),
                new WmsAllocationSupervisorStateValidator(jdbcTemplate),
                new WmsLoadChangeStateValidator(jdbcTemplate),
                new WmsRulePublishStateValidator(jdbcTemplate),
                new WmsSpaceChangeStateValidator(jdbcTemplate),
                new WmsLogicalMergeBatchStateValidator(jdbcTemplate),
                new WmsLogicalMergeCloseStateValidator(jdbcTemplate)
            ),
            List.of()
        );
    }

    private static LocalAction action(String key, String name) {
        LocalAction action = mock(LocalAction.class);
        when(action.describe()).thenReturn(new LocalActionDescriptor(
            key,
            "1",
            name,
            "SAME_TX",
            true
        ));
        return action;
    }
}
