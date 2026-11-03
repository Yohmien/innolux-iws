/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 一个不可拆单元的完整只读分配请求；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

/** 一个分配单元的完整只读请求。 */
public record AllocationRequest(AllocationDemand demand, AllocationPolicy policy) {
    public AllocationRequest {
        if (demand == null || policy == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_REQUEST_NULL);
        }
    }
}
