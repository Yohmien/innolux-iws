/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : INNOLUX IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 标记角色已显式配置应用独立菜单；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色应用菜单作用域 sys_role_menu_scope。
 */
@Data
@TableName("sys_role_menu_scope")
public class SysRoleMenuScope {

    /**
     * 角色ID。
     */
    @TableId(type = IdType.INPUT)
    private Long roleId;

    /**
     * 统一身份应用标识。
     */
    private String applicationId;
}
