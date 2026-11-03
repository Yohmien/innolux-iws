/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : EVALUATE、RESERVE 与 VERIFY 的分配锁位领域入口；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.service;

import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;

/** 分配锁位短事务入口。 */
public interface WmsAllocationReservationService {

    String ERROR_INPUT_INVALID = "ALLOCATION_INPUT_INVALID";

    String ERROR_EFFECT_KEY_CONFLICT = "ALLOCATION_EFFECT_KEY_CONFLICT";

    String ERROR_LOCK_DOMAIN_CHANGED = "ALLOCATION_LOCK_DOMAIN_CHANGED";

    String ERROR_VERIFY_FAILED = "ALLOCATION_VERIFY_FAILED";

    /**
     * 在调用者事务内冻结候选、排序和解释，不取得通道或储位锁。
     *
     * @param command 分配锁位命令
     * @return 已持久或幂等回放的评估结果
     */
    WmsAllocationReservationResult evaluate(WmsAllocationReserveCommand command);

    /**
     * 在调用者事务内锁通道/储位、重读实时账、生成候选并提交锁位。
     *
     * @param command 分配锁位命令
     * @return 已提交或幂等回放的持久结果
     */
    WmsAllocationReservationResult reserve(WmsAllocationReserveCommand command);

    /**
     * 只读回读同一效果键的决策、选中候选和活动锁。
     *
     * @param effectKey 决策效果键
     * @return 持久结果
     */
    WmsAllocationReservationResult verify(String effectKey);
}
