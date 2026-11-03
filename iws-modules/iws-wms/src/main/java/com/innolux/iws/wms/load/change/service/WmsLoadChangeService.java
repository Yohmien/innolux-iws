/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆事实
 * @Version      : V1.0
 * @Description  : 在调用者事务内提交装拆关系、根位置、占用和关系历史；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.service;

import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCheckResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;

/** 装拆事实领域入口。 */
public interface WmsLoadChangeService {

    String ERROR_INVALID_COMMAND = "LOAD_CHANGE_INPUT_INVALID";

    String ERROR_RELATION_CONFLICT = "LOAD_CHANGE_RELATION_CONFLICT";

    String ERROR_CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";

    String ERROR_CAPACITY_MISSING = "LOAD_CHANGE_CAPACITY_MISSING";

    String ERROR_CAPACITY_UNSUPPORTED = "LOAD_CHANGE_CAPACITY_UNSUPPORTED";

    String ERROR_LEDGER_DRIFT = "LOAD_CHANGE_LEDGER_DRIFT";

    String ERROR_ALLOCATION_UNSUPPORTED = "LOAD_CHANGE_ALLOCATION_UNSUPPORTED";

    /**
     * 在调用者事务内提交一次完整装拆效果。
     *
     * @param command 装拆命令
     * @return 持久事实
     */
    WmsLoadChangeResult apply(WmsLoadChangeCommand command);

    /**
     * 在调用者事务内执行只读结构预检。
     *
     * <p>最终提交仍由 {@link #apply(WmsLoadChangeCommand)} 在锁内重新验证容量、
     * 占用和活动锁，不能使用本结果替代最终事实。</p>
     *
     * @param command 装拆命令
     * @return 结构预检结果
     */
    WmsLoadChangeCheckResult check(WmsLoadChangeCommand command);
}
