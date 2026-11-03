/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公共审计
 * @Version      : V1.0
 * @Description  : WMS 成功对象变更审计实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WMS 成功对象变更审计实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@TableName("wms_object_audit")
public class WmsObjectAudit {

    @TableId("audit_id")
    private Long auditId;

    private String objectType;

    private Long objectId;

    private String action;

    private String commandKey;

    private String beforeSummary;

    private String afterSummary;

    private Long actorUserId;

    private String actorName;

    private String initiatorType;

    private String initiatorId;

    private Long deptId;

    private String deptName;

    private String initiatorName;

    private String executionSubject;

    private String executionSubjectType;

    private String executionAccount;

    private String result;

    private String evidenceJson;

    private LocalDateTime occurredAt;

    private LocalDateTime recordedAt;
}
