/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 储位五项地址精确定位请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 储位五项地址精确定位请求。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsStorageLocateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long warehouseId;

    private List<WmsStorageAddressFieldBo> addressFields;
}
