/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公共审计
 * @Version      : V1.0
 * @Description  : WMS 受控命令尝试与幂等事实实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WMS 受控命令尝试与幂等事实实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@TableName("wms_command_attempt")
public class WmsCommandAttempt {

    @TableId("attempt_id")
    private Long attemptId;

    private String commandKey;

    private String action;

    private String objectType;

    private Long objectId;

    private String inputDigest;

    private String inputSummary;

    private String status;

    private String errorCode;

    private String errorMessage;

    private String factType;

    private String factId;

    private Long actorUserId;

    private String actorName;

    private Long deptId;

    private String deptName;

    private String initiatorName;

    private String executionSubject;

    private String executionSubjectType;

    private String executionAccount;

    private LocalDateTime occurredAt;

    private LocalDateTime recordedAt;
}
