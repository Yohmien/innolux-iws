/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司管理角色范围和独立修订展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司管理角色范围和独立修订展示对象。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsCompanyManagementScopeVo {

    private Long companyId;

    private Long scopeRevision;

    private List<Long> roleIds = new ArrayList<>();

    private List<WmsCompanyRoleVo> roles = new ArrayList<>();

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
