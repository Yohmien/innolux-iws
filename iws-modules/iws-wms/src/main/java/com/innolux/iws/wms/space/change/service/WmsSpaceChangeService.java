/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 定义空间变更持久单及校验、应用、核验事务合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.service;

import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeResult;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeValidation;

/** 空间生命周期事务服务。 */
public interface WmsSpaceChangeService {

    String BUSINESS_TYPE = "WMS_SPACE_CHANGE";

    String PLAN_TYPE_CHANGE = "SPACE_CHANGE";

    String PLAN_TYPE_RETIRE = "SPACE_RETIRE";

    String SCHEDULE_CODE = "SPACE_CONFIG";

    String PERMISSION_CHANGE = "inventory:space:change";

    String ERROR_INVALID = "SPACE_CHANGE_INVALID";

    String ERROR_RETIRE_BLOCKED = "SPACE_RETIRE_BLOCKED";

    String ERROR_VERSION_CONFLICT = "SPACE_CHANGE_VERSION_CONFLICT";

    /**
     * 创建持久空间变更单；由计划入口在同一事务调用。
     *
     * @param command 归一化命令
     * @param inputHash 冻结输入摘要
     * @param inputJson 冻结输入 JSON
     * @return 持久单 ID
     */
    long createOrder(WmsSpaceChangeCommand command, String inputHash, String inputJson);

    /**
     * 校验空间变更并冻结完整配置。
     *
     * @param changeOrderId 持久单 ID
     * @return 校验事实
     */
    WmsSpaceChangeValidation validateWithinTransaction(long changeOrderId);

    /**
     * 原子提交一个空间配置版本和当前生效指针。
     *
     * @param changeOrderId 持久单 ID
     * @return 提交事实
     */
    WmsSpaceChangeResult applyWithinTransaction(long changeOrderId);

    /**
     * 回读当前对象和生效指针。
     *
     * @param changeOrderId 持久单 ID
     * @return 核验事实
     */
    WmsSpaceChangeResult verifyWithinTransaction(long changeOrderId);

    /**
     * 检查退役阻碍。
     *
     * @param changeOrderId 持久单 ID
     * @return 检查事实
     */
    WmsSpaceChangeValidation checkRetirementWithinTransaction(long changeOrderId);

    /**
     * 退役空间身份。
     *
     * @param changeOrderId 持久单 ID
     * @return 退役事实
     */
    WmsSpaceChangeResult retireWithinTransaction(long changeOrderId);
}
