/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 逻辑归属储位快照实体，对应 wms_logical_scope_storage；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 逻辑归属储位快照实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_logical_scope_storage")
public class WmsLogicalScopeStorage extends BaseEntity {

    @TableId(value = "logical_scope_storage_id", type = IdType.AUTO)
    private Long logicalScopeStorageId;

    private Long scopeVersionId;

    private Long storageId;

    private Integer revision;
}
