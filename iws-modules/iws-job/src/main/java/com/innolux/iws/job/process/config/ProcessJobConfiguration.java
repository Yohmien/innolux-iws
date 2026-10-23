/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件 job 接线条件装配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.config;

import com.innolux.iws.job.process.executor.BusinessScheduleExecutor;
import com.innolux.iws.job.process.executor.PlanEventWorkerExecutor;
import com.innolux.iws.job.process.control.SupervisorControlService;
import com.innolux.iws.job.process.platform.SnailJobBizIdOpenApiClient;
import com.innolux.iws.job.process.platform.SnailJobOpenApiClient;
import com.innolux.iws.job.process.platform.SnailJobSchedulerAdapter;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仅在 process、process 调度和 SnailJob 三个开关全部开启时注册执行器与控制适配器。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "iws.process.scheduler", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "snail-job", name = "enabled", havingValue = "true")
public class ProcessJobConfiguration {

    /**
     * SnailJob BizId 客户端。
     *
     * @return 客户端
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailJobOpenApiClient snailJobOpenApiClient() {
        return new SnailJobBizIdOpenApiClient();
    }

    /**
     * 控制平台适配器。
     *
     * @param client SnailJob 客户端
     * @return 适配器
     */
    @Bean
    public SnailJobSchedulerAdapter snailJobSchedulerAdapter(SnailJobOpenApiClient client) {
        return new SnailJobSchedulerAdapter(client);
    }

    /**
     * 监督控制意图协调服务。
     *
     * @param processPort process 端口
     * @param platformPort 平台端口
     * @return 控制服务
     */
    @Bean
    public SupervisorControlService supervisorControlService(ProcessSchedulerPort processPort,
                                                             SchedulerPlatformPort platformPort) {
        return new SupervisorControlService(processPort, platformPort);
    }

    /**
     * 监督轮次执行器。
     *
     * @param schedulerPort process 调度端口
     * @param controlService 监督控制服务
     * @return 执行器
     */
    @Bean
    public BusinessScheduleExecutor businessScheduleExecutor(ProcessSchedulerPort schedulerPort,
                                                             SupervisorControlService controlService) {
        return new BusinessScheduleExecutor(schedulerPort, controlService);
    }

    /**
     * QUEUE 业务事件 worker 执行器。
     *
     * @param schedulerPort process 调度端口
     * @return 执行器
     */
    @Bean
    public PlanEventWorkerExecutor planEventWorkerExecutor(ProcessSchedulerPort schedulerPort) {
        return new PlanEventWorkerExecutor(schedulerPort);
    }
}
