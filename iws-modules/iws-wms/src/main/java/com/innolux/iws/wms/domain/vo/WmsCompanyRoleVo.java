/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司管理范围角色展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公司管理范围角色展示对象。
 *
 * @author YONGJIE.PENG
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WmsCompanyRoleVo {

    private Long roleId;

    private String roleName;
}
