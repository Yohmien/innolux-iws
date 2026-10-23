/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 分支 token 与决策范围协调记录。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;

/**
 * 分支 token 与决策范围协调记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_token")
public class ProcessToken extends BaseEntity {

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 计划实例 ID。
     */
    private Long instanceId;

    /**
     * token 角色。
     */
    private TokenRole tokenRole;

    /**
     * token 元数据状态。
     */
    private TokenStatus tokenStatus;

    /**
     * 已提交状态。
     */
    private String stateCode;

    /**
     * 决策节点编码。
     */
    private String decisionNodeCode;

    /**
     * 父范围键。
     */
    private String parentScopeKey;

    /**
     * 范围键。
     */
    private String scopeKey;

    /**
     * 分支键。
     */
    private String branchKey;

    /**
     * 决策输入摘要。
     */
    private String decisionInputHash;

    /**
     * 决策规则版本。
     */
    private String ruleVersion;

    /**
     * 选中分支集合。
     */
    private String selectedKeys;

    /**
     * 期待分支集合。
     */
    private String expectedKeys;

    /**
     * 已到达分支集合。
     */
    private String joinedKeys;

    /**
     * 汇合屏障是否关闭。
     */
    private Boolean barrierClosed;

    /**
     * 阻塞事件 ID。
     */
    private Long blockedEventId;

    /**
     * 最后尝试 ID。
     */
    private Long lastAttemptId;

    /**
     * token 版本。
     */
    private Long tokenVersion;

    /**
     * 监督轮次号。
     */
    private Long roundNo;

}
