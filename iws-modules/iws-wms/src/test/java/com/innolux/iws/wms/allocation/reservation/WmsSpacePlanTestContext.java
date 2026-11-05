/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P1-P7 组合验收
 * @Version      : V1.0
 * @Description  : 组装正式空库、生产规则来源和空间计划运行链；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanInstanceActionPolicy;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.PlanScopeResolver;
import com.innolux.iws.process.runtime.ProcessControlIntentService;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorAdmitQueueAction;
import com.innolux.iws.process.runtime.SupervisorCandidateDecisionAction;
import com.innolux.iws.process.runtime.SupervisorCandidateSelector;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.impl.BusinessScheduleServiceImpl;
import com.innolux.iws.process.service.impl.PlanDefinitionServiceImpl;
import com.innolux.iws.process.service.impl.PlanInstanceServiceImpl;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionReceiptStore;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.support.EditCapabilityResolver;
import com.innolux.iws.wms.allocation.process.WmsAllocationSupervisorStateValidator;
import com.innolux.iws.wms.allocation.process.WmsInventoryProcessConfiguration;
import com.innolux.iws.wms.catalog.WmsInventoryPlanMountGate;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogServiceImpl;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.wms.process.WmsProcessAccessPolicy;
import com.innolux.iws.wms.process.WmsProcessMountGate;
import com.innolux.iws.wms.rule.binding.StorageRuleAttributeSourceProvider;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingConfiguration;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.process.WmsRuleActivateAction;
import com.innolux.iws.wms.rule.process.WmsRulePublicationProcess;
import com.innolux.iws.wms.rule.process.WmsRulePublishStateValidator;
import com.innolux.iws.wms.rule.process.WmsRuleValidateAction;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.rule.service.impl.WmsRuleDraftServiceImpl;
import com.innolux.iws.wms.rule.service.impl.WmsRulePublishServiceImpl;
import com.innolux.iws.wms.rule.service.impl.WmsRuleQueryServiceImpl;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.rule.support.WmsRuleCommandExecutor;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.space.change.process.WmsSpaceApplyAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeStateValidator;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeProcess;
import com.innolux.iws.wms.space.change.process.WmsSpaceRetireAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceRetireCheckAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceValidateAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceVerifyAction;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.space.change.service.impl.WmsSpaceChangeServiceImpl;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskBarrierServiceImpl;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ListableBeanFactory;
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

