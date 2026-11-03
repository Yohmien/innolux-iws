/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 以显式子父列表表达装载关系并保留重复项供校验；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

/** 一条 child -> parent 装载关系；重复项和重复父不在此静默折叠。 */
public record LoadEdge(long childId, long parentId) {
    public LoadEdge {
        if (childId <= 0 || parentId <= 0) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_EDGE_INVALID);
        }
    }
}
