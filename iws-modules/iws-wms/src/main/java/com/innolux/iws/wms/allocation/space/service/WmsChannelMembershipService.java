/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道成员共锁
 * @Version      : V1.0
 * @Description  : 旧新通道与储位共锁的普通通道成员变更能力；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.service;

import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeAddCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeRemoveCommand;

/** 普通储位通道成员变更能力。 */
public interface WmsChannelMembershipService {

    String ERROR_INPUT_INVALID = "CHANNEL_MEMBERSHIP_INPUT_INVALID";

    String ERROR_MEMBERSHIP_CONFLICT = "CHANNEL_MEMBERSHIP_CONFLICT";

    String ERROR_CHANNEL_BUSY = "CHANNEL_BUSY";

    String ERROR_EVIDENCE_UNKNOWN = "EVIDENCE_UNKNOWN";

    String ERROR_CONFIGURATION_CONFLICT = "CHANNEL_CONFIGURATION_CONFLICT";

    String ERROR_EDGE_CONFLICT = "CHANNEL_ACCESS_EDGE_CONFLICT";

    /**
     * 在旧/新通道和储位共锁后变更普通通道成员。
     *
     * @param command 成员变更命令
     * @return 变更结果
     */
    WmsChannelMembershipChangeResult change(WmsChannelMembershipChangeCommand command);

    /**
     * 在通道、全部当前成员储位和活动资源共锁后批量调整访问顺序。
     *
     * @param command 访问顺序变更命令
     * @return 变更结果
     */
    WmsChannelAccessOrderChangeResult reorder(WmsChannelAccessOrderChangeCommand command);

    /**
     * 在通道、全部成员储位和活动资源共锁后变更通道定义。
     *
     * @param command 通道定义变更命令
     * @return 变更结果
     */
    WmsChannelDefinitionChangeResult changeDefinition(
        WmsChannelDefinitionChangeCommand command);

    /**
     * 在相关通道、两侧储位和活动资源共锁后新增阻挡边。
     *
     * @param command 阻挡边新增命令
     * @return 变更结果
     */
    WmsStorageAccessEdgeChangeResult addEdge(WmsStorageAccessEdgeAddCommand command);

    /**
     * 在相关通道、两侧储位和活动资源共锁后删除阻挡边。
     *
     * @param command 阻挡边删除命令
     * @return 变更结果
     */
    WmsStorageAccessEdgeChangeResult removeEdge(WmsStorageAccessEdgeRemoveCommand command);
}
