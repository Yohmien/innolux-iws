/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 无效果或对账后无效果证据检查、本任务锁释放和终态核验；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.service;

import com.innolux.iws.wms.allocation.release.domain.WmsAllocationEvidenceResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCheckCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseVerifyCommand;

/** P5 分配锁释放能力。 */
public interface WmsAllocationReleaseService {

    String ERROR_INPUT_INVALID = "ALLOCATION_RELEASE_INPUT_INVALID";

    String ERROR_EVIDENCE_INVALID = "ALLOCATION_RELEASE_EVIDENCE_INVALID";

    String ERROR_EVIDENCE_CHANGED = "ALLOCATION_RELEASE_EVIDENCE_CHANGED";

    String ERROR_EFFECT_CONFLICT = "ALLOCATION_RELEASE_EFFECT_CONFLICT";

    String ERROR_STATE_CONFLICT = "ALLOCATION_RELEASE_STATE_CONFLICT";

    String ERROR_ALREADY_CONSUMED = "ALLOCATION_ALREADY_CONSUMED";

    String ERROR_VERIFY_FAILED = "ALLOCATION_RELEASE_VERIFY_FAILED";

    /**
     * 锁定待释放集合并冻结无外部效果证据。
     *
     * @param command 检查命令
     * @return 可写入计划 checkpoint 的证据
     */
    WmsAllocationEvidenceResult checkEvidence(WmsAllocationReleaseCheckCommand command);

    /**
     * 按同一证据和锁域锁序释放本任务份额。
     *
     * @param command 释放命令
     * @return 释放事实
     */
    WmsAllocationReleaseResult release(WmsAllocationReleaseCommand command);

    /**
     * 回读释放流水、分配锁和通道锁终态。
     *
     * @param command 核验命令
     * @return 已提交释放事实
     */
    WmsAllocationReleaseResult verifyRelease(WmsAllocationReleaseVerifyCommand command);
}