/** 正式空库上的空间计划、生产规则来源和对象授权组合上下文。 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({
    "com.innolux.iws.process.mapper",
    "com.innolux.iws.wms.mapper",
    "com.innolux.iws.wms.catalog.mapper",
    "com.innolux.iws.wms.rule.mapper"
})
@Import({
    com.innolux.iws.wms.support.WmsTestIdentityFactsConfiguration.class,
    SpringUtils.class,
    PlanDefinitionServiceImpl.class,
    PlanInstanceServiceImpl.class,
    BusinessScheduleServiceImpl.class,
    SupervisorRuntimeService.class,
    RuntimeDao.class,
    PlanMaterializer.class,
    EventEligibilityService.class,
    PlanActivationService.class,
    PlanInstanceActionPolicy.class,
    DefinitionValidator.class,
    DefinitionGraphBuilder.class,
    EditCapabilityResolver.class,
    DefinitionReceiptStore.class,
    ProcessAuditService.class,
    CommandReceiptService.class,
    ProcessControlIntentService.class,
    WmsInventoryCatalogServiceImpl.class,
    WmsInventoryPlanMountGate.class,
    WmsCompanyAccessPolicy.class,
    WmsSpaceChangeServiceImpl.class,
    WmsSpaceChangeProcess.class,
    WmsProcessAccessPolicy.class,
    WmsProcessMountGate.class,
    WmsBusinessTaskServiceImpl.class,
    WmsBusinessTaskBarrierServiceImpl.class,
    StorageRuleAttributeSourceProvider.class,
    WmsRuleBindingConfiguration.class,
    WmsRuleBindingService.class,
    WmsRuleAuditService.class,
    WmsRuleCommandExecutor.class,
    WmsRuleScopeGuard.class,
    WmsRuleQueryServiceImpl.class,
    WmsRuleDraftServiceImpl.class,
    WmsRulePublishServiceImpl.class,
    WmsRulePublicationProcess.class
})
class WmsSpacePlanTestContext {

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
        AtomicLong sequence = new AtomicLong(1_100_000L);
        return entity -> sequence.incrementAndGet();
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
    SchedulerPlatformPort schedulerPlatformPort() {
        return mock(SchedulerPlatformPort.class);
    }

    @Bean
    PlanScopeResolver planScopeResolver() {
        PlanScopeResolver resolver = mock(PlanScopeResolver.class);
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("DEFAULT");
        return resolver;
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
    ProcessObjectAuthorizer processObjectAuthorizer(
        RuntimeDao runtimeDao,
        ListableBeanFactory beanFactory
    ) {
        return new ProcessObjectAuthorizer(
            runtimeDao,
            beanFactory.getBeanProvider(
                com.innolux.iws.process.runtime.ProcessAccessPolicy.class)
        );
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
    BranchSelector branchSelector(
        ProcessTokenMapper tokenMapper,
        ProcessInstanceMapper instanceMapper,
        CapabilityRegistry capabilityRegistry
    ) {
        return new BranchSelector(tokenMapper, instanceMapper, capabilityRegistry);
    }

    @Bean
    CallCapacityService callCapacityService() {
        return mock(CallCapacityService.class);
    }

    @Bean
    RecoveryEvidenceService recoveryEvidenceService() {
        return mock(RecoveryEvidenceService.class);
    }

    @Bean
    EventMutationService eventMutationService(
        RuntimeDao runtimeDao,
        ProcessEventMapper eventMapper,
        com.innolux.iws.process.mapper.ProcessAttemptMapper attemptMapper,
        ProcessTokenMapper tokenMapper,
        ProcessInstanceMapper instanceMapper,
        ProcessScheduleControlMapper controlMapper,
        ProcessAuditService auditService,
        JsonMapper jsonMapper,
        PlanActivationService activationService,
        CallCapacityService callCapacityService,
        CapabilityRegistry capabilityRegistry,
        RecoveryEvidenceService recoveryEvidenceService,
        com.innolux.iws.process.mapper.ProcessRequestDetailMapper requestDetailMapper
    ) {
        return new EventMutationService(
            runtimeDao, eventMapper, attemptMapper, tokenMapper, instanceMapper,
            controlMapper, auditService, jsonMapper, activationService,
            callCapacityService, capabilityRegistry, recoveryEvidenceService,
            requestDetailMapper
        );
    }

    @Bean
    EventUpdateService eventUpdateService(
        RuntimeDao runtimeDao,
        EventMutationService eventMutationService,
        PlanActivationService activationService,
        CapabilityRegistry capabilityRegistry,
        com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper receiptMapper,
        com.innolux.iws.process.mapper.ProcessAttemptMapper attemptMapper,
        JsonMapper jsonMapper
    ) {
        return new EventUpdateService(
            runtimeDao, eventMutationService, activationService, capabilityRegistry,
            mock(com.innolux.iws.process.runtime.EndpointTargetResolver.class),
            receiptMapper, attemptMapper, jsonMapper
        );
    }

    @Bean
    CapabilityRegistry capabilityRegistry(
        EventTypeProvider eventTypeProvider,
        WmsSpaceChangeService changeService,
        WmsRulePublishService publishService,
        ProcessInstanceMapper instanceMapper,
        ProcessEventMapper eventMapper,
        ProcessScheduleControlMapper controlMapper,
        PlanDefinitionMapper definitionMapper,
        BusinessScheduleMapper scheduleMapper,
        SupervisorRuntimeService supervisorRuntimeService,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper
    ) {
        WmsFrozenActionInputReader inputReader =
            new WmsFrozenActionInputReader(jdbcTemplate, mock(IamIdentityFacts.class));
        SupervisorCandidateSelector selector = new SupervisorCandidateSelector(
            instanceMapper, controlMapper, definitionMapper, scheduleMapper);
        return new CapabilityRegistry(
            List.of(eventTypeProvider),
            List.of(),
            List.of(
                new WmsSpaceValidateAction(
                    changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader),
                new WmsSpaceApplyAction(
                    changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader),
                new WmsSpaceVerifyAction(
                    changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader),
                new WmsSpaceRetireCheckAction(
                    changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader),
                new WmsSpaceRetireAction(
                    changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader),
                new WmsRuleValidateAction(
                    publishService, instanceMapper, jsonMapper, inputReader),
                new WmsRuleActivateAction(
                    publishService, instanceMapper, jsonMapper, inputReader),
                new SupervisorCandidateDecisionAction(
                    instanceMapper,
                    controlMapper,
                    definitionMapper,
                    scheduleMapper,
                    eventMapper,
                    selector
                ),
                new SupervisorAdmitQueueAction(eventMapper, supervisorRuntimeService)
            ),
            List.of(
                new WmsSpaceChangeStateValidator(jdbcTemplate),
                new WmsAllocationSupervisorStateValidator(jdbcTemplate),
                new WmsRulePublishStateValidator(jdbcTemplate)
            ),
            List.of()
        );
    }
}
