/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 仓库主档实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 仓库主档实体。
 *
 * <p>仓库类型配置版本列采用权威 P1 名称 {@code type_config_version_id}。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_warehouse")
public class WmsWarehouse extends BaseEntity {

    @TableId("warehouse_id")
    private Long warehouseId;

    private Long companyId;

    private String warehouseCode;

    private String warehouseName;

    private String warehouseTypeCode;

    private Long typeConfigVersionId;

    private String lifecycleStatus;

    private Integer enabled;

    private Long revision;

    private String remark;
}
