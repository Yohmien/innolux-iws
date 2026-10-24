/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 完整组件扫描的隔离基础设施；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEdgeMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessExecutionQueryMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessSystemPolicyMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 完整扫描 process 与 endpoint 主包，只替换 Mapper、JDBC 和 JSON 基础设施。
 *
 * <p>本夹具位于主包之外，避免自身被完整扫描；历史数据库 fixture 配置由测试类
 * 通过 {@code FilteredClassLoader} 排除。</p>
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties
@ComponentScan(basePackages = {"com.innolux.iws.process", "com.innolux.iws.endpoint"},
    excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.CUSTOM,
        classes = ProcessModuleAssemblyFixture.TestSourceFilter.class))
public class ProcessModuleAssemblyFixture {

    @Bean
    com.innolux.iws.process.mapper.ProcessRequestQueryMapper processRequestQueryMapper() {
        return mock(com.innolux.iws.process.mapper.ProcessRequestQueryMapper.class);
    }

    /** 排除 test-classes，完整扫描主源码；ClassLoader 过滤不改变扫描到的资源。 */
    public static class TestSourceFilter implements org.springframework.core.type.filter.TypeFilter {
        @Override
        public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                             org.springframework.core.type.classreading.MetadataReaderFactory factory) {
            return reader.getResource().toString().replace('\\', '/').contains("/test-classes/");
        }
    }

    /**
     * 只提供数据库产品标识，启动 endpoint 仓库时不建立连接池或发起查询。
     *
     * @return mock 数据源
     * @throws SQLException 元数据桩失败
     */
    @Bean
    DataSource dataSource() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return dataSource;
    }

    /**
     * 数据库基础设施替身；装配阶段不执行 SQL。
     *
     * @return mock JDBC 模板
     */
    @Bean
    JdbcTemplate jdbcTemplate() {
        return mock(JdbcTemplate.class);
    }

    /**
     * 真实 JSON 映射器，避免为装配验证替换序列化行为。
     *
     * @return 映射器
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    BusinessScheduleMapper businessScheduleMapper() {
        return mock(BusinessScheduleMapper.class);
    }

    @Bean
    BusinessSchedulePlanTypeMapper businessSchedulePlanTypeMapper() {
        return mock(BusinessSchedulePlanTypeMapper.class);
    }

    @Bean
    PlanDefinitionMapper planDefinitionMapper() {
        return mock(PlanDefinitionMapper.class);
    }

    @Bean
    ProcessActionAuditMapper processActionAuditMapper() {
        return mock(ProcessActionAuditMapper.class);
    }

    @Bean
    ProcessAttemptMapper processAttemptMapper() {
        return mock(ProcessAttemptMapper.class);
    }

    @Bean
    ProcessEdgeMapper processEdgeMapper() {
        return mock(ProcessEdgeMapper.class);
    }

    @Bean
    ProcessEventDefinitionMapper processEventDefinitionMapper() {
        return mock(ProcessEventDefinitionMapper.class);
    }

    @Bean
    ProcessEventMapper processEventMapper() {
        return mock(ProcessEventMapper.class);
    }

    @Bean
    ProcessExecutionQueryMapper processExecutionQueryMapper() {
        return mock(ProcessExecutionQueryMapper.class);
    }

    @Bean
    ProcessInstanceMapper processInstanceMapper() {
        return mock(ProcessInstanceMapper.class);
    }

    @Bean
    ProcessRequestDetailMapper processRequestDetailMapper() {
        return mock(ProcessRequestDetailMapper.class);
    }

    @Bean
    ProcessScheduleControlMapper processScheduleControlMapper() {
        return mock(ProcessScheduleControlMapper.class);
    }

    @Bean
    ProcessStateMapper processStateMapper() {
        return mock(ProcessStateMapper.class);
    }

    @Bean
    ProcessSystemContextMapper processSystemContextMapper() {
        return mock(ProcessSystemContextMapper.class);
    }

    @Bean
    ProcessSystemPolicyMapper processSystemPolicyMapper() {
        return mock(ProcessSystemPolicyMapper.class);
    }

    @Bean
    ProcessTokenMapper processTokenMapper() {
        return mock(ProcessTokenMapper.class);
    }

    @Bean
    ProcessTriggerReceiptMapper processTriggerReceiptMapper() {
        return mock(ProcessTriggerReceiptMapper.class);
    }
}
