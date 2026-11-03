/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 仓库只读视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 仓库只读视图。
 *
 * <p>ID 字段使用 {@link Long}，由框架统一序列化为字符串。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsWarehouseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
