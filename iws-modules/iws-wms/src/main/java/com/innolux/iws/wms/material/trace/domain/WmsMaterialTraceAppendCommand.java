/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 定义内部成功物料事实的完整追加命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部物料事实追加命令。调用者不得提供 trace、item 或 edge 主键。
 *
 * @param effectKey 领域效果键
 * @param businessType 父业务类型编码快照
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务内部 ID
 * @param businessNo 父业务号快照
 * @param businessLineId 父业务明细 ID
 * @param rootBusinessType 根父业务类型编码快照
 * @param rootBusinessId 根父业务内部 ID
 * @param taskKey WMS 任务键
 * @param planInstanceId 计划实例 ID
 * @param planCode 计划编码
 * @param planType 计划类型
 * @param definitionVersion 计划定义版本
 * @param eventId 计划事件 ID
 * @param attemptId 计划尝试 ID
 * @param inputHash 规范化输入 SHA-256
 * @param reverseTraceId 补偿关联的原 trace ID
 * @param traceKind 追加事实类型
 * @param reason 业务原因
 * @param evidenceRef 脱敏证据引用
 * @param actorId 实际操作者 ID 快照
 * @param actorName 实际操作者名称快照
 * @param initiatorId 发起人标识快照
 * @param initiatorName 发起人名称快照
 * @param departmentId 操作者部门 ID 快照
 * @param departmentName 操作者部门名称快照
 * @param executionSubject 执行主体快照
 * @param executionAccount 执行账号快照
 * @param onBehalfOf 授权代办身份快照
 * @param sourceSystem 来源系统
 * @param terminalId 来源终端标识
 * @param requestId 请求 ID
 * @param sourceTraceId 上游链路追踪 ID
 * @param occurredAt 真实业务发生时间
 * @param items 完整物料项快照
 * @param edges 生产家谱边
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceAppendCommand(
    String effectKey,
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    String rootBusinessType,
    long rootBusinessId,
    String taskKey,
    Long planInstanceId,
    String planCode,
    String planType,
    String definitionVersion,
    Long eventId,
    Long attemptId,
    String inputHash,
    Long reverseTraceId,
    WmsMaterialTraceKind traceKind,
    String reason,
    String evidenceRef,
    Long actorId,
    String actorName,
    String initiatorId,
    String initiatorName,
    Long departmentId,
    String departmentName,
    String executionSubject,
    String executionAccount,
    String onBehalfOf,
    String sourceSystem,
    String terminalId,
    String requestId,
    String sourceTraceId,
    LocalDateTime occurredAt,
    List<WmsMaterialTraceItemInput> items,
    List<WmsMaterialTraceEdgeInput> edges
) {

    /**
     * 复制调用者集合，避免追加后命令集合被外部修改。
     */
    public WmsMaterialTraceAppendCommand {
        items = items == null ? List.of() : List.copyOf(items);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
