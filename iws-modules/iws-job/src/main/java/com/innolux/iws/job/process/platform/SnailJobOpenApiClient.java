/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SnailJob BizId OpenAPI 窄客户端；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import java.util.Optional;

/**
 * SnailJob 按 bizId 管理任务的最小客户端。
 */
public interface SnailJobOpenApiClient {

    /**
     * 查询任务 ID。
     *
     * @param bizId 稳定任务 ID
     * @return 平台任务 ID，任务不存在时为空
     */
    Optional<Long> findJobId(String bizId);

    /**
     * 创建任务。
     *
     * @param definition 任务定义
     * @return 平台任务 ID
     */
    long add(SnailJobJobDefinition definition);

    /**
     * 更新任务。
     *
     * @param definition 任务定义
     * @return 平台是否确认更新
     */
    boolean update(SnailJobJobDefinition definition);

    /**
     * 启用或停用任务。
     *
     * @param bizId 稳定任务 ID
     * @param running 是否运行
     * @return 平台是否确认
     */
    boolean updateRunning(String bizId, boolean running);

    /**
     * 触发一次集群任务。
     *
     * @param bizId 稳定任务 ID
     * @return 平台是否确认触发
     */
    boolean trigger(String bizId);
}
