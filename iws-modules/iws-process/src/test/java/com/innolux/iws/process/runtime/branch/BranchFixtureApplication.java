/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 分支、汇合与计划聚合隔离测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.branch;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.process.runtime.BranchCondition;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.RuntimeDao;
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
 * 只装配分支运行链与真实隔离 MySQL，不装配监督、即时或外部动作。
 */
@SpringBootConfiguration
@EnableTransactionManagement
@MapperScan("com.innolux.iws.process.mapper")
@Import({DefinitionGraphBuilder.class, RuntimeDao.class, PlanMaterializer.class,
    EventEligibilityService.class, BranchSelector.class, PlanActivationService.class})
public class BranchFixtureApplication {

    /**
     * 隔离数据源。
     *
     * @return 数据源
     * @throws SQLException 初始化失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        return BranchMysqlFixture.dataSource();
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
     * JSON 映射器。
     *
     * @return 映射器
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
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
     * 分支条件只从调用方冻结事实读取，不使用恒真 stub。
     *
     * @return 条件
     */
    @Bean
    BranchCondition routeACondition() {
        return new BranchRouteCondition("ROUTE_A", "A");
    }

    @Bean
    BranchCondition routeCCondition() {
        return new BranchRouteCondition("ROUTE_C", "C");
    }

    /**
     * 测试能力注册表。
     *
     * @param conditions 分支条件
     * @return 注册表
     */
    @Bean
    CapabilityRegistry capabilityRegistry(List<BranchCondition> conditions) {
        return new CapabilityRegistry(List.of(), List.of(), List.of(), List.of(), conditions);
    }
}
