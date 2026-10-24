package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
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

/** 运行层真实 MySQL 测试宿主。 */
@SpringBootConfiguration
@EnableTransactionManagement
@MapperScan("com.innolux.iws.process.mapper")
@EnableConfigurationProperties(ProcessProperties.class)
@Import({RuntimeDao.class, ProcessAuditService.class, CallCapacityService.class,
    EventEligibilityService.class, DefinitionGraphBuilder.class, CapabilityRegistry.class,
    BranchSelector.class, PlanActivationService.class, EventMutationService.class, RecoveryEvidenceService.class})
public class RuntimeFixtureApplication {

    @Bean
    DataSource dataSource() throws SQLException {
        return RuntimeMysqlFixture.dataSource();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

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

    @Bean
    MetaObjectHandler metaObjectHandler() {
        return new InjectionMetaObjectHandler();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
