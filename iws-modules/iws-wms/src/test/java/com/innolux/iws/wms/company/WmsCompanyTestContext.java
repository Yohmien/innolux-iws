/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环集成测试
 * @Version      : V1.0
 * @Description  : 组装只包含公司事务、审计和真实 MySQL 的最小 Spring 测试上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.company;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.mybatis.handler.InjectionMetaObjectHandler;
import com.innolux.iws.system.api.RoleService;
import com.innolux.iws.wms.service.impl.WmsCompanyServiceImpl;
import com.innolux.iws.wms.service.impl.WmsSupplierServiceImpl;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.support.company.WmsCompanyAuditService;
import com.innolux.iws.wms.support.meta.IwsCrudMetaRegistry;
import com.innolux.iws.wms.support.schema.WmsDirectWriteSchemaGate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 公司最小闭环的隔离集成测试上下文。
 *
 * @author YONGJIE.PENG
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan("com.innolux.iws.wms.mapper")
@Import({
    SpringUtils.class,
    WmsCompanyServiceImpl.class,
    WmsSupplierServiceImpl.class,
    WmsCompanyAuditService.class,
    WmsCompanyAccessPolicy.class,
    IwsCrudMetaRegistry.class,
    WmsDirectWriteSchemaGate.class
})
class WmsCompanyTestContext {

    private static final Set<Long> KNOWN_ROLE_IDS = Set.of(100L, 200L, 300L);

    @Bean
    WmsCompanyTestInfrastructure.InterceptingDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(WmsCompanyTestDatabase.jdbcUrl());
        dataSource.setUsername(WmsCompanyTestDatabase.username());
        dataSource.setPassword(WmsCompanyTestDatabase.password());
        return new WmsCompanyTestInfrastructure.InterceptingDataSource(dataSource);
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.ASSIGN_ID));
        globalConfig.setMetaObjectHandler(new InjectionMetaObjectHandler());

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(interceptor);
        return factory;
    }

    @Bean
    WmsCompanyTestInfrastructure.FaultInjectingTransactionManager transactionManager(DataSource dataSource) {
        return new WmsCompanyTestInfrastructure.FaultInjectingTransactionManager(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    RoleService roleService() {
        return new StubRoleService();
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * 只返回测试固定角色，避免测试审批链依赖系统库。
     */
    static final class StubRoleService implements RoleService {

        @Override
        public Map<Long, String> selectRoleNamesByIds(Collection<Long> roleIds) {
            Map<Long, String> names = new HashMap<>();
            for (Long roleId : roleIds) {
                if (KNOWN_ROLE_IDS.contains(roleId)) {
                    names.put(roleId, "role-" + roleId);
                }
            }
            return names;
        }
    }
}
