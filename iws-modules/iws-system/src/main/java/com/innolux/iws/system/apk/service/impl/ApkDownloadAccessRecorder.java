/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 应用版本管理
 * @Version      : V1.0
 * @Description  : 记录 APK 公开下载的入站请求与领域审计；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.innolux.iws.common.core.utils.ServletUtils;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessRequestDetail;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 记录 APK 公开下载的入站请求与领域审计。
 *
 * <p>下载走匿名公开接口，不属于计划事件触发；这里按入站请求记录落库，
 * 并关联发布版本所在的计划实例，使记录同时出现在请求总览和实例审计中。
 * 记录不写入原始报文，只保存来源、版本与结果摘要。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkDownloadAccessRecorder {

    private static final String RECEIVER_SYSTEM_CODE = "IWS";

    private static final String MODULE_CODE = "APK";

    private static final String CONTRACT_KEY = "APK_DOWNLOAD";

    private static final String CONTRACT_VERSION = "1";

    private static final String ENTRY_PATH = "/app/apk/download";

    private static final int ACCESS_SUMMARY_MAX = 500;

    private static final int REASON_MAX = 1000;

    private static final int SUMMARY_MAX = 2000;

    private static final int USER_AGENT_MAX = 500;

    private final ProcessRequestDetailMapper requestDetailMapper;

    private final ProcessActionAuditMapper auditMapper;

    /**
     * 构造下载访问记录器。
     *
     * @param requestDetailMapper 请求记录 Mapper
     * @param auditMapper         审计 Mapper
     */
    public ApkDownloadAccessRecorder(ProcessRequestDetailMapper requestDetailMapper,
                                     ProcessActionAuditMapper auditMapper) {
        this.requestDetailMapper = requestDetailMapper;
        this.auditMapper = auditMapper;
    }

    /**
     * 写入一次下载访问的请求记录与审计。
     *
     * @param access 下载访问事实
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(Access access) {
        if (access == null) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        String accessSummary = accessSummary(access);
        ProcessRequestDetail detail = new ProcessRequestDetail();
        detail.setRequestId(requestId);
        detail.setDirection(EventDirection.INBOUND);
        detail.setPurpose(CallPurpose.APK_DOWNLOAD);
        detail.setReceiverSystemCode(RECEIVER_SYSTEM_CODE);
        detail.setModuleCode(MODULE_CODE);
        detail.setClientIp(truncate(access.clientIp(), 64));
        detail.setAccessSummary(truncate(accessSummary, ACCESS_SUMMARY_MAX));
        detail.setContractKey(CONTRACT_KEY);
        detail.setContractVersion(CONTRACT_VERSION);
        detail.setEffectKey(truncate(effectKey(access), 256));
        detail.setInstanceId(access.planInstanceId());
        detail.setHttpStatus(access.httpStatus());
        EffectResult effectResult = effectResult(access);
        detail.setResultCode(effectResult.name());
        detail.setEffectResult(effectResult);
        detail.setSent(false);
        detail.setRequestSummary(truncate("匿名下载；" + accessSummary
            + (blank(access.clientIp()) ? "" : "；来源 IP=" + access.clientIp()), SUMMARY_MAX));
        detail.setResponseSummary(truncate(responseSummary(access), SUMMARY_MAX));
        detail.setStartedAt(access.startedAt());
        detail.setEndedAt(access.endedAt());
        detail.setEvidenceAt(access.endedAt());
        requestDetailMapper.insert(detail);

        ProcessActionAudit audit = new ProcessActionAudit();
        audit.setActionCode(ActionType.APK_DOWNLOAD.name());
        audit.setActionSource(ActionSource.SYSTEM);
        audit.setResult(auditResult(effectResult));
        audit.setOccurredAt(access.endedAt());
        audit.setInstanceId(access.planInstanceId());
        audit.setRequestId(requestId);
        audit.setReason(truncate(accessSummary + "；结果=" + resultText(effectResult)
            + (blank(access.clientIp()) ? "" : "；来源 IP=" + access.clientIp())
            + (blank(access.message()) ? "" : ("；原因=" + access.message())), REASON_MAX));
        audit.setClientIp(truncate(access.clientIp(), 64));
        audit.setUserAgent(truncate(access.userAgent(), USER_AGENT_MAX));
        audit.setEntryPath(ENTRY_PATH);
        audit.setRequestStartedAt(access.startedAt());
        audit.setRequestFinishedAt(access.endedAt());
        audit.setDurationMs(Duration.between(access.startedAt(), access.endedAt()).toMillis());
        auditMapper.insert(audit);
    }

    /**
     * 解析当前请求来源信息；非请求线程返回空值。
     *
     * @return 来源信息
     */
    public static Source currentSource() {
        try {
            String ip = ServletUtils.getClientIP();
            String userAgent = ServletUtils.getRequest() == null ? null
                : ServletUtils.getRequest().getHeader("User-Agent");
            return new Source(ip, truncate(userAgent, USER_AGENT_MAX));
        } catch (RuntimeException ex) {
            return new Source(null, null);
        }
    }

    private static String accessSummary(Access access) {
        return "包名=" + text(access.packageName())
            + "；当前版本=" + text(access.currentVersion())
            + "；目标版本=" + text(access.targetVersion());
    }

    private static String responseSummary(Access access) {
        if (!access.success()) {
            String prefix = effectResult(access) == EffectResult.UNKNOWN ? "下载流中断，结果待确认" : "下载失败";
            return prefix + (blank(access.message()) ? "" : "：" + access.message());
        }
        return "下载完成" + (access.fileSize() == null ? "" : "；文件大小=" + access.fileSize())
            + (access.versionCode() == null ? "" : "；目标版本号=" + access.versionCode());
    }

    private static String effectKey(Access access) {
        return "apk-download:" + text(access.packageName()) + ":"
            + (access.versionCode() == null ? text(access.targetVersion()) : access.versionCode());
    }

    private static EffectResult effectResult(Access access) {
        if (access.success()) {
            return EffectResult.SUCCEEDED;
        }
        return access.committed() ? EffectResult.UNKNOWN : EffectResult.FAILED;
    }

    private static AuditResult auditResult(EffectResult effectResult) {
        return switch (effectResult) {
            case SUCCEEDED -> AuditResult.SUCCESS;
            case UNKNOWN -> AuditResult.UNKNOWN;
            default -> AuditResult.FAILED;
        };
    }

    private static String resultText(EffectResult effectResult) {
        return switch (effectResult) {
            case SUCCEEDED -> "成功";
            case UNKNOWN -> "待确认";
            default -> "失败";
        };
    }

    private static String text(String value) {
        return blank(value) ? "未知" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * 当前请求来源。
     *
     * @param clientIp  来源 IP
     * @param userAgent User-Agent
     */
    public record Source(String clientIp, String userAgent) {
    }

    /**
     * 一次下载访问事实。
     *
     * @param packageName    安装包名称
     * @param currentVersion 客户端当前版本
     * @param targetVersion  目标版本
     * @param versionCode    目标版本号
     * @param planInstanceId 发布版本计划实例
     * @param fileSize       文件大小
     * @param success        是否完成下载
     * @param committed      响应是否已提交
     * @param message        失败原因
     * @param httpStatus     HTTP 状态
     * @param clientIp       来源 IP
     * @param userAgent      User-Agent
     * @param startedAt      开始时间
     * @param endedAt        结束时间
     */
    public record Access(String packageName, String currentVersion, String targetVersion, Long versionCode,
                         Long planInstanceId, Long fileSize, boolean success, boolean committed, String message,
                         int httpStatus, String clientIp, String userAgent,
                         LocalDateTime startedAt, LocalDateTime endedAt) {
    }
}
