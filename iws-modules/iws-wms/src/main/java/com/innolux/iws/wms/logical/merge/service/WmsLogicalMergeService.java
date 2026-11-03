/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 定义逻辑归属合并批次的锁内持久化合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.service;

import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeResult;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireResult;

/**
 * 逻辑归属合并持久化服务。
 */
public interface WmsLogicalMergeService {

    String ERROR_EFFECT_CONFLICT = "LOGICAL_MERGE_EFFECT_CONFLICT";

    String ERROR_ASSIGNMENT_CONFLICT = "LOGICAL_MERGE_ASSIGNMENT_CONFLICT";

    String ERROR_TARGET_CHANGED = "LOGICAL_MERGE_TARGET_CHANGED";

    String ERROR_SOURCE_VERSION_MISSING = "LOGICAL_MERGE_SOURCE_VERSION_MISSING";

    String ERROR_HISTORY_INCONSISTENT = "LOGICAL_MERGE_HISTORY_INCONSISTENT";

    String ERROR_CONCURRENCY_RETRY = "LOGICAL_MERGE_CONCURRENCY_RETRY";

    String ERROR_BATCH_EXCEEDS_LIMIT = "LOGICAL_MERGE_BATCH_EXCEEDS_LIMIT";

    String ERROR_SOURCE_NOT_DRAINED = "LOGICAL_MERGE_SOURCE_NOT_DRAINED";

    /**
     * 在同一事务内锁定并迁移物料归属，写入归属历史与批次幂等事实。
     *
     * @param command 冻结合并批次命令
     * @return 首次提交或幂等回放结果
     */
    WmsLogicalMergeResult apply(WmsLogicalMergeCommand command);

    /**
     * 仅在来源版本已无任何物料归属且已停新后执行最终退役。
     *
     * @param command 来源退役命令
     * @return 幂等的退役结果
     */
    WmsLogicalMergeRetireResult retireSources(WmsLogicalMergeRetireCommand command);
}
