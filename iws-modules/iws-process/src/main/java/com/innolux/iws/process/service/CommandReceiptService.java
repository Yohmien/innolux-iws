/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 命令回执锚点、重投与异参冲突；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Service;

/**
 * 复用 action_audit 的唯一 receipt_key 保存命令回执。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class CommandReceiptService {

    private final ProcessAuditService auditService;

    /**
     * 构造回执服务。
     *
     * @param auditService 审计服务
     */
    public CommandReceiptService(ProcessAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 查找既有回执。
     *
     * @param action 动作
     * @param target 目标
     * @param commandKey 命令键
     * @return 回执，未找到返回 null
     */
    public ProcessActionAudit find(ActionType action, String target, String commandKey) {
        if (commandKey == null || commandKey.isBlank()) {
            throw new ServiceException("commandKey 不能为空");
        }
        return auditService.findByReceiptKey(key(action, target, commandKey));
    }

    /**
     * 校验同 key 请求摘要一致。
     *
     * @param audit 既有回执
     * @param payloadHash 当前请求摘要
     */
    public void checkSamePayload(ProcessActionAudit audit, String payloadHash) {
        if (!payloadHash.equals(audit.getPayloadSummary())) {
            throw new ServiceException("同一 commandKey 的参数不同，拒绝复用原命令");
        }
    }

    /**
     * 回执键。
     *
     * @param action 动作
     * @param target 目标
     * @param commandKey 命令键
     * @return 稳定回执键
     */
    public String key(ActionType action, String target, String commandKey) {
        String value = action.name() + ":" + target + ":" + ProcessText.required(commandKey, "commandKey");
        return value.length() <= 256 ? value : ProcessText.sha256(value);
    }

    /**
     * 计算请求摘要。
     *
     * @param payload 请求对象
     * @return 稳定摘要
     */
    public String payloadHash(Object payload) {
        return ProcessText.sha256(String.valueOf(payload));
    }
}
