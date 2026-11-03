/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 以CAS推进下发、回执、未知或消费终态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分配锁外部效果推进命令。
 *
 * @param identity 原分配锁任务身份
 * @param targetEffectResult 目标效果状态
 * @param expectations 逐锁期望代次
 * @param terminalEffectKey 消费终态效果键
 * @param consumptionTraceId 消费 trace 头 ID
 * @param evidenceRef 脱敏证据引用
 * @param occurredAt 效果发生时间
 * @param actorId 实际操作者 ID
 * @param departmentId 实际操作部门 ID
 */
public record WmsAllocationEffectCommand(
    WmsAllocationEffectIdentity identity,
    String targetEffectResult,
    List<WmsAllocationEffectExpectation> expectations,
    String terminalEffectKey,
    Long consumptionTraceId,
    String evidenceRef,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {
    public WmsAllocationEffectCommand {
        expectations = expectations == null ? null : List.copyOf(expectations);
    }
}
