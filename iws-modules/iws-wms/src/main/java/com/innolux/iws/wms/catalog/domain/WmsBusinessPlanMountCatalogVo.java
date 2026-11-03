/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务计划挂载目录项；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

/**
 * 库存业务计划挂载目录项。
 *
 * @param mountId         挂载主键
 * @param businessTypeCode 业务类型编码
 * @param planTypeCode     计划类型编码
 * @param planTypeName     计划类型名称
 * @param scheduleCode     调度业务编码
 * @param contractVersion  挂载合同版本
 * @param enabled          是否启用
 * @param revision         修订号
 * @param allowMount       当前是否允许新挂载
 * @author YONGJIE.PENG
 */
public record WmsBusinessPlanMountCatalogVo(Long mountId, String businessTypeCode, String planTypeCode,
                                            String planTypeName, String scheduleCode, String contractVersion,
                                            Boolean enabled, Long revision, Boolean allowMount) {
}
