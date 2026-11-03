/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 定义只供内部领域事务调用的物料 trace 追加与读取合同；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.service;

import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceAppendCommand;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;

import java.util.Optional;

/**
 * 内部物料 trace 服务。
 *
 * @author YONGJIE.PENG
 */
public interface WmsMaterialTraceService {

    String ERROR_EFFECT_KEY_CONFLICT = "MATERIAL_TRACE_EFFECT_KEY_CONFLICT";

    String ERROR_INVALID_COMMAND = "MATERIAL_TRACE_INVALID_COMMAND";

    String ERROR_QUANTITY_INVALID = "MATERIAL_TRACE_QUANTITY_INVALID";

    String ERROR_EDGE_INVALID = "MATERIAL_TRACE_EDGE_INVALID";

    String ERROR_COMPENSATION_INVALID = "MATERIAL_TRACE_COMPENSATION_INVALID";

    String EFFECT_RESULT_APPLIED = "APPLIED";

    /**
     * 在调用者现有事务内追加完整且不可变的成功物料事实。
     *
     * @param command 追加命令
     * @return 已写入完整快照
     */
    WmsMaterialTraceSnapshot append(WmsMaterialTraceAppendCommand command);

    /**
     * 按效果键读取已写入不可变快照。
     *
     * @param effectKey 领域效果键
     * @return 快照；不存在时为空
     */
    Optional<WmsMaterialTraceSnapshot> findByEffectKey(String effectKey);

    /**
     * 按 trace ID 读取已写入不可变快照。
     *
     * @param traceId trace ID
     * @return 快照；不存在时为空
     */
    Optional<WmsMaterialTraceSnapshot> findByTraceId(long traceId);
}
