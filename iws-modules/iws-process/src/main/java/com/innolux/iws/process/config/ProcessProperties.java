/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 模块开关与装配属性；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * process 模块开关。
 *
 * <p>默认关闭；关闭时不注册任何 process Bean 或 Controller。调度、工作流开关只影响对应接线。</p>
 */
@Data
@ConfigurationProperties(prefix = "iws.process")
public class ProcessProperties {

    /**
     * 是否启用计划事件模块。
     */
    private boolean enabled;

    /**
     * 是否由 job 启用调度接线。
     */
    private Scheduler scheduler = new Scheduler();

    /**
     * 是否启用可选工作流审批接线。
     */
    private Workflow workflow = new Workflow();

    /**
     * 是否启用命令拒绝审计拦截器。
     */
    private boolean commandAuditEnabled = true;

    /**
     * 按 CALL 资源键配置的并发容量；缺省或 0 表示不设上限。
     */
    private Map<String, Integer> callCapacities = new LinkedHashMap<>();

    /**
     * 调度接线开关，对应 {@code iws.process.scheduler.*}。
     */
    @Data
    public static class Scheduler {

        /**
         * 是否启用 job 调度接线。
         */
        private boolean enabled;
    }

    /**
     * 工作流接线开关，对应 {@code iws.process.workflow.*}。
     */
    @Data
    public static class Workflow {

        /**
         * 是否启用可选工作流审批接线。
         */
        private boolean enabled;
    }
}
