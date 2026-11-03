/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务集成测试
 * @Version      : V1.0
 * @Description  : 组装真实分配锁位服务、process 运行时和隔离 MySQL；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.wms.allocation.process.WmsInventoryProcessConfiguration;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.allocation.reservation.service.impl.WmsAllocationReservationServiceImpl;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReaderImpl;
import com.innolux.iws.wms.allocation.release.service.impl.WmsAllocationReleaseServiceImpl;
import com.innolux.iws.wms.allocation.effect.service.impl.WmsAllocationEffectServiceImpl;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectInvestigationServiceImpl;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScope;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScopeResolver;
import com.innolux.iws.wms.allocation.space.service.impl.WmsChannelMembershipServiceImpl;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilityEvaluator;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshotService;
import com.innolux.iws.wms.load.change.service.impl.WmsLoadChangeServiceImpl;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeOrchestrationService;
import com.innolux.iws.wms.material.change.service.WmsMaterialChangeService;
import com.innolux.iws.wms.material.change.service.impl.WmsMaterialChangeServiceImpl;
import com.innolux.iws.wms.material.process.FixtureMaterialChangeAction;
import com.innolux.iws.wms.material.trace.service.impl.WmsMaterialTraceServiceImpl;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskServiceImpl;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskBarrierServiceImpl;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;

/** 分配锁位 SAME_TX 隔离测试上下文。 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({
    "com.innolux.iws.process.mapper",
    "com.innolux.iws.wms.allocation.effect.investigation.mapper",
    "com.innolux.iws.wms.material.change.mapper",
    "com.innolux.iws.wms.material.trace.mapper"
})
@Import({
    WmsAllocationReservationServiceImpl.class,
    WmsAllocationReleaseServiceImpl.class,
    WmsAllocationEffectServiceImpl.class,
    WmsAllocationEffectInvestigationServiceImpl.class,
    WmsMaterialTraceServiceImpl.class,
    WmsMaterialChangeServiceImpl.class,
    WmsChannelMembershipServiceImpl.class,
    WmsChannelReachabilityEvaluator.class,
    WmsChannelReachabilitySnapshotService.class,
    WmsLoadChangeServiceImpl.class,
    WmsBusinessTaskServiceImpl.class,
    WmsBusinessTaskBarrierServiceImpl.class,
    WmsInventoryProcessConfiguration.class,
    ProcessAuditService.class
})
class WmsAllocationReservationTestContext {

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
        AtomicLong sequence = new AtomicLong(900_000L);
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
    WmsRulePublishService wmsRulePublishService() {
        return mock(WmsRulePublishService.class);
    }

    @Bean
    WmsSpaceChangeService wmsSpaceChangeService() {
        return mock(WmsSpaceChangeService.class);
    }

    @Bean
    WmsLogicalMergeOrchestrationService wmsLogicalMergeOrchestrationService() {
        return mock(WmsLogicalMergeOrchestrationService.class);
    }

    @Bean
    WmsInventoryReadScopeResolver readScopeResolver() {
        return new WmsInventoryReadScopeResolver() {
            @Override
            public WmsInventoryReadScope current() {
                return new WmsInventoryReadScope(true, false, 1L, null);
            }
        };
    }

    @Bean
    RuntimeDao runtimeDao() {
        return mock(RuntimeDao.class);
    }

    @Bean
    PlanActivationService planActivationService() {
        return mock(PlanActivationService.class);
    }

    @Bean
    CallCapacityService callCapacityService() {
        return mock(CallCapacityService.class);
    }

    @Bean
    CapabilityRegistry capabilityRegistry() {
        return mock(CapabilityRegistry.class);
    }

    @Bean
    RecoveryEvidenceService recoveryEvidenceService() {
        return mock(RecoveryEvidenceService.class);
    }

    @Bean
    EventMutationService eventMutationService(
        RuntimeDao runtimeDao,
        ProcessEventMapper eventMapper,
        ProcessAttemptMapper attemptMapper,
        ProcessTokenMapper tokenMapper,
        ProcessInstanceMapper instanceMapper,
        ProcessScheduleControlMapper controlMapper,
        ProcessAuditService auditService,
        JsonMapper jsonMapper,
        PlanActivationService activationService,
        CallCapacityService callCapacityService,
        CapabilityRegistry capabilityRegistry,
        RecoveryEvidenceService recoveryEvidenceService,
        ProcessRequestDetailMapper requestDetailMapper
    ) {
        return new EventMutationService(
            runtimeDao,
            eventMapper,
            attemptMapper,
            tokenMapper,
            instanceMapper,
            controlMapper,
            auditService,
            jsonMapper,
            activationService,
            callCapacityService,
            capabilityRegistry,
            recoveryEvidenceService,
            requestDetailMapper
        );
    }

    @Bean
    FixtureAllocationReserveAction fixtureAllocationReserveAction(
        WmsAllocationReservationService reservationService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper
    ) {
        return new FixtureAllocationReserveAction(
            reservationService, instanceMapper, jsonMapper);
    }

    @Bean
    FixtureMaterialChangeAction fixtureMaterialChangeAction(
        WmsMaterialChangeService materialChangeService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper
    ) {
        return new FixtureMaterialChangeAction(
            materialChangeService, instanceMapper, jsonMapper);
    }

    @Bean
    RetrySpaceReader retrySpaceReader(JdbcTemplate jdbcTemplate) {
        return new RetrySpaceReader(new WmsAllocationSpaceReaderImpl(jdbcTemplate));
    }

}
