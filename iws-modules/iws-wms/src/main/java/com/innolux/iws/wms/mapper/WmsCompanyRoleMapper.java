/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司授权使用的数据库角色当前读入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 公司授权使用的数据库角色当前读入口。
 *
 * @author YONGJIE.PENG
 */
public interface WmsCompanyRoleMapper {

    /**
     * 有效角色菜单权限的公共关联片段：角色与菜单均须处于正常状态。
     */
    String EFFECTIVE_PERMISSION_JOIN = """
        from sys_user_role ur
        join sys_role r on r.role_id = ur.role_id and r.status = '0' and r.del_flag = '0'
        join sys_role_menu rm on rm.role_id = r.role_id
        join sys_menu m on m.menu_id = rm.menu_id
        """;

    /**
     * 以共享锁读取当前用户数据库角色，阻止事务期间角色关系被删除。
     *
     * @param userId 用户ID
     * @return 有效角色ID
     */
    @Select("""
        select r.role_id
        from sys_user_role ur
        join sys_role r on r.role_id = ur.role_id
        where ur.user_id = #{userId}
          and r.status = '0'
          and r.del_flag = '0'
        order by r.role_id
        for share
        """)
    List<Long> selectCurrentRoleIdsForShare(@Param("userId") Long userId);

    /**
     * 按数据库当前状态读取用户的有效菜单权限，不锁定。
     *
     * @param userId     用户ID
     * @param permission 权限标识
     * @return 命中时返回 1，否则为 null
     */
    @Select("select 1 " + EFFECTIVE_PERMISSION_JOIN
        + " where ur.user_id = #{userId} and m.status = '0' and m.perms = #{permission} limit 1")
    Integer selectCurrentUserPermission(@Param("userId") Long userId,
                                        @Param("permission") String permission);

    /**
     * 在写入事务内当前读用户的有效菜单权限，避免旧快照绕过已提交的撤权。
     *
     * @param userId     用户ID
     * @param permission 权限标识
     * @return 命中时返回 1，否则为 null
     */
    @Select("select 1 " + EFFECTIVE_PERMISSION_JOIN
        + " where ur.user_id = #{userId} and m.status = '0' and m.perms = #{permission} limit 1 for share")
    Integer selectCurrentUserPermissionForShare(@Param("userId") Long userId,
                                                @Param("permission") String permission);

    /**
     * 以共享锁判断显式用户是否仍持有超级管理员角色。
     *
     * @param userId 用户ID
     * @return 命中时返回 1
     */
    @Select("""
        select 1
        from sys_user_role ur
        join sys_role r on r.role_id = ur.role_id
        where ur.user_id = #{userId}
          and r.role_key = 'superadmin'
          and r.status = '0'
          and r.del_flag = '0'
        limit 1
        for share
        """)
    Integer selectSuperAdminForShare(@Param("userId") Long userId);

    /**
     * 以共享锁读取目标角色行，防止授权提交前角色被并发删除；
     * 与权限核验使用同一锁模式，避免共享锁升级排他锁形成死锁。
     *
     * @param roleIds 角色ID集合
     * @return 当前仍存在且未删除的角色ID
     */
    @Select("""
        <script>
        select role_id
        from sys_role
        where del_flag = '0'
          and role_id in
          <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
            #{roleId}
          </foreach>
        order by role_id
        for share
        </script>
        """)
    List<Long> selectExistingRoleIdsForShare(@Param("roleIds") Collection<Long> roleIds);
}
