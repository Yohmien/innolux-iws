/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 向业务模块提供权威账号、角色和权限只读事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 权威身份事实读取接缝。
 *
 * <p>业务模块只保存角色 ID 和 user_id 引用，不复制账号、角色或菜单表。</p>
 */
public interface IamIdentityFacts {

    /**
     * 读取账号展示事实。
     *
     * @param userId 原账号 ID
     * @return 账号事实，不存在时为 null
     */
    UserFact findUser(Long userId);

    /**
     * 读取部门展示事实。
     *
     * @param deptId 原部门 ID
     * @return 部门事实，不存在时为 null
     */
    DeptFact findDept(Long deptId);

    /**
     * 读取当前有效角色并在事务内共享锁定。
     *
     * @param userId 原账号 ID
     * @return 有效角色 ID
     */
    List<Long> currentRoleIdsForShare(Long userId);

    /**
     * 判断账号当前是否具有菜单权限，不锁定。
     *
     * @param userId     原账号 ID
     * @param permission 菜单权限标识
     * @return 是否有效
     */
    boolean currentPermission(Long userId, String permission);

    /**
     * 判断账号当前是否具有菜单权限，并在事务内共享锁定关系行。
     *
     * @param userId     原账号 ID
     * @param permission 菜单权限标识
     * @return 是否有效
     */
    boolean currentPermissionForShare(Long userId, String permission);

    /**
     * 判断账号当前是否持有超级管理员角色，并共享锁定关系行。
     *
     * @param userId 原账号 ID
     * @return 是否为超级管理员
     */
    boolean superAdminForShare(Long userId);

    /**
     * 共享锁定仍存在的角色。
     *
     * @param roleIds 待校验角色 ID
     * @return 当前仍存在的角色 ID
     */
    List<Long> existingRoleIdsForShare(Collection<Long> roleIds);

    /**
     * 读取角色自定义部门范围，并在业务事务内共享锁定授权关系。
     *
     * @param roleId 角色 ID
     * @return 自定义部门 ID
     */
    List<Long> roleCustomDeptIdsForShare(Long roleId);

    /**
     * 读取部门及全部下级部门，并在业务事务内共享锁定部门事实。
     *
     * @param deptId 部门 ID
     * @return 部门树 ID
     */
    List<Long> deptAndChildIdsForShare(Long deptId);

    /**
     * 读取角色名称。
     *
     * @param roleIds 角色 ID
     * @return roleId 到 roleName 的映射
     */
    Map<Long, String> roleNames(Collection<Long> roleIds);

    /**
     * 账号展示事实。
     *
     * @param userId   原账号 ID
     * @param userName 原登录名
     * @param nickName 原昵称
     */
    record UserFact(Long userId, String userName, String nickName) {
    }

    /**
     * 部门展示事实。
     *
     * @param deptId   原部门 ID
     * @param deptName 原部门名
     */
    record DeptFact(Long deptId, String deptName) {
    }
}
