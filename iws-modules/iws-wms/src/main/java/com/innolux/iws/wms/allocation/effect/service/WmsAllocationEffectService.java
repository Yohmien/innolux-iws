/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 推进下发、回执、未知和消费效果并执行generation门禁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.service;

import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectCommand;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectResult;

/** P5 分配锁外部效果推进能力。 */
public interface WmsAllocationEffectService {

    String ERROR_INPUT_INVALID = "ALLOCATION_EFFECT_INPUT_INVALID";

    String ERROR_GENERATION_CONFLICT = "ALLOCATION_EFFECT_GENERATION_CONFLICT";

    String ERROR_STATE_CONFLICT = "ALLOCATION_EFFECT_STATE_CONFLICT";

    String ERROR_EFFECT_CONFLICT = "ALLOCATION_EFFECT_EFFECT_CONFLICT";

    /**
     * 按期望 generation 推进原分配锁及其通道锁效果。
     *
     * @param command 效果推进命令
     * @return 推进后的 generation 和 checkpoint
     */
    WmsAllocationEffectResult advance(WmsAllocationEffectCommand command);
}
