package com.innolux.iws.process.supervisor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.ProcessControlIntentService;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import com.innolux.iws.process.runtime.ProcessAccessTarget;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.SupervisorAdmitQueueAction;
import com.innolux.iws.process.runtime.SupervisorAdmissionService;
import com.innolux.iws.process.runtime.SupervisorCandidateDecisionAction;
import com.innolux.iws.process.runtime.SupervisorCandidateSelector;
import com.innolux.iws.process.runtime.SupervisorControlService;
import com.innolux.iws.process.runtime.SupervisorQueryService;
import com.innolux.iws.process.runtime.SupervisorRoundMaterializer;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.runtime.SupervisorTickServiceImpl;
import com.innolux.iws.process.runtime.ValidationResult;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** 监督运行时真实 MySQL 测试宿主。 */
@SpringBootConfiguration
@EnableTransactionManagement
@MapperScan("com.innolux.iws.process.mapper")
@EnableConfigurationProperties(ProcessProperties.class)
@Import({RuntimeDao.class, DefinitionGraphBuilder.class, ProcessAuditService.class,
    CallCapacityService.class, EventEligibilityService.class, BranchSelector.class,
    PlanActivationService.class, EventMutationService.class, EventUpdateService.class,
    com.innolux.iws.process.runtime.RecoveryEvidenceService.class,
    SupervisorRoundMaterializer.class, SupervisorCandidateDecisionAction.class,
    SupervisorCandidateSelector.class,
    SupervisorAdmitQueueAction.class, SupervisorRuntimeService.class,
    SupervisorTickServiceImpl.class, SupervisorAdmissionService.class,
    SupervisorControlService.class, SupervisorQueryService.class,
    ProcessControlIntentService.class, ProcessObjectAuthorizer.class,
    CommandReceiptService.class})
public class SupervisorFixtureApplication {

    /** 测试用对象范围策略；正常用例默认放行。 */
    static final AtomicReference<Predicate<ProcessAccessTarget>> OBJECT_POLICY =
        new AtomicReference<>(target -> true);

    static void allowAllObjectScopes() {
        OBJECT_POLICY.set(target -> true);
    }

    @Bean
    DataSource dataSource() throws SQLException {
        return SupervisorMysqlFixture.dataSource();
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
    EndpointTargetResolver endpointTargetResolver() {
        return Mockito.mock(EndpointTargetResolver.class);
    }

    @Bean
    StateValidator supervisorStateValidator() {
        return new TestStateValidator();
    }

    @Bean
    ProcessAccessPolicy processAccessPolicy() {
        return target -> OBJECT_POLICY.get().test(target);
    }

    @Bean
    CapabilityRegistry capabilityRegistry(SupervisorCandidateDecisionAction decision,
                                          SupervisorAdmitQueueAction admit,
                                          StateValidator validator) {
        return new CapabilityRegistry(List.of(), List.of(), List.of(decision, admit),
            List.of(validator), List.of());
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(interceptor);
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

    /** 监督测试状态校验器。 */
    static final class TestStateValidator implements StateValidator {

        @Override
        public StateValidatorDescriptor describe() {
            return new StateValidatorDescriptor("SUP_VALIDATOR", "1", "监督测试校验");
        }

        @Override
        public ValidationResult validateStructural(StateContext context) {
            return ValidationResult.pass();
        }

        @Override
        public ValidationResult validateBusiness(StateContext context) {
            return ValidationResult.pass();
        }
    }
}
