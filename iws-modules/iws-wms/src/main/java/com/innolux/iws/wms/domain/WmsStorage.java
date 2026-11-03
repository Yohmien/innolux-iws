/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 储位实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 储位实体。
 *
 * <p>S1 允许 {@code warehouse_id} 暂为空；本只读模型不把空值推断为任何公司或仓库。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_storage")
public class WmsStorage extends BaseEntity {

    @TableId("storage_id")
    private Long storageId;

    private String storageCode;

    private Long zoneId;

    private Integer storageType;

    private Integer heightType;

    private String heightDesc;

    private Integer rowNo;

    private Integer colNo;

    private Integer levelNo;

    private BigDecimal xCoordinate;

    private BigDecimal yCoordinate;

    private String portCode;

    private String wcsPointCode;

    private Integer status;

    private Integer lockStatus;

    private Integer disableStatus;

    private String lifecycleStatus;

    private Integer version;

    @TableLogic
    private String delFlag;

    private String remark;

    private Long warehouseId;

    private String aisleText;

    private String rowText;

    private String levelText;

    private String columnText;

    private String depthText;
}
