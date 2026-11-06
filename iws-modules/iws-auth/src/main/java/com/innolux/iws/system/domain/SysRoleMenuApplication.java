/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 应用维度的角色菜单授权；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色、应用和菜单关联 sys_role_menu_application。
 */
@Data
@TableName("sys_role_menu_application")
public class SysRoleMenuApplication {

    /**
     * 角色ID。
     */
    @TableId(type = IdType.INPUT)
    private Long roleId;

    /**
     * 统一身份应用标识。
     */
    private String applicationId;

    /**
     * 菜单ID。
     */
    private Long menuId;
}
