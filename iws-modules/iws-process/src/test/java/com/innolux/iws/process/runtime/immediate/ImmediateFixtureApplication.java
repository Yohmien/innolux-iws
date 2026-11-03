/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划真实隔离 MySQL 测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.DefaultPlanScopeResolver;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.ImmediateAdmissionService;
import com.innolux.iws.process.runtime.ImmediateCommandReceiptStore;
import com.innolux.iws.process.runtime.ImmediateExecutionDriver;
import com.innolux.iws.process.runtime.ImmediateExecutionGuard;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.runtime.ImmediateFailureContract;
import com.innolux.iws.process.runtime.ImmediateFailureContractProvider;
import com.innolux.iws.process.runtime.ImmediateFailureContractRegistry;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceRegistry;
import com.innolux.iws.process.runtime.ImmediateOutcomeService;
import com.innolux.iws.process.runtime.ImmediatePlanPreflight;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.ProcessControlIntentService;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * 只装配即时运行链与真实隔离 MySQL 的测试宿主。
 */
@SpringBootConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan("com.innolux.iws.process.mapper")
@Import({DefinitionGraphBuilder.class, RuntimeDao.class, PlanMaterializer.class, EventEligibilityService.class,
    BranchSelector.class, PlanActivationService.class, CallCapacityService.class, EventMutationService.class,
    com.innolux.iws.process.runtime.RecoveryEvidenceService.class,
    EventUpdateService.class, EndpointTargetResolver.class, SupervisorRuntimeService.class,
    ProcessControlIntentService.class,
    ProcessAuditService.class, DefaultPlanScopeResolver.class, ImmediateFailureContractRegistry.class,
    ImmediateInputEvidenceRegistry.class,
    ImmediatePlanPreflight.class, ImmediateCommandReceiptStore.class, ImmediateAdmissionService.class,
    ImmediateExecutionGuard.class, ImmediateExecutionDriver.class, ImmediateOutcomeService.class,
    ProcessObjectAuthorizer.class, ImmediateExecutionService.class})
public class ImmediateFixtureApplication {

    /**
     * 隔离数据源。
     *
     * @return 数据源
     * @throws SQLException 初始化失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        return ImmediateMysqlFixture.dataSource();
    }

    /**
     * JDBC 模板。
     *
     * @param dataSource 数据源
     * @return 模板
     */
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * MyBatis-Plus 会话工厂。
     *
     * @param dataSource 数据源
     * @param metaObjectHandler 填充处理器
     * @return 会话工厂
     * @throws Exception 构建失败
     */
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

    /**
     * 创建/更新时间填充。
     *
     * @return 填充处理器
     */
    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    /**
     * 事务管理器。
     *
     * @param dataSource 数据源
     * @return 管理器
     */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * JSON 映射器。
     *
     * @return 映射器
     */
    @Bean
    JsonMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * process 配置。
     *
     * @return 配置
     */
    @Bean
    ProcessProperties processProperties() {
        return new ProcessProperties();
    }

    /**
     * 即时测试动作。
     *
     * @return 动作
     */
    @Bean
    ImmediateActionFixture immediateActionFixture() {
        return new ImmediateActionFixture();
    }

    /**
     * 即时入站证据测试提供者。
     *
     * @return 提供者
     */
    @Bean
    ImmediateInputEvidenceFixture immediateInputEvidenceFixture() {
        return new ImmediateInputEvidenceFixture();
    }

    /**
     * 能力注册表。
     *
     * @param action 测试动作
     * @return 注册表
     */
    @Bean
    CapabilityRegistry capabilityRegistry(ImmediateActionFixture action) {
        return new CapabilityRegistry(List.of(), List.of(action.operation()), List.of(action),
            List.of(action.validator()), List.of());
    }

    /**
     * 即时安全失败合同。
     *
     * @return 提供者
     */
    @Bean
    ImmediateFailureContractProvider immediateFailureContractProvider() {
        return () -> List.of(new ImmediateFailureContract(
                "LOCAL:" + ImmediateActionFixture.LOCAL_KEY + ":1", true, "按已存检查点安全终结"),
            new ImmediateFailureContract("CALL:" + ImmediateActionFixture.INBOUND_CONTRACT + ":1", true,
                "按已存 canonical 入站证据安全终结"));
    }

    /**
     * fixture 显式注册的业务对象范围。
     *
     * @return 策略
     */
    @Bean
    ProcessAccessPolicy immediateProcessAccessPolicy() {
        return target -> target.scheduleId() != null && target.scheduleId() == 8001L;
    }

    /**
     * 即时图不需要真实 endpoint 地址。
     *
     * @return 只读目录
     */
    @Bean
    EndpointCatalogReader endpointCatalogReader() {
        return new EndpointCatalogReader() {
            @Override
            public EndpointCatalogSnapshot snapshot() {
                return new EndpointCatalogSnapshot("1", List.of(), List.of(), List.of(), List.of());
            }

            @Override
            public EndpointCatalogSnapshot lockSnapshot(String expectedCatalogRevision) {
                return snapshot();
            }

            @Override
            public String currentEnvironmentCode() {
                return "TEST";
            }
        };
    }
}
