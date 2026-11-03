/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 业务类型目录只读行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

import lombok.Data;

/**
 * 业务类型目录只读行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsBusinessTypeRow {

    private String businessTypeCode;

    private String businessTypeName;

    private String status;

    private String contractVersion;

    private String objectPolicyKey;

    private Integer revision;
}
