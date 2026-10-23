package com.innolux.iws.process.support;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 拒绝事实使用独立事务，不与已回滚的命令共享提交结果。 */
@Service
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessCommandRejectionAudit {
    private final ProcessActionAuditMapper mapper;

    public ProcessCommandRejectionAudit(ProcessActionAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void rejected(String action, String path, String category) {
        ProcessActionAudit audit = new ProcessActionAudit();
        audit.setActionCode(action);
        audit.setActionSource(ActionSource.USER);
        audit.setResult(AuditResult.REJECTED);
        audit.setEntryPath(path);
        audit.setReason(category);
        audit.setOccurredAt(LocalDateTime.now());
        Object actor = StpUtil.getLoginIdDefaultNull();
        if (actor != null) {
            audit.setActorName(actor.toString());
            try {
                audit.setActorUserId(Long.valueOf(actor.toString()));
            } catch (NumberFormatException ignored) {
                // 非数字身份保留名称，不伪造用户主键。
            }
        }
        mapper.insert(audit);
    }
}
