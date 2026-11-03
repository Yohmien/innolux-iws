/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则发布目标实体与公司投影；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import lombok.Data;

/**
 * 规则目标实体及所属公司投影。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleScopeTargetRow {

    private Long scopeId;

    private Long companyId;

    private Long warehouseId;

    private Long parentZoneId;

    private String zoneKind;

    private String delFlag;

}
