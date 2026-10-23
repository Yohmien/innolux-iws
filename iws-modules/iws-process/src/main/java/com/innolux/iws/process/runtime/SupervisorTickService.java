/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SupervisorTickService；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.SupervisorTickResult;

/**
 * 监督有限轮次推进服务。job 只通过该服务驱动监督，不直接改事件或控制表。
 */
public interface SupervisorTickService {

    /**
     * 续未收口轮次或创建下一轮，执行监督 DECISION/LOCAL 事件。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param batchLimit 单轮事件上限
     * @param maxDurationMs 单轮时长上限
     * @return 轮次结果
     */
    SupervisorTickResult tick(long supervisorInstanceId, int batchLimit, long maxDurationMs);

    /**
     * 读取并刷新控制意图快照。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @return 控制意图快照
     */
    ControlIntentSnapshot flushControlIntent(long supervisorInstanceId);

    /**
     * 回写平台观测结果，按控制修订号 CAS。
     *
     * @param command 平台结果
     * @return 回写确认
     */
    PlatformResultAck recordPlatformResult(PlatformResultCommand command);
}
