/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SnailJob 2.0.2 按 bizId 管理集群任务的 SDK 适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.client.job.core.handler.AbstractParamsBizIdHandler;
import com.aizuda.snailjob.client.job.core.handler.add.ClusterAddBizIdHandler;
import com.aizuda.snailjob.client.job.core.handler.update.ClusterUpdateBizIdHandler;
import com.aizuda.snailjob.client.job.core.openapi.SnailJobBizIdOpenApi;
import com.aizuda.snailjob.common.core.enums.JobArgsTypeEnum;
import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.model.response.JobExistsResponse;

import java.util.Optional;

/**
 * 直接复用 SnailJob 2.0.2 的 BizId OpenAPI，不另建任务管理协议。
 */
public class SnailJobBizIdOpenApiClient implements SnailJobOpenApiClient {

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Long> findJobId(String bizId) {
        JobExistsResponse response = SnailJobBizIdOpenApi.existsJob(bizId).execute();
        return response == null || response.getId() == null ? Optional.empty() : Optional.of(response.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long add(SnailJobJobDefinition definition) {
        ClusterAddBizIdHandler handler = SnailJobBizIdOpenApi.addClusterJob(definition.bizId());
        configure(handler, definition);
        Long jobId = handler.execute();
        if (jobId == null) {
            throw new IllegalStateException("SnailJob 创建任务未返回任务 ID");
        }
        return jobId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean update(SnailJobJobDefinition definition) {
        ClusterUpdateBizIdHandler handler = SnailJobBizIdOpenApi.updateClusterJob(definition.bizId());
        configure(handler, definition);
        return Boolean.TRUE.equals(handler.execute());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean updateRunning(String bizId, boolean running) {
        Boolean result = SnailJobBizIdOpenApi.updateJobStatus(bizId)
            .setStatus(running ? StatusEnum.YES : StatusEnum.NO)
            .execute();
        return Boolean.TRUE.equals(result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean trigger(String bizId) {
        return Boolean.TRUE.equals(SnailJobBizIdOpenApi.triggerClusterJob(bizId).execute());
    }

    static <H extends AbstractParamsBizIdHandler<H, ?>> void configure(H handler,
                                                                       SnailJobJobDefinition definition) {
        handler.setJobName(definition.jobName())
            .setExecutorInfo(definition.executorName())
            .setTriggerType(definition.triggerType())
            .setTriggerInterval(definition.triggerInterval())
            .setExecutorTimeout(definition.executorTimeoutSeconds())
            .setMaxRetryTimes(definition.maxRetryTimes());
        if (definition.routeKey() != null) {
            handler.getReqDTO().setRouteKey(definition.routeKey().getType());
        }
        handler.getReqDTO().setArgsStr(definition.paramsJson());
        handler.getReqDTO().setArgsType(JobArgsTypeEnum.JSON.getArgsType());
    }

}
