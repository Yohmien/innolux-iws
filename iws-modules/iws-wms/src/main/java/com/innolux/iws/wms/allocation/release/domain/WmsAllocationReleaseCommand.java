/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 按已冻结无效果或对账后无效果证据释放本任务分配锁和通道锁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分配锁释放命令。
 *
 * @param identity 释放任务身份
 * @param effectKey 释放领域效果键
 * @param inputHash 规范化输入摘要
 * @param evidenceHash 证据检查结果摘要
 * @param evidenceRevision 证据检查结果修订号
 * @param evidenceRef 脱敏证据引用
 * @param evidenceType 释放证据类型；空值按 NO_EFFECT 兼容
 * @param expectations 检查时锁快照
 * @param occurredAt 业务发生时间
 * @param reason 释放原因
 * @param sourceSystem 来源系统
 * @param requestId 请求 ID
 * @param eventId 计划事件 ID
 * @param attemptId 计划尝试 ID
 * @param actorId 实际操作者 ID
 * @param departmentId 实际操作部门 ID
 */
public record WmsAllocationReleaseCommand(
    WmsAllocationReleaseIdentity identity,
    String effectKey,
    String inputHash,
    String evidenceHash,
    int evidenceRevision,
    String evidenceRef,
    String evidenceType,
    List<WmsAllocationReleaseLockExpectation> expectations,
    LocalDateTime occurredAt,
    String reason,
    String sourceSystem,
    String requestId,
    Long eventId,
    Long attemptId,
    Long actorId,
    Long departmentId
) {
    public static final String EVIDENCE_NO_EFFECT = "NO_EFFECT";

    public WmsAllocationReleaseCommand {
        expectations = expectations == null ? null : List.copyOf(expectations);
    }

    public WmsAllocationReleaseCommand(
        WmsAllocationReleaseIdentity identity,
        String effectKey,
        String inputHash,
        String evidenceHash,
        int evidenceRevision,
        String evidenceRef,
        List<WmsAllocationReleaseLockExpectation> expectations,
        LocalDateTime occurredAt,
        String reason,
        String sourceSystem,
        String requestId,
        Long eventId,
        Long attemptId,
        Long actorId,
        Long departmentId
    ) {
        this(
            identity,
            effectKey,
            inputHash,
            evidenceHash,
            evidenceRevision,
            evidenceRef,
            EVIDENCE_NO_EFFECT,
            expectations,
            occurredAt,
            reason,
            sourceSystem,
            requestId,
            eventId,
            attemptId,
            actorId,
            departmentId
        );
    }

    public String normalizedEvidenceType() {
        return evidenceType == null || evidenceType.isBlank()
            ? EVIDENCE_NO_EFFECT : evidenceType;
    }
}
