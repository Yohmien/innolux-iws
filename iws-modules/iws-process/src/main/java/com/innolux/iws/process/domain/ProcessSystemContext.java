/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例冻结的逐系统模式与首次确认事实。；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.innolux.iws.process.enums.PostingMode;
import java.time.LocalDateTime;

/**
 * 实例冻结的逐系统模式与首次确认事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iws_process_system_context")
public class ProcessSystemContext extends BaseEntity {

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
     * 系统编码。
     */
    private String systemCode;

    /**
     * 冻结模式。
     */
    private PostingMode postingMode;

    /**
     * 是否已首次确认。
     */
    private Boolean firstConfirmed;

    /**
     * 确认人 ID。
     */
    private Long confirmedBy;

    /**
     * 确认人名称。
     */
    private String confirmedName;

    /**
     * 确认时间。
     */
    private LocalDateTime confirmedAt;

    /**
     * 确认事实版本。
     */
    private Long revision;

}
