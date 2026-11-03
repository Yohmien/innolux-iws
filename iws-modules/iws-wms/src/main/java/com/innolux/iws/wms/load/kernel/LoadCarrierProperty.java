/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 表达与所载物料属性分开汇总的载具自有属性；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

/** 载具自身规格属性；它不会写入或覆盖载具内物料属性命名空间。 */
public record LoadCarrierProperty(long carrierNodeId, LoadAttributeFact property) {
    public LoadCarrierProperty {
        if (carrierNodeId <= 0 || property == null) {
            throw LoadKernelException.failure(
                LoadKernelException.Code.LOAD_CARRIER_ATTRIBUTE_INVALID);
        }
    }
}
