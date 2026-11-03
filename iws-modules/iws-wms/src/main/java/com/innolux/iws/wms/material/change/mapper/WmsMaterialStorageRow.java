/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 读取目标储位身份和最小可写门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.mapper;

import lombok.Data;

/**
 * 目标储位行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialStorageRow {

    private Long storageId;

    private String storageCode;

    private Long zoneId;

    private String zoneCode;

    private Integer status;

    private Integer lockStatus;

    private Integer disableStatus;

    private String delFlag;
}
