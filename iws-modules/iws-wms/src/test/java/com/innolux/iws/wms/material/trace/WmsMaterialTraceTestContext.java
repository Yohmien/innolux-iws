/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 集成测试
 * @Version      : V1.0
 * @Description  : 组装物料 trace 服务、只插读 Mapper 与真实 MySQL 事务上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.wms.material.trace.service.impl.WmsMaterialTraceServiceImpl;
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

/**
 * 物料 trace 隔离集成测试上下文。
 *
 * @author YONGJIE.PENG
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan("com.innolux.iws.wms.material.trace.mapper")
@Import(WmsMaterialTraceServiceImpl.class)
class WmsMaterialTraceTestContext {

    @Bean
    DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(WmsMaterialTraceTestDatabase.jdbcUrl());
        dataSource.setUsername(WmsMaterialTraceTestDatabase.username());
        dataSource.setPassword(WmsMaterialTraceTestDatabase.password());
        return dataSource;
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml")
        );
        return factory;
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        AtomicLong sequence = new AtomicLong(10_000L);
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
}
