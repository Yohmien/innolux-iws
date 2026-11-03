/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 内部新挂载准入校验结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

/**
 * 内部新挂载准入校验结果。
 *
 * @param businessTypeCode 业务类型编码
 * @param planTypeCode     计划类型编码
 * @param scheduleCode     已解析的调度业务编码
 * @param contractVersion  已校验的合同版本
 * @author YONGJIE.PENG
 */
public record WmsInventoryMountValidationVo(String businessTypeCode, String planTypeCode,
                                            String scheduleCode, String contractVersion) {
}
