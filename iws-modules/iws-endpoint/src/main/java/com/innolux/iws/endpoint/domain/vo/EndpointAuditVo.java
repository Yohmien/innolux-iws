/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点配置审计展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 端点配置审计展示对象。
 */
@Data
public class EndpointAuditVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String auditId;

    private String objectType;

    private String objectKey;

    private String environmentCode;

    private String action;

    private String beforeSummary;

    private String afterSummary;

    private String operatorId;

    private String operatorName;

    private String commandKey;

    private String reason;

    private String catalogRevision;

    private String resultRevision;

    private LocalDateTime createTime;
}
