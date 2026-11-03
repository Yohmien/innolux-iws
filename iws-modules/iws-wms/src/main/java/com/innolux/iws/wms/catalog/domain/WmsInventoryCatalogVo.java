/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务类型与计划挂载只读目录；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

import java.util.List;

/**
 * 库存业务目录。
 *
 * @param contractVersion 目录合同版本
 * @param businessTypes   业务类型目录
 * @param mounts          计划挂载目录
 * @author YONGJIE.PENG
 */
public record WmsInventoryCatalogVo(String contractVersion, List<WmsBusinessTypeCatalogVo> businessTypes,
                                    List<WmsBusinessPlanMountCatalogVo> mounts) {
}
