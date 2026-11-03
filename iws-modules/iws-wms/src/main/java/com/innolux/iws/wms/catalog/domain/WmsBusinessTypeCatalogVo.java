/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务类型目录项；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

/**
 * 库存业务类型目录项。
 *
 * @param businessTypeCode 业务类型编码
 * @param businessTypeName 业务类型名称
 * @param status           状态
 * @param contractVersion  业务合同版本
 * @param objectPolicyKey  对象策略键
 * @param revision         修订号
 * @author YONGJIE.PENG
 */
public record WmsBusinessTypeCatalogVo(String businessTypeCode, String businessTypeName, String status,
                                       String contractVersion, String objectPolicyKey, Long revision) {
}
