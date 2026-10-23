/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督轮次与计划名额窄端口；供 QUEUE/IMMEDIATE 共用
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.RoundStatus;

/**
 * 监督准入窄端口。调用方不得直接改监督控制行或计划名额行。
 */
public interface SupervisorAdmissionPort {

    /**
     * 独占当前监督的开放轮次；已有他人活跃租约返回 null。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param commandKey 命令键，周期 tick 可为 null
     * @param maxDurationMs 本轮租约毫秒
     * @return 轮次租约，忙碌或暂停返回 null
     */
    SupervisorRoundLease openExclusive(long supervisorInstanceId, String commandKey, long maxDurationMs);

    /**
     * 只读检查监督是否可准入。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param requiredMode 调用方要求模式
     * @return null 表示可继续，否则为拒绝码
     */
    String precheck(long supervisorInstanceId, PlanMode requiredMode);

    /**
     * 在轮次租约内原子占用一个 PLAN_SLOT 并准入子计划。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param lease 当前轮次租约
     * @param childInstanceId 子计划实例 ID
     * @return 准入结果
     */
    SupervisorSlot admit(long supervisorInstanceId, SupervisorRoundLease lease, long childInstanceId);

    /**
     * 安全释放一次 PLAN_SLOT。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param childInstanceId 子计划实例 ID
     * @param reason 原因
     * @return 是否已释放或原本已释放
     */
    boolean release(long supervisorInstanceId, long childInstanceId, String reason);

    /**
     * 按当前代次关闭轮次。
     *
     * @param lease 轮次租约
     * @param status 目标状态
     * @param reason 原因
     * @return 是否由本代次关闭
     */
    boolean close(SupervisorRoundLease lease, RoundStatus status, String reason);
}
