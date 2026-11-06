/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 应用维度角色菜单授权数据层；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.system.domain.SysRoleMenuApplication;

import java.util.Collection;

/**
 * 角色应用菜单关联数据层。
 */
public interface SysRoleMenuApplicationMapper
    extends BaseMapperPlus<SysRoleMenuApplication, SysRoleMenuApplication> {

    /**
     * 删除菜单对应的全部应用维度关联。
     *
     * @param menuIds 菜单ID列表
     * @return 删除数量
     */
    default int deleteByMenuIds(Collection<Long> menuIds) {
        return this.lambda().in(SysRoleMenuApplication::getMenuId, menuIds).deleteCount();
    }
}
