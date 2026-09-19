/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.liteflow.config;

import com.innolux.iws.common.liteflow.component.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * LiteFlow 公共节点自动配置。
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration
@ConditionalOnProperty(value = "liteflow.enable", havingValue = "true")
public class LiteFlowAutoConfiguration {

    /**
     * 空节点，用于显式表达无需处理的分支。
     *
     * @return 空节点
     */
    @Bean("noop")
    public NoopComponent noopComponent() {
        return new NoopComponent();
    }

    /**
     * 失败节点，用于显式抛出业务失败提示。
     *
     * @return 失败节点
     */
    @Bean("fail")
    public FailComponent failComponent() {
        return new FailComponent();
    }

    /**
     * 上下文必填校验节点。
     *
     * @return 上下文必填校验节点
     */
    @Bean("contextRequired")
    public ContextRequiredComponent contextRequiredComponent() {
        return new ContextRequiredComponent();
    }

    /**
     * 恒为 true 的条件节点。
     *
     * @return true 条件节点
     */
    @Bean("alwaysTrue")
    public AlwaysTrueComponent alwaysTrueComponent() {
        return new AlwaysTrueComponent();
    }

    /**
     * 恒为 false 的条件节点。
     *
     * @return false 条件节点
     */
    @Bean("alwaysFalse")
    public AlwaysFalseComponent alwaysFalseComponent() {
        return new AlwaysFalseComponent();
    }

}
