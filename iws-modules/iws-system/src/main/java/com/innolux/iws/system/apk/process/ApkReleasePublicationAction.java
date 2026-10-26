/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布 SAME_TX 本地动作；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 在 process 同事务中调用 APK 发布业务端口。
 *
 * <p>动作不发送 HTTP、不读取对象存储，也不回写 process 事件表。发布 ID 只取自
 * 当前实例的持久化 {@code businessId}。证据和检查点作为动作结果返回，
 * 由平台尝试记录在同一事务中持久化。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkReleasePublicationAction implements LocalAction {

    private static final int DIGEST_LENGTH = 16;

    private final ApkReleasePublicationPort publicationPort;
    private final com.innolux.iws.process.mapper.ProcessInstanceMapper instanceMapper;

    /**
     * 构造本地动作。
     *
     * @param publicationPort 业务发布端口
     */
    public ApkReleasePublicationAction(ApkReleasePublicationPort publicationPort,
            com.innolux.iws.process.mapper.ProcessInstanceMapper instanceMapper) {
        this.publicationPort = publicationPort;
        this.instanceMapper = instanceMapper;
    }

    /**
     * 描述唯一本地动作。
     *
     * @return SAME_TX 且可核对检查点的动作描述
     */
    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(ApkProcessContract.LOCAL_ACTION_KEY,
            ApkProcessContract.VERSION, "APK 发布", "SAME_TX", true);
    }

    /**
     * 执行一次 APK 发布；只调用业务端口。
     *
     * @param context 平台只读上下文
     * @return 成功或确定失败结果
     */
    @Override
    public ActionResult execute(ActionContext context) {
        if (context == null) {
            throw new ServiceException("APK 发布动作上下文不能为空");
        }
        if (context.direction() != EventDirection.INTERNAL) {
            throw new ServiceException("APK 发布动作只允许 INTERNAL 事件");
        }
        if (!ApkProcessContract.LOCAL_EFFECT_KEY.equals(context.localEffectKey())) {
            throw new ServiceException("APK 发布动作本地效果键不匹配: " + context.localEffectKey());
        }
        var instance = instanceMapper.selectById(context.instanceId());
        if (instance == null || !ApkProcessContract.BUSINESS_TYPE.equals(instance.getBusinessType())) {
            throw new ServiceException("APK 发布计划实例不存在或业务类型不匹配");
        }
        long releaseId = positiveLong(ProcessText.required(instance.getBusinessId(), "businessId"), "businessId");
        if (context.instanceId() <= 0) {
            throw new ServiceException("APK 发布计划实例 ID 必须为正整数");
        }
        String inputHash = ProcessText.required(context.inputHash(), "inputHash");
        String digest = ProcessText.sha256(inputHash).substring(0, DIGEST_LENGTH);
        String evidenceRef = ApkProcessContract.EVIDENCE_PREFIX + releaseId + ":" + digest;
        // 端口异常必须冒泡：SAME_TX 事务可能已被标记 rollback-only，吞掉后再提交会掩盖真实回滚。
        publicationPort.publish(releaseId, context.instanceId());
        requirePublished(releaseId, context.instanceId());
        return new ActionResult(EffectResult.SUCCEEDED, evidenceRef, null, null, null, null,
            checkpoint("PUBLISHED", releaseId, context.instanceId(), context.attemptId(), digest),
            "APK 发布完成");
    }

    private void requirePublished(long releaseId, long planInstanceId) {
        Optional<ApkPublicationFact> found = publicationPort.findByPlanInstanceId(planInstanceId);
        if (found == null || found.isEmpty()) {
            throw new ServiceException("APK 发布后未读取到业务发布事实");
        }
        ApkPublicationFact fact = found.get();
        if (!fact.published() || fact.releaseId() != releaseId || fact.planInstanceId() != planInstanceId) {
            throw new ServiceException("APK 业务发布事实与计划实例不一致");
        }
    }

    private static String checkpoint(String status, long releaseId, long planInstanceId, long attemptId,
                                     String digest) {
        return String.format(Locale.ROOT,
            "{\"status\":\"%s\",\"releaseId\":%d,\"planInstanceId\":%d,\"attemptId\":%d,"
                + "\"inputHashDigest\":\"%s\"}",
            status, releaseId, planInstanceId, attemptId, digest);
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new ServiceException(name + " 必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new ServiceException(name + " 必须为数字: " + value);
        }
    }

}
