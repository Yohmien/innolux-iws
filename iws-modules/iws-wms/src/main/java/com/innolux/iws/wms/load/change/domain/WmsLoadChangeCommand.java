/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆事实
 * @Version      : V1.0
 * @Description  : 冻结一次装拆关系、根位置、业务身份和容量重算输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 装拆事实命令。调用者必须已开启事务。
 *
 * @param effectKey 领域效果键
 * @param inputHash 规范化输入摘要
 * @param mutations 关系或根位置变更
 * @param occurredAt 业务发生时间
 * @param reason 业务原因
 * @param evidenceRef 脱敏证据引用
 * @param sourceSystem 来源系统
 * @param requestId 请求 ID
 * @param businessType 父业务类型
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务 ID
 * @param businessNo 父业务号
 * @param businessLineId 父业务明细 ID
 * @param taskKey 任务键
 * @param planInstanceId 计划实例 ID
 * @param definitionVersion 计划定义版本
 * @param eventId 计划事件 ID
 * @param attemptId 计划尝试 ID
 * @param actorId 实际操作者 ID
 * @param departmentId 实际操作部门 ID
 * @param traceId 关联物料 trace 头 ID
 */
public record WmsLoadChangeCommand(
    String effectKey,
    String inputHash,
    List<WmsLoadMutation> mutations,
    LocalDateTime occurredAt,
    String reason,
    String evidenceRef,
    String sourceSystem,
    String requestId,
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    String taskKey,
    Long planInstanceId,
    String definitionVersion,
    Long eventId,
    Long attemptId,
    Long actorId,
    Long departmentId,
    Long traceId
) {
    public WmsLoadChangeCommand {
        mutations = mutations == null ? null : List.copyOf(mutations);
    }

    public WmsLoadChangeCommand(
        String effectKey,
        String inputHash,
        List<WmsLoadMutation> mutations,
        LocalDateTime occurredAt,
        String reason,
        String evidenceRef,
        String sourceSystem,
        String requestId,
        String businessType,
        String businessName,
        long businessId,
        String businessNo,
        Long businessLineId,
        String taskKey,
        Long planInstanceId,
        String definitionVersion,
        Long eventId,
        Long attemptId,
        Long actorId,
        Long departmentId
    ) {
        this(
            effectKey, inputHash, mutations, occurredAt, reason, evidenceRef,
            sourceSystem, requestId, businessType, businessName, businessId,
            businessNo, businessLineId, taskKey, planInstanceId, definitionVersion,
            eventId, attemptId, actorId, departmentId, null
        );
    }
}
