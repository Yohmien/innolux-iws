/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本内单动作事件定义。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.SkipPolicy;

/**
 * 计划版本内单动作事件定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_event_definition")
public class ProcessEventDefinition extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 定义版本 ID。
     */
    private Long definitionVersionId;

    /**
     * 事件节点编码。
     */
    private String eventCode;

    /**
     * 事件中文名。
     */
    private String displayName;

    /**
     * 事件角色。
     */
    private EventRole eventRole;

    /**
     * 事件方向。
     */
    private EventDirection eventDirection;

    /**
     * 事件类型键。
     */
    private String eventTypeKey;

    /**
     * 事件类型版本。
     */
    private String eventTypeVersion;

    /**
     * 调用合同键。
     */
    private String callContractKey;

    /**
     * 调用合同版本。
     */
    private String callContractVersion;

    /**
     * 动作类型。
     */
    private OperationKind operationKind;

    /**
     * 本地动作键。
     */
    private String localActionKey;

    /**
     * 本地动作版本。
     */
    private String localActionVersion;

    /**
     * 逻辑效果键。
     */
    private String effectKey;

    /**
     * 本地效果键。
     */
    private String localEffectKey;

    /**
     * 政策系统编码。
     */
    private String systemCode;

    /**
     * 真实效果系统编码。
     */
    private String targetSystemCode;

    /**
     * 地址引用。
     */
    private String endpointRef;

    /**
     * 调用用途。
     */
    private CallPurpose purpose;

    /**
     * 完成合同。
     */
    private CompletionContract completionContract;

    /**
     * 源状态。
     */
    private String sourceStateCode;

    /**
     * 目标状态。
     */
    private String targetStateCode;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 跳过策略。
     */
    private SkipPolicy skipPolicy;

    /**
     * 跳过合同键。
     */
    private String skipContractKey;

    /**
     * 恢复查询来源事件编码。
     */
    private String recoveryForEventCode;

    /**
     * 事件优先级。
     */
    private Integer priority;

    /**
     * 编辑策略。
     */
    private EditPolicy editPolicy;

    /**
     * 图编辑策略。
     */
    private GraphEditPolicy graphEditPolicy;

    /**
     * 代码锁定字段。
     */
    private String lockedFields;

    /**
     * 说明。
     */
    private String remark;

}
