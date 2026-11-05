/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司功能权限之外的显式角色-公司对象范围策略；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.company;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.domain.WmsCompany;
import com.innolux.iws.wms.domain.WmsCompanyManagementGrant;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import com.innolux.iws.wms.mapper.WmsCompanyMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementGrantMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementScopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 公司显式角色-公司对象范围策略。
 *
 * @author YONGJIE.PENG
 */
@Component
@RequiredArgsConstructor
public class WmsCompanyAccessPolicy {

    private final WmsCompanyManagementScopeMapper scopeMapper;

    private final WmsCompanyManagementGrantMapper grantMapper;

    private final IamIdentityFacts identityFacts;

    private final WmsCompanyMapper companyMapper;

    /**
     * 校验当前用户是否可以创建公司。
     *
     * @param firstCompany 是否为首家公司
     */
    public void checkCreateAccess(boolean firstCompany) {
        if (firstCompany) {
            if (!LoginHelper.isSuperAdmin()) {
                throw new ServiceException(
                    "首家公司只能由现有超级管理员创建",
                    HttpStatus.HTTP_FORBIDDEN
                );
            }
            return;
        }
        checkPermissionForShare(WmsCompanyContract.PERMISSION_CREATE);
        if (!hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL)) {
            throw new ServiceException(
                "创建后续公司要求实时菜单权限 inventory:company:scope:all",
                HttpStatus.HTTP_FORBIDDEN
            );
        }
    }

    /**
     * 锁定公司范围头并以数据库当前授权复核对象访问权限。
     *
     * @param companyId 公司ID
     */
    public void lockAndCheckCompany(Long companyId) {
        lockAndCheckCompany(companyId, LoginHelper.getUserId());
    }

    /**
     * 以显式用户身份锁定公司范围头并复核授权。
     *
     * <p>无 HTTP 会话的受信 worker 使用持久发起人 ID；不得伪造登录，也不得跳过授权。</p>
     *
     * @param companyId 公司ID
     * @param userId    发起人或执行主体用户ID
     */
    public void lockAndCheckCompany(Long companyId, Long userId) {
        WmsCompanyManagementScope scope = scopeMapper.selectByIdForUpdate(companyId);
        if (scope == null) {
            throw new ServiceException("公司管理范围头缺失，拒绝写入");
        }
        if (hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL, userId)) {
            return;
        }
        if (!hasActiveGrantForUpdate(companyId, userId)) {
            throw companyScopeDenied();
        }
    }

    /**
     * 共享锁定公司范围头并以数据库当前授权复核对象访问权限。
     *
     * <p>共享锁允许同一公司的多个只读对象命令并发，同时与范围替换的排他锁形成确定边界。</p>
     *
     * @param companyId 公司ID
     */
    public void lockAndCheckCompanyForShare(Long companyId) {
        lockAndCheckCompanyForShare(companyId, LoginHelper.getUserId());
    }

    /**
     * 以显式用户身份共享锁定公司范围头并复核授权。
     *
     * @param companyId 公司ID
     * @param userId    发起人或执行主体用户ID
     */
    public void lockAndCheckCompanyForShare(Long companyId, Long userId) {
        WmsCompanyManagementScope scope = scopeMapper.selectByIdForShare(companyId);
        if (scope == null) {
            throw companyScopeDenied();
        }
        if (hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL, userId)) {
            return;
        }
        if (!hasActiveGrant(companyId, userId, true)) {
            throw companyScopeDenied();
        }
    }

    /**
     * 锁定公司管理范围头。
     *
     * <p>调用方必须先完成 scope:manage/scope:all 的实时核验，以保证系统权限共享锁先于公司行锁获取。</p>
     *
     * @param companyId 公司ID
     * @return 已锁定范围头
     */
    public WmsCompanyManagementScope lockAndCheckScopeManage(Long companyId) {
        WmsCompanyManagementScope scope = scopeMapper.selectByIdForUpdate(companyId);
        if (scope == null) {
            throw new ServiceException("公司管理范围头缺失，拒绝写入");
        }
        return scope;
    }

    /**
     * 在管理范围替换事务内以共享锁实时核验替换资格。
     */
    public void checkScopeManageForShare() {
        checkPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_MANAGE);
        if (!hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL)) {
            throw new ServiceException(
                "管理范围替换要求实时菜单权限 inventory:company:scope:all",
                HttpStatus.HTTP_FORBIDDEN
            );
        }
    }

    /**
     * 查询管理范围前校验功能权限、全范围资格和公司范围头。
     *
     * @param companyId 公司ID
     * @return 当前范围头
     */
    public WmsCompanyManagementScope checkScopeManage(Long companyId) {
        checkPermission(WmsCompanyContract.PERMISSION_SCOPE_MANAGE);
        if (!hasPermission(WmsCompanyContract.PERMISSION_SCOPE_ALL)) {
            throw new ServiceException(
                "管理范围查询要求实时菜单权限 inventory:company:scope:all",
                HttpStatus.HTTP_FORBIDDEN
            );
        }
        WmsCompanyManagementScope scope = scopeMapper.selectById(companyId);
        if (scope == null) {
            throw new ServiceException("公司管理范围头缺失", HttpStatus.HTTP_NOT_FOUND);
        }
        return scope;
    }

    /**
     * 为分页查询附加当前用户可见公司范围。
     *
     * @param wrapper 公司查询条件
     */
    public void applyCompanyScope(QueryWrapper<WmsCompany> wrapper) {
        applyCompanyScope(wrapper, "wms_company.company_id");
    }

    /**
     * 为任意公司业务 SQL 附加同一套公司管理范围谓词。
     *
     * <p>只把既有授权 EXISTS 谓词中的公司列参数化；不改变公司查询语义，也不表示
     * 库存可见范围或对象 visibility 已实现。{@code companyIdColumn} 必须来自代码常量。</p>
     *
     * @param wrapper         查询条件
     * @param companyIdColumn 公司主键列表达式
     */
    public void applyCompanyScope(QueryWrapper<?> wrapper, String companyIdColumn) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            wrapper.apply("1 = 0");
            return;
        }
        if (hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL, userId)) {
            return;
        }
        List<Long> roleIds = identityFacts.currentRoleIdsForShare(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        // identity 事实在业务事务内保持共享锁；业务库只关联角色 ID，不复制身份表。
        wrapper.apply(companyScopeSql(companyIdColumn, roleIds), roleIds.toArray());
    }

    /**
     * 为仓库从属表附加公司范围；仅复用上面的公司管理范围谓词。
     *
     * @param wrapper           查询条件
     * @param warehouseIdColumn 仓库主键列表达式
     */
    public void applyWarehouseCompanyScope(QueryWrapper<?> wrapper, String warehouseIdColumn) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            wrapper.apply("1 = 0");
            return;
        }
        if (hasPermissionForShare(WmsCompanyContract.PERMISSION_SCOPE_ALL, userId)) {
            return;
        }
        List<Long> roleIds = identityFacts.currentRoleIdsForShare(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.apply(
            warehouseIdColumn + " in (select scope_w.warehouse_id from wms_warehouse scope_w where "
                + companyScopeSql("scope_w.company_id", roleIds) + ")",
            roleIds.toArray()
        );
    }

    /**
     * 读取当前用户可管理的公司集合，供空间数据 readiness 使用。
     *
     * <p>该查询与分页查询共用同一套授权谓词；返回空集合表示没有任何公司可证明
     * 属于当前管理范围。</p>
     *
     * @return 当前可管理公司ID
     */
    public Set<Long> accessibleCompanyIds() {
        QueryWrapper<WmsCompany> wrapper = new QueryWrapper<>();
        wrapper.select("company_id");
        applyCompanyScope(wrapper, "wms_company.company_id");
        return companyMapper.selectList(wrapper).stream()
            .map(WmsCompany::getCompanyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String companyScopeSql(String companyIdColumn, List<Long> roleIds) {
        String placeholders = IntStream.range(0, roleIds.size())
            .mapToObj(index -> "{" + index + "}")
            .collect(Collectors.joining(","));
        return "(exists (select 1 from wms_company_management_grant g"
            + " where g.company_id = " + companyIdColumn
            + " and g.role_id in (" + placeholders + ")))";
    }

    /**
     * 当前登录用户是否具有全公司管理范围。
     *
     * @return true=可跨公司管理
     */
    public boolean hasScopeAll() {
        return hasPermission(WmsCompanyContract.PERMISSION_SCOPE_ALL);
    }

    /**
     * 当前登录用户是否具有管理范围替换资格。
     *
     * @return true=可替换管理范围
     */
    public boolean hasScopeManage() {
        return hasPermission(WmsCompanyContract.PERMISSION_SCOPE_MANAGE);
    }

    /**
     * 按当前登录用户判断是否可读取指定公司的业务对象。
     *
     * @param companyId 公司ID
     * @return true=可读取
     */
    public boolean hasCompanyAccess(Long companyId) {
        if (companyId == null) {
            return false;
        }
        Long userId = LoginHelper.getUserId();
        if (hasPermission(WmsCompanyContract.PERMISSION_SCOPE_ALL, userId)) {
            return true;
        }
        return userId != null && hasActiveGrantRead(companyId, userId);
    }

    /**
     * 按数据库当前状态判断当前用户是否具有指定有效菜单权限。
     *
     * @param permission 权限标识
     * @return true=当前实时有效
     */
    public boolean hasPermission(String permission) {
        return hasPermission(permission, LoginHelper.getUserId());
    }

    /**
     * 按显式用户的数据库当前状态判断有效菜单权限。
     *
     * @param permission 权限标识
     * @param userId     用户ID
     * @return true=当前实时有效
     */
    public boolean hasPermission(String permission, Long userId) {
        if (isSuperAdmin(userId)) {
            return true;
        }
        return userId != null
            && identityFacts.currentPermission(userId, permission);
    }

    /**
     * 按数据库当前状态校验当前用户的有效菜单权限。
     *
     * @param permission 权限标识
     */
    public void checkPermission(String permission) {
        if (!hasPermission(permission)) {
            throw new NotPermissionException("当前用户缺少实时菜单权限 " + permission);
        }
    }

    /**
     * 在写入事务内当前读并共享锁定有效菜单权限，避免旧快照绕过已提交的撤权。
     *
     * @param permission 权限标识
     */
    public void checkPermissionForShare(String permission) {
        checkPermissionForShare(permission, LoginHelper.getUserId());
    }

    /**
     * 以显式用户身份校验写入事务内的有效菜单权限。
     *
     * @param permission 权限标识
     * @param userId     用户ID
     */
    public void checkPermissionForShare(String permission, Long userId) {
        if (!hasPermissionForShare(permission, userId)) {
            throw new NotPermissionException("当前用户缺少实时菜单权限 " + permission);
        }
    }

    private boolean hasActiveGrantForUpdate(Long companyId, Long userId) {
        return hasActiveGrant(companyId, userId, false);
    }

    private boolean hasActiveGrant(Long companyId, Long userId, boolean forShare) {
        List<Long> roleIds = identityFacts.currentRoleIdsForShare(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }
        return !grantMapper.selectList(
            new QueryWrapper<WmsCompanyManagementGrant>()
                .eq("company_id", companyId)
                .in("role_id", roleIds)
                .last(forShare ? "FOR SHARE" : "FOR UPDATE")
        ).isEmpty();
    }

    private boolean hasActiveGrantRead(Long companyId, Long userId) {
        List<Long> roleIds = identityFacts.currentRoleIdsForShare(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }
        return !grantMapper.selectList(
            new QueryWrapper<WmsCompanyManagementGrant>()
                .eq("company_id", companyId)
                .in("role_id", roleIds)
        ).isEmpty();
    }

    private ServiceException companyScopeDenied() {
        return new ServiceException(
            "COMPANY_SCOPE_DENIED: 当前角色没有该公司的有效管理授权",
            HttpStatus.HTTP_FORBIDDEN
        );
    }

    private boolean hasPermissionForShare(String permission) {
        return hasPermissionForShare(permission, LoginHelper.getUserId());
    }

    private boolean hasPermissionForShare(String permission, Long userId) {
        if (isSuperAdmin(userId)) {
            return true;
        }
        return userId != null
            && identityFacts.currentPermissionForShare(userId, permission);
    }

    private boolean isSuperAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return identityFacts.superAdminForShare(userId);
    }
}
