/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 分配候选内核的结构化拒绝原因；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

/** 分配候选输入或合同不满足纯内核要求时抛出；调用方负责映射传输层错误。 */
public final class AllocationKernelException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final Code code;

    private AllocationKernelException(Code code) {
        super(code.name() + ": " + code.message);
        this.code = code;
    }

    static AllocationKernelException failure(Code code) {
        return new AllocationKernelException(code);
    }

    public String code() {
        return code.name();
    }

    public String chineseMessage() {
        return code.message;
    }

    /** 错误码属于稳定内核合同；新增语义只追加，不复用既有名称。 */
    public enum Code {
        ALLOCATION_INPUT_NULL("分配候选输入不能为空"),
        ALLOCATION_LIMIT_INVALID("分配候选限额必须为正整数"),
        ALLOCATION_LIMIT_EXCEEDED("分配候选输入超过工作量限额"),
        ALLOCATION_STORAGE_INVALID("储位候选快照字段非法"),
        ALLOCATION_STORAGE_DUPLICATE("同一冻结快照不得重复储位"),
        ALLOCATION_CAPACITY_INVALID("容量事实必须非负、单位非空且符合decimal(38,12)精度"),
        ALLOCATION_SLOT_INVALID("实体槽事实必须非负且保护量不超过总槽位"),
        ALLOCATION_QUANTITY_INVALID("分配数量必须为正、单位非空且符合decimal(38,12)精度"),
        ALLOCATION_DEMAND_INVALID("不可拆分配单元必须且只能声明容量需求或实体槽需求"),
        ALLOCATION_DEMAND_DUPLICATE("批量分配单元ID不得重复"),
        ALLOCATION_DIMENSION_INVALID("排序维度必须与需求维度精确一致且不得重复"),
        ALLOCATION_REQUEST_NULL("分配请求不能为空");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
