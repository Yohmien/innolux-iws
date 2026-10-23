/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 追加式领域动作审计；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 领域审计服务。审计记录只追加，不提供更新或删除入口。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessAuditService {

    private final ProcessActionAuditMapper auditMapper;

    /**
     * 构造审计服务。
     *
     * @param auditMapper 审计 Mapper
     */
    public ProcessAuditService(ProcessActionAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 创建带当前操作者和时间的审计记录。
     *
     * @param action 动作
     * @param source 来源
     * @param result 结果
     * @return 审计实体
     */
    public ProcessActionAudit newAudit(ActionType action, ActionSource source, AuditResult result) {
        ProcessActionAudit audit = new ProcessActionAudit();
        audit.setActionCode(action.name());
        audit.setActionSource(source);
        audit.setResult(result);
        audit.setOccurredAt(LocalDateTime.now());
        if (source == ActionSource.USER) {
            try {
                Object loginId = StpUtil.getLoginIdDefaultNull();
                if (loginId != null) {
                    String id = loginId.toString();
                    audit.setActorName(id);
                    try {
                        audit.setActorUserId(Long.valueOf(id));
                    } catch (NumberFormatException ignored) {
                        audit.setActorName(id);
                    }
                }
            } catch (RuntimeException ignored) {
                // 测试或系统线程没有 Sa-Token 上下文。
            }
        }
        return audit;
    }

    /**
     * 追加审计，必须在所属业务事务内调用。
     *
     * @param audit 审计
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(ProcessActionAudit audit) {
        auditMapper.insert(audit);
    }

    /**
     * 按命令回执锚点查询。
     *
     * @param receiptKey 回执键
     * @return 审计，未找到返回 null
     */
    public ProcessActionAudit findByReceiptKey(String receiptKey) {
        return auditMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<ProcessActionAudit>lambdaQuery()
            .eq(ProcessActionAudit::getReceiptKey, receiptKey));
    }
}
