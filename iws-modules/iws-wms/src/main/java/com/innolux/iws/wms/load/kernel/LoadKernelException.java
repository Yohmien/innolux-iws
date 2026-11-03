/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 集中装载树稳定错误码与中文提示，供纯内核调用方处理；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

/** 装载树输入或拓扑不满足纯内核合同时抛出；调用方负责映射其自身的传输协议。 */
public final class LoadKernelException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final Code code;

    private LoadKernelException(Code code) {
        super(code.name() + ": " + code.message);
        this.code = code;
    }

    static LoadKernelException failure(Code code) {
        return new LoadKernelException(code);
    }

    public String code() {
        return code.name();
    }

    public String chineseMessage() {
        return code.message;
    }

    /** 错误码属于稳定内核合同；新增语义时只追加，不复用既有名称。 */
    public enum Code {
        LOAD_INPUT_NULL("装载树输入不能为空"),
        LOAD_LIMIT_INVALID("装载树深度限额必须为正整数"),
        LOAD_NODE_INVALID("装载节点必须具有正整数ID、唯一类型以及恰一个库存或载具身份"),
        LOAD_NODE_DUPLICATE("装载节点身份必须唯一"),
        LOAD_EDGE_INVALID("装载关系必须具有正整数子节点和父节点ID"),
        LOAD_EDGE_DUPLICATE("装载关系列表不得重复同一子父关系"),
        LOAD_CHILD_UNKNOWN("装载关系引用了不存在的子节点"),
        LOAD_PARENT_UNKNOWN("装载关系引用了不存在的父节点"),
        LOAD_MULTIPLE_PARENTS("同一节点最多只能有一个父节点"),
        LOAD_SELF_PARENT("装载节点不能以自身为父节点"),
        LOAD_CYCLE("装载关系禁止成环"),
        LOAD_MATERIAL_HAS_CHILD("物料节点不能拥有子节点"),
        LOAD_DEPTH_EXCEEDED("装载树嵌套深度超过限额，整组拒绝"),
        LOAD_NODE_UNKNOWN("请求的节点不属于当前装载树"),
        LOAD_LEAF_INPUT_NULL("物料叶输入不能为空"),
        LOAD_LEAF_INVALID("物料叶输入必须具有正整数节点ID、非空属性和数量"),
        LOAD_LEAF_DUPLICATE("物料叶输入不得重复"),
        LOAD_LEAF_UNKNOWN("物料叶输入必须是当前装载树中的有效物料叶"),
        LOAD_LEAF_INPUT_MISSING("当前装载树的每个有效物料叶都必须提供输入"),
        LOAD_QUANTITY_INVALID("物料数量必须非负且符合decimal(20,6)精度"),
        LOAD_ATTRIBUTE_INVALID("属性事实必须具有名称、合法状态以及匹配的值列表"),
        LOAD_ATTRIBUTE_DUPLICATE("同一节点同一命名空间的属性不得重复"),
        LOAD_CARRIER_ATTRIBUTE_INVALID("载具自身属性只能关联当前装载树中的载具节点"),
        LOAD_QUANTITY_GROUP_UNKNOWN("数量汇总必须具有非空白单位和规则包版本"),
        LOAD_ATTRIBUTE_TYPE_CONFLICT("同一属性的非空值类型不一致，整组拒绝");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
