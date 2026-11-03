/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则发布目标范围及物理父链投影；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import lombok.Data;

/**
 * 规则发布目标范围及物理父链投影。
 *
 * <p>只包含范围校验所需字段；库区字段使用独立投影 {@link WmsRuleScopeZoneRow}。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleScopeRow {

    private Long companyId;

    private String companyLifecycleStatus;

    private Long warehouseId;

    private Long warehouseCompanyId;

    private String warehouseLifecycleStatus;

    private Long zoneId;

    private Long spaceId;

    private Long spaceWarehouseId;

    private String spaceLifecycleStatus;

    private String spaceDelFlag;

    private Long deviceZoneParentZoneId;
}
