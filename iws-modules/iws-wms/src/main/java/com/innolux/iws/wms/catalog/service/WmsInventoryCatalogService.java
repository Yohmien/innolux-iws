/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务目录只读查询与新挂载内部校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.service;

import com.innolux.iws.wms.catalog.domain.WmsInventoryCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsInventoryMountValidationVo;

/**
 * 库存业务目录只读查询服务。
 *
 * @author YONGJIE.PENG
 */
public interface WmsInventoryCatalogService {

    /**
     * 查询业务类型与计划挂载目录，包含已停用历史信息。
     *
     * @return 只读目录
     */
    WmsInventoryCatalogVo getCatalog();

    /**
     * 校验新挂载准入；不创建计划实例，不调用 attach。
     *
     * @param businessTypeCode       业务类型编码
     * @param planTypeCode           计划类型编码
     * @param scheduleCode           调度业务编码
     * @param expectedContractVersion 调用方合同版本
     * @return 校验后的挂载身份
     */
    WmsInventoryMountValidationVo requireMountAllowed(String businessTypeCode, String planTypeCode,
                                                      String scheduleCode, String expectedContractVersion);
}
