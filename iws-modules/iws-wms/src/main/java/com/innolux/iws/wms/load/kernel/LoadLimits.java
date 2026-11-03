/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 提供独立于作业批量配置的装载树深度限额；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

/** 根节点深度为 1；调用方可显式传入其他正整数限额。 */
public record LoadLimits(int maxDepth) {
    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final LoadLimits DEFAULT = new LoadLimits(DEFAULT_MAX_DEPTH);

    public LoadLimits {
        if (maxDepth < 1) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_LIMIT_INVALID);
        }
    }
}
