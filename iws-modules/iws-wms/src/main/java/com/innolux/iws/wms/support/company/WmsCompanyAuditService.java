/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公共审计
 * @Version      : V1.1
 * @Description  : 公司成功审计、命令幂等尝试和失败尝试独立留证；
 * @ModifyRecord : V1.0 新建；V1.1 失败留证事务取得直接写入共享锚点；
 */

package com.innolux.iws.wms.support.company;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import com.innolux.iws.wms.domain.WmsObjectAudit;
import com.innolux.iws.wms.mapper.WmsCommandAttemptMapper;
import com.innolux.iws.wms.mapper.WmsObjectAuditMapper;
import com.innolux.iws.wms.support.schema.WmsDirectWriteSchemaGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 公司成功审计与命令尝试留证服务。
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsCompanyAuditService {

    private final WmsObjectAuditMapper auditMapper;

    private final WmsCommandAttemptMapper attemptMapper;

    private final WmsDirectWriteSchemaGate directWriteSchemaGate;

    /**
     * 计算命令输入的稳定摘要。
     *
     * @param input 归一化命令输入
     * @return SHA-256 摘要
     */
    public String digest(Object input) {
        String json = JsonUtils.toJsonString(input);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    /**
     * 复核命令键是否可执行。
     *
     * @param commandKey 命令键
     * @param digest     当前输入摘要
     * @return true=首次执行，false=相同成功命令已生效
     */
    public boolean assertReusable(String commandKey, String digest, String action) {
        WmsCommandAttempt existing = findAttempt(commandKey);
        if (existing == null) {
            return true;
        }
        if (!StrUtil.equals(existing.getInputDigest(), digest)
            || !StrUtil.equals(existing.getAction(), attemptAction(action))) {
            throw new ServiceException(
                "INPUT_HASH_CONFLICT: 命令键已绑定不同输入",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (WmsCompanyContract.ATTEMPT_APPLIED.equals(existing.getStatus())) {
            return false;
        }
        int statusCode = WmsCompanyContract.ATTEMPT_REJECTED.equals(existing.getStatus())
            ? HttpStatus.HTTP_FORBIDDEN : HttpStatus.HTTP_CONFLICT;
        throw new ServiceException(
            StrUtil.blankToDefault(existing.getErrorCode(), "COMMAND_ALREADY_FAILED")
                + ": " + existing.getErrorMessage(),
            statusCode
        );
    }

    /**
     * 读取命令键对应的尝试记录，供提交结果未知时安全复核。
     *
     * @param commandKey 命令键
     * @return 尝试记录，不存在时为 null
     */
    public WmsCommandAttempt findAttempt(String commandKey) {
        return attemptMapper.selectByCommandKey(commandKey);
    }

    /**
     * 在同一业务事务内写入成功审计和成功 attempt。
     *
     * @param action     动作
     * @param objectId   公司ID
     * @param commandKey 命令键
     * @param input      命令输入
     * @param before     变更前摘要
     * @param after      变更后摘要
     * @param reason     业务原因
     * @param occurredAt 业务发生时间
     */
    public void recordSuccess(String action, Long objectId, String commandKey, Object input,
                              Object before, Object after, String reason, LocalDateTime occurredAt) {
        Actor actor = actor();
        String beforeJson = JsonUtils.toJsonString(before);
        String afterJson = JsonUtils.toJsonString(after);

        WmsObjectAudit audit = new WmsObjectAudit();
        audit.setObjectType(WmsCompanyContract.OBJECT_TYPE);
        audit.setObjectId(objectId);
        audit.setAction(action);
        audit.setCommandKey(commandKey);
        audit.setBeforeSummary(limit(beforeJson, 2000));
        audit.setAfterSummary(limit(afterJson, 2000));
        audit.setActorUserId(actor.userId());
        audit.setActorName(limit(actor.name(), 64));
        audit.setInitiatorType(WmsCompanyContract.INITIATOR_USER);
        audit.setInitiatorId(actor.userId() == null ? null : String.valueOf(actor.userId()));
        audit.setInitiatorName(limit(actor.name(), 64));
        audit.setDeptId(actor.deptId());
        audit.setDeptName(limit(actor.deptName(), 128));
        audit.setExecutionSubject(limit(actor.name(), 64));
        audit.setExecutionSubjectType(WmsCompanyContract.INITIATOR_USER);
        audit.setExecutionAccount(limit(actor.account(), 64));
        audit.setResult(WmsCompanyContract.RESULT_SUCCESS);
        audit.setEvidenceJson(limit(
            JsonUtils.toJsonString(Map.of("reason", StrUtil.nullToEmpty(reason))),
            2000
        ));
        audit.setOccurredAt(occurredAt);
        audit.setRecordedAt(LocalDateTime.now());
        auditMapper.insert(audit);

        WmsCommandAttempt attempt = attempt(action, objectId, commandKey, input, actor, occurredAt);
        attempt.setStatus(WmsCompanyContract.ATTEMPT_APPLIED);
        attempt.setFactType("WMS_OBJECT_AUDIT");
        attempt.setFactId(String.valueOf(audit.getAuditId()));
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(
                "COMMAND_KEY_CONFLICT: 同一命令已并发生效",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    /**
     * 在业务事务回滚后独立记录失败尝试。
     *
     * <p>失败留证保持 best-effort 语义，但独立事务先取得同一直接写入共享锚点，
     * 使冻结等待已开始的留证事务；冻结完成后不再写入 late attempt。</p>
     *
     * @param action     动作
     * @param objectId   公司ID
     * @param commandKey 命令键
     * @param input      命令输入
     * @param error      原始业务异常
     * @param occurredAt 业务发生时间
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(String action, Long objectId, String commandKey, Object input,
                              RuntimeException error, LocalDateTime occurredAt) {
        if (StrUtil.isBlank(commandKey)) {
            return;
        }
        try {
            directWriteSchemaGate.acquire();
            if (attemptMapper.selectByCommandKey(commandKey) != null) {
                return;
            }
            Actor actor = actor();
            String errorCode = resolveErrorCode(error);
            WmsCommandAttempt attempt = attempt(action, objectId, commandKey, input, actor, occurredAt);
            attempt.setStatus(isRejected(error) ? WmsCompanyContract.ATTEMPT_REJECTED
                : WmsCompanyContract.ATTEMPT_FAILED);
            attempt.setErrorCode(errorCode);
            attempt.setErrorMessage(limit(error.getMessage(), 500));
            attemptMapper.insert(attempt);
        } catch (RuntimeException auditError) {
            log.error("公司失败命令尝试独立留证失败: commandKey={}", commandKey, auditError);
        }
    }

    private WmsCommandAttempt attempt(String action, Long objectId, String commandKey, Object input,
                                      Actor actor, LocalDateTime occurredAt) {
        WmsCommandAttempt attempt = new WmsCommandAttempt();
        attempt.setCommandKey(commandKey);
        attempt.setAction(attemptAction(action));
        attempt.setObjectType(WmsCompanyContract.OBJECT_TYPE);
        attempt.setObjectId(objectId);
        attempt.setInputDigest(digest(input));
        attempt.setInputSummary(limit(JsonUtils.toJsonString(input), 2000));
        attempt.setActorUserId(actor.userId());
        attempt.setActorName(limit(actor.name(), 64));
        attempt.setDeptId(actor.deptId());
        attempt.setDeptName(limit(actor.deptName(), 128));
        attempt.setInitiatorName(limit(actor.name(), 64));
        attempt.setExecutionSubject(limit(actor.name(), 64));
        attempt.setExecutionSubjectType(WmsCompanyContract.INITIATOR_USER);
        attempt.setExecutionAccount(limit(actor.account(), 64));
        attempt.setOccurredAt(occurredAt);
        attempt.setRecordedAt(LocalDateTime.now());
        return attempt;
    }

    private Actor actor() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            return new Actor(null, "SYSTEM", "SYSTEM", "SYSTEM", null);
        }
        String nickname = StrUtil.blankToDefault(loginUser.getNickname(), loginUser.getUsername());
        return new Actor(
            loginUser.getUserId(),
            nickname,
            loginUser.getUsername(),
            loginUser.getDeptName(),
            loginUser.getDeptId()
        );
    }

    private boolean isRejected(RuntimeException error) {
        if (error instanceof NotPermissionException || error instanceof NotLoginException) {
            return true;
        }
        if (error instanceof ServiceException serviceException) {
            return Integer.valueOf(HttpStatus.HTTP_FORBIDDEN).equals(serviceException.getCode());
        }
        return false;
    }

    private String resolveErrorCode(RuntimeException error) {
        if (error instanceof NotPermissionException) {
            return "PERMISSION_DENIED";
        }
        if (error instanceof NotLoginException) {
            return "NOT_LOGGED_IN";
        }
        String message = StrUtil.nullToEmpty(error.getMessage());
        int separator = message.indexOf(':');
        if (separator > 0) {
            String candidate = message.substring(0, separator).trim();
            if (candidate.matches("[A-Z][A-Z0-9_]{2,63}")) {
                return candidate;
            }
        }
        if (error instanceof ServiceException serviceException && serviceException.getCode() != null) {
            return String.valueOf(serviceException.getCode());
        }
        return error.getClass().getSimpleName().toUpperCase();
    }

    private String limit(String value, int maxLength) {
        String text = StrUtil.nullToEmpty(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 将业务动作映射为 attempt 动作编码。
     *
     * @param action 业务动作
     * @return attempt 动作编码
     */
    public String attemptAction(String action) {
        return WmsCompanyContract.ACTION_REPLACE_MANAGEMENT_SCOPE.equals(action)
            ? "COMPANY_SCOPE_REPLACE"
            : "COMPANY_" + action;
    }

    private record Actor(Long userId, String name, String account, String deptName, Long deptId) {
    }
}
