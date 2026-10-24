/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行查询隔离测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.query;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.mybatis.aspect.DataPermissionPointcutAdvisor;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.common.mybatis.interceptor.PlusDataPermissionInterceptor;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.PlanCandidateQueryService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.service.impl.ProcessExecutionQueryServiceImpl;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.ProcessRequestQuerySupport;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 只装配查询服务、对象授权、MyBatis 与真实隔离 MySQL 的测试宿主。
 */
@SpringBootConfiguration
@EnableTransactionManagement
@EnableAspectJAutoProxy(proxyTargetClass = true)
@MapperScan("com.innolux.iws.process.mapper")
@Import({DefinitionGraphBuilder.class, RuntimeDao.class, ProcessObjectAuthorizer.class,
    com.innolux.iws.process.runtime.PlanInstanceActionPolicy.class,
    com.innolux.iws.process.runtime.QueryRecoveryAdmission.class,
    PlanCandidateQueryService.class,
    ProcessRequestQuerySupport.class,
    ProcessExecutionQueryServiceImpl.class})
public class QueryFixtureApplication {

    private static final AtomicReference<String> DENIED_BUSINESS_ID = new AtomicReference<>();

    @Bean
    com.innolux.iws.process.runtime.ProcessAccessPolicy queryFixtureScope() {
        // 此夹具只包含 SMT 调度，行级部门范围仍由真实 DataPermission SQL 校验。
        return target -> Long.valueOf(100L).equals(target.scheduleId())
            && (DENIED_BUSINESS_ID.get() == null
                || !Objects.equals(DENIED_BUSINESS_ID.get(), target.businessId()));
    }

    /**
     * 设置当前用例需要由业务策略拒绝的业务 ID。
     *
     * @param businessId 业务 ID；null 表示恢复默认全量范围
     */
    static void denyBusinessId(String businessId) {
        DENIED_BUSINESS_ID.set(businessId);
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }

    @Bean
    com.innolux.iws.common.core.utils.SpringUtils springUtils() {
        return new com.innolux.iws.common.core.utils.SpringUtils();
    }

    /**
     * 隔离数据源。
     *
     * @return 数据源
     * @throws SQLException 初始化失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        return QueryMysqlFixture.dataSource();
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
     * MyBatis-Plus 会话工厂，启用数据权限与分页。
     *
     * @param dataSource 数据源
     * @param metaObjectHandler 元对象填充
     * @return 会话工厂
     * @throws Exception 构建失败
     */
    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler,
        com.innolux.iws.common.core.utils.SpringUtils springUtils)
        throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
            .getResources("classpath*:mapper/**/*Mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PlusDataPermissionInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(interceptor);
        factory.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    /**
     * 与生产一致的创建/更新时间与操作者填充规则。
     *
     * @return 填充处理器
     */
    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    /**
     * 数据权限 Mapper 注解切面。
     *
     * @return 切面顾问
     */
    @Bean
    DataPermissionPointcutAdvisor dataPermissionPointcutAdvisor() {
        return new DataPermissionPointcutAdvisor();
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
}
