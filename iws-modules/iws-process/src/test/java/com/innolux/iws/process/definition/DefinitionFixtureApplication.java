/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划定义隔离测试宿主；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.service.impl.PlanDefinitionServiceImpl;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionReceiptStore;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.support.EditCapabilityResolver;
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

/**
 * 只装配定义层、MyBatis 与真实隔离 MySQL 的测试宿主。
 */
@SpringBootConfiguration
@EnableTransactionManagement
@MapperScan("com.innolux.iws.process.mapper")
@Import({DefinitionValidator.class, DefinitionGraphBuilder.class, EditCapabilityResolver.class,
    DefinitionReceiptStore.class, ProcessAuditService.class, CommandReceiptService.class,
    PlanDefinitionServiceImpl.class})
public class DefinitionFixtureApplication {

    /**
     * 隔离数据源。
     *
     * @return 数据源
     * @throws SQLException 初始化失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        return DefinitionMysqlFixture.dataSource();
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
     * 与生产一致的创建/更新时间与操作者填充规则。
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
     * 真实 endpoint 表上的隔离目录 Reader。
     *
     * @param jdbcTemplate 模板
     * @return Reader
     */
    @Bean
    EndpointCatalogReader endpointCatalogReader(JdbcTemplate jdbcTemplate) {
        return new EndpointCatalogFixtureReader(jdbcTemplate, "TEST");
    }

    /**
     * 测试能力注册表。
     *
     * @return 注册表
     */
    @Bean
    CapabilityRegistry capabilityRegistry() {
        return DefinitionCapabilityFixture.registry();
    }
}
