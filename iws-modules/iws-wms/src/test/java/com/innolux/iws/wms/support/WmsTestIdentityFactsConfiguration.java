/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 权威身份隔离测试
 * @Version      : V1.0
 * @Description  : 为历史 WMS 测试上下文提供与生产一致的 JDBC 权威身份事实读取器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.system.service.JdbcIamIdentityFactReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * WMS Testcontainers 上下文的权威身份事实装配。
 */
@Configuration(proxyBeanMethods = false)
public class WmsTestIdentityFactsConfiguration {

    /**
     * 使用测试物理库创建 identity 路由，不复制账号或权限规则。
     *
     * @param dataSource 测试业务数据源
     * @return 生产同源 JDBC 身份事实读取器
     */
    @Bean
    IamIdentityFacts iamIdentityFacts(DataSource dataSource) {
        DynamicRoutingDataSource routing;
        if (dataSource instanceof DynamicRoutingDataSource dynamic) {
            routing = dynamic;
        } else {
            routing = new DynamicRoutingDataSource(List.of());
            routing.addDataSource("master", dataSource);
            routing.addDataSource("identity", dataSource);
            routing.setPrimary("master");
            routing.setStrict(true);
            routing.afterPropertiesSet();
        }
        return new JdbcIamIdentityFactReader(routing);
    }
}
