/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时命令唯一回执锚点与追加事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以 action_audit 的唯一 receiptKey 记录首次回执，后续只追加事实。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateCommandReceiptStore {

    private static final String ACTION_CODE = "EXECUTE_IMMEDIATE";

    private static final int REASON_LIMIT = 1000;

    private final ProcessActionAuditMapper auditMapper;

    private final ProcessAuditService auditService;

    private final ObjectMapper objectMapper;

    /**
     * 构造回执存储。
     *
     * @param auditMapper 审计 Mapper
     * @param auditService 审计服务
     * @param objectMapper JSON 映射器
     */
    public ImmediateCommandReceiptStore(ProcessActionAuditMapper auditMapper, ProcessAuditService auditService,
                                        ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 回执唯一键。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 回执键
     */
    public static String receiptKey(long scheduleId, String commandKey) {
        return "IMMEDIATE:" + scheduleId + ":" + commandKey;
    }

    /**
     * 请求指纹。
     *
     * @param command 命令
     * @return SHA-256
     */
    public static String requestHash(ExecuteImmediateCommand command) {
        return ProcessText.sha256(String.join("|", String.valueOf(command.scheduleId()), command.planType(),
            command.businessType(), command.businessId(), command.taskKey(), command.inputHash()));
    }

    /**
     * 查询回执锚点。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 锚点或 null
     */
    public ProcessActionAudit findAnchor(long scheduleId, String commandKey) {
        return auditMapper.selectOne(Wrappers.<ProcessActionAudit>lambdaQuery()
            .eq(ProcessActionAudit::getReceiptKey, receiptKey(scheduleId, commandKey))
            .last("LIMIT 1"));
    }

    /**
     * 原命令开始时间。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 开始时间
     */
    public LocalDateTime startedAt(long scheduleId, String commandKey) {
        ProcessActionAudit anchor = findAnchor(scheduleId, commandKey);
        if (anchor == null) {
            return LocalDateTime.now();
        }
        return anchor.getRequestStartedAt() == null ? anchor.getOccurredAt() : anchor.getRequestStartedAt();
    }

    /**
     * 锚点请求是否与本次命令一致。
     *
     * @param anchor 回执锚点
     * @param command 当前命令
     * @return true 表示同一请求
     */
    public boolean sameRequest(ProcessActionAudit anchor, ExecuteImmediateCommand command) {
        Map<String, Object> request = parse(anchor.getPayloadSummary());
        return requestHash(command).equals(request.get("requestHash"));
    }

    /**
     * 读取当前可见结果。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 结果或 null
     */
    public ImmediateExecutionVO findView(long scheduleId, String commandKey) {
        ProcessActionAudit anchor = findAnchor(scheduleId, commandKey);
        if (anchor == null) {
            return null;
        }
        ProcessActionAudit latest = auditMapper.selectOne(Wrappers.<ProcessActionAudit>lambdaQuery()
            .eq(ProcessActionAudit::getActionCode, ACTION_CODE)
            .eq(ProcessActionAudit::getScheduleId, scheduleId)
            .eq(ProcessActionAudit::getCommandKey, commandKey)
            .orderByDesc(ProcessActionAudit::getId)
            .last("LIMIT 1"));
        if (latest == null) {
            latest = anchor;
        }
        Map<String, Object> response = parse(latest.getDecisionSummary());
        Map<String, Object> original = parse(anchor.getDecisionSummary());
        String resultCode = text(response.get("resultCode"), latest.getAfterStatus());
        String originalCode = text(original.get("resultCode"), anchor.getAfterStatus());
        String effectResult = text(response.get("effectResult"), null);
        boolean reconciliation = bool(response.get("reconciliationRequired"),
            requiresReconciliation(resultCode));
        return new ImmediateExecutionVO(commandKey, resultCode, latest.getReason(),
            ProcessText.id(latest.getInstanceId()), ProcessText.id(latest.getSupervisorInstanceId()),
            anchor.getRequestStartedAt() == null ? anchor.getOccurredAt() : anchor.getRequestStartedAt(),
            latest.getRequestFinishedAt(), effectResult, reconciliation,
            queryUrl(scheduleId, commandKey), originalCode, latest.getOccurredAt());
    }

    /**
     * 在准入事务内写入唯一锚点。
     *
     * @param command 命令
     * @param supervisorInstanceId 监督实例 ID
     * @param instanceId 业务实例 ID
     * @param roundNo 轮次号
     * @param resultCode 当前结果码
     * @param message 中文说明
     * @param effectResult 效果结果
     * @param reconciliationRequired 是否需对账
     * @param startedAt 请求开始时间
     * @param finishedAt 结束时间
     * @param result 审计结果
     */
    public void writeAnchor(ExecuteImmediateCommand command, Long supervisorInstanceId, Long instanceId, Long roundNo,
                            String resultCode, String message, String effectResult, boolean reconciliationRequired,
                            LocalDateTime startedAt, LocalDateTime finishedAt, AuditResult result) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.COMMAND_REJECTED, ActionSource.USER, result);
        audit.setActionCode(ACTION_CODE);
        audit.setReceiptKey(receiptKey(command.scheduleId(), command.commandKey()));
        audit.setCommandKey(command.commandKey());
        audit.setDispatchId(receiptKey(command.scheduleId(), command.commandKey()));
        audit.setScheduleId(command.scheduleId());
        audit.setInstanceId(instanceId);
        audit.setChildInstanceId(instanceId);
        audit.setSupervisorInstanceId(supervisorInstanceId);
        audit.setRoundNo(roundNo);
        audit.setAfterStatus(resultCode);
        audit.setReason(limit(message));
        audit.setPayloadSummary(requestSummary(command));
        audit.setDecisionSummary(responseSummary(resultCode, message, effectResult, reconciliationRequired));
        audit.setRequestStartedAt(startedAt);
        audit.setRequestFinishedAt(finishedAt);
        audit.setOccurredAt(finishedAt == null ? startedAt : finishedAt);
        auditMapper.insert(audit);
    }

    /**
     * 追加当前事实，不更新历史审计。
     *
     * @param command 命令
     * @param supervisorInstanceId 监督实例 ID
     * @param instanceId 业务实例 ID
     * @param roundNo 轮次号
     * @param resultCode 当前结果码
     * @param message 中文说明
     * @param effectResult 效果结果
     * @param reconciliationRequired 是否需对账
     * @param startedAt 请求开始时间
     * @param finishedAt 事实时间
     * @param result 审计结果
     */
    public void append(ExecuteImmediateCommand command, Long supervisorInstanceId, Long instanceId, Long roundNo,
                       String resultCode, String message, String effectResult, boolean reconciliationRequired,
                       LocalDateTime startedAt, LocalDateTime finishedAt, AuditResult result) {
        ProcessActionAudit audit = auditService.newAudit(ActionType.COMMAND_REJECTED,
            ActionSource.SCHEDULER, result);
        audit.setActionCode(ACTION_CODE);
        audit.setCommandKey(command.commandKey());
        audit.setDispatchId(receiptKey(command.scheduleId(), command.commandKey()));
        audit.setScheduleId(command.scheduleId());
        audit.setInstanceId(instanceId);
        audit.setChildInstanceId(instanceId);
        audit.setSupervisorInstanceId(supervisorInstanceId);
        audit.setRoundNo(roundNo);
        audit.setAfterStatus(resultCode);
        audit.setReason(limit(message));
        audit.setDecisionSummary(responseSummary(resultCode, message, effectResult, reconciliationRequired));
        audit.setRequestStartedAt(startedAt);
        audit.setRequestFinishedAt(finishedAt);
        audit.setOccurredAt(finishedAt);
        auditMapper.insert(audit);
    }

    /**
     * 当前是否已存在同结果事实。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @param resultCode 结果码
     * @return true 表示无需重复追加
     */
    public boolean latestHasResult(long scheduleId, String commandKey, String resultCode) {
        ProcessActionAudit latest = auditMapper.selectOne(Wrappers.<ProcessActionAudit>lambdaQuery()
            .eq(ProcessActionAudit::getActionCode, ACTION_CODE)
            .eq(ProcessActionAudit::getScheduleId, scheduleId)
            .eq(ProcessActionAudit::getCommandKey, commandKey)
            .orderByDesc(ProcessActionAudit::getId)
            .last("LIMIT 1"));
        return latest != null && resultCode.equals(latest.getAfterStatus());
    }

    private String requestSummary(ExecuteImmediateCommand command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestHash", requestHash(command));
        value.put("planType", command.planType());
        value.put("businessType", command.businessType());
        value.put("businessId", command.businessId());
        value.put("taskKey", command.taskKey());
        value.put("inputHash", command.inputHash());
        return json(value);
    }

    private String responseSummary(String resultCode, String message, String effectResult,
                                   boolean reconciliationRequired) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("resultCode", resultCode);
        value.put("message", message);
        value.put("effectResult", effectResult);
        value.put("reconciliationRequired", reconciliationRequired);
        return json(value);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("即时回执 JSON 写入失败", ex);
        }
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean result ? result : fallback;
    }

    private static boolean requiresReconciliation(String resultCode) {
        return "RESULT_UNKNOWN".equals(resultCode) || "RESPONSE_TIMEOUT".equals(resultCode)
            || "FAILED_HOLD".equals(resultCode);
    }

    private static String queryUrl(long scheduleId, String commandKey) {
        return "/process/schedule/" + scheduleId + "/commands/" + commandKey;
    }

    private static String limit(String value) {
        if (value == null || value.length() <= REASON_LIMIT) {
            return value;
        }
        return value.substring(0, REASON_LIMIT);
    }
}
