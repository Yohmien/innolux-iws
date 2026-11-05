/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 定义初始化与即时发布真实 MySQL 测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.endpoint.catalog.EndpointCatalogConflictException;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.DefaultPlanScopeResolver;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.ImmediateAdmissionService;
import com.innolux.iws.process.runtime.ImmediateCommandReceiptStore;
import com.innolux.iws.process.runtime.ImmediateExecutionDriver;
import com.innolux.iws.process.runtime.ImmediateExecutionGuard;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.runtime.ImmediateFailureContractRegistry;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceRegistry;
import com.innolux.iws.process.runtime.ImmediateOutcomeService;
import com.innolux.iws.process.runtime.ImmediatePlanPreflight;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.ProcessControlIntentService;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.SupervisorCandidateDecisionAction;
import com.innolux.iws.process.runtime.SupervisorCandidateSelector;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.impl.BusinessScheduleServiceImpl;
import com.innolux.iws.process.service.impl.PlanDefinitionServiceImpl;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionReceiptStore;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.support.EditCapabilityResolver;
import com.innolux.iws.system.apk.service.impl.ApkReleasePublicationPortImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * 只装配 APK 发布所需的真实平台服务和隔离 MySQL。
 *
 * <p>目录只模拟外部接入配置；定义、计划实例、事件、回执和 APK 发布事实均落真实 SQL。</p>
 */
@SpringBootConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({"com.innolux.iws.process.mapper", "com.innolux.iws.system.apk.mapper"})
@Import({DefinitionValidator.class, DefinitionGraphBuilder.class, EditCapabilityResolver.class,
    DefinitionReceiptStore.class, ProcessAuditService.class, CommandReceiptService.class,
    PlanDefinitionServiceImpl.class, BusinessScheduleServiceImpl.class, RuntimeDao.class,
    PlanMaterializer.class, EventEligibilityService.class, BranchSelector.class, PlanActivationService.class,
    CallCapacityService.class, EventMutationService.class, RecoveryEvidenceService.class,
    EventUpdateService.class, EndpointTargetResolver.class, SupervisorRuntimeService.class,
    SupervisorCandidateSelector.class, SupervisorCandidateDecisionAction.class,
    ProcessControlIntentService.class, DefaultPlanScopeResolver.class, ImmediateFailureContractRegistry.class,
    ImmediateInputEvidenceRegistry.class, ImmediatePlanPreflight.class, ImmediateCommandReceiptStore.class,
    ImmediateAdmissionService.class, ImmediateExecutionGuard.class, ImmediateExecutionDriver.class,
    ImmediateOutcomeService.class, ProcessObjectAuthorizer.class, ImmediateExecutionService.class,
    ApkReleasePublishEventTypeProvider.class, ApkReleasePublicationAction.class,
    ApkReleaseStateValidator.class, ApkSupervisorStateValidator.class, ApkReleaseProcessAccessPolicy.class,
    ApkReleaseImmediateFailureContractProvider.class, SaTokenApkPlanAdminGuard.class,
    ApkReleasePublicationPortImpl.class, ApkPublicationProcess.class})
public class ApkPublicationFixtureApplication {

    private static final String CATALOG_REVISION = "1";

    /**
     * 创建通过隔离门禁的数据源。
     *
     * @return 数据源
     * @throws SQLException 隔离校验失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        return ApkPublicationMysqlFixture.dataSource();
    }

    /**
     * 真实 SQL 读断言入口。
     *
     * @param dataSource 数据源
     * @return JDBC 模板
     */
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * MyBatis-Plus 分页拦截器，定义列表查询必须真实走分页 SQL。
     *
     * @return 拦截器
     */
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * MyBatis 会话工厂。
     *
     * @param dataSource 数据源
     * @param metaObjectHandler 审计字段填充器
     * @param interceptor 分页插件
     * @return 会话工厂
     * @throws Exception 构建失败
     */
    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler,
                                        MybatisPlusInterceptor interceptor) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(interceptor);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(mapperResources());
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    /**
     * 与生产一致的创建/更新时间填充。
     *
     * @return 填充处理器
     */
    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    /**
     * 真实本地事务管理器。
     *
     * @param dataSource 数据源
     * @return 事务管理器
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
     * 启用平台运行时。
     *
     * @return 配置
     */
    @Bean
    ProcessProperties processProperties() {
        ProcessProperties properties = new ProcessProperties();
        properties.setEnabled(true);
        return properties;
    }

    /**
     * 注册 APK 与监督决策的真实能力。
     *
     * @param eventTypes 事件类型提供者
     * @param actions 本地动作
     * @param validators 状态校验器
     * @return 能力注册表
     */
    @Bean
    CapabilityRegistry capabilityRegistry(List<EventTypeProvider> eventTypes, List<LocalAction> actions,
                                          List<StateValidator> validators) {
        return new CapabilityRegistry(eventTypes, List.of(), actions, validators, List.of());
    }

    /**
     * 只供定义服务使用的系统目录；IWS 与 dev 均已启用。
     *
     * @return Reader
     */
    @Bean
    EndpointCatalogReader endpointCatalogReader() {
        return new EndpointCatalogReader() {
            @Override
            public EndpointCatalogSnapshot snapshot() {
                return catalogSnapshot();
            }

            @Override
            public EndpointCatalogSnapshot lockSnapshot(String expectedCatalogRevision) {
                if (!Objects.equals(CATALOG_REVISION, expectedCatalogRevision)) {
                    throw new EndpointCatalogConflictException(expectedCatalogRevision, CATALOG_REVISION);
                }
                return catalogSnapshot();
            }

            @Override
            public String currentEnvironmentCode() {
                return "dev";
            }
        };
    }

    private static EndpointCatalogSnapshot catalogSnapshot() {
        return new EndpointCatalogSnapshot(CATALOG_REVISION,
            List.of(new EndpointSystemSnapshot("IWS", "IWS", true, CATALOG_REVISION)),
            List.of(new EndpointEnvironmentSnapshot("dev", "开发环境", true, CATALOG_REVISION)),
            List.of(), List.of());
    }

    private static org.springframework.core.io.Resource[] mapperResources() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        org.springframework.core.io.Resource[] process = resolver.getResources("classpath*:mapper/process/*.xml");
        org.springframework.core.io.Resource[] apk = resolver.getResources("classpath*:mapper/apk/*.xml");
        org.springframework.core.io.Resource[] all = new org.springframework.core.io.Resource[process.length + apk.length];
        System.arraycopy(process, 0, all, 0, process.length);
        System.arraycopy(apk, 0, all, process.length, apk.length);
        return all;
    }
}
