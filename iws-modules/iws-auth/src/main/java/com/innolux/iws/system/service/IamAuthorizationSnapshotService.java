/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 计算包含菜单、角色、数据范围和部门树的完整授权指纹；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.innolux.iws.system.api.domain.RoleDTO;
import com.innolux.iws.system.domain.vo.SysRoleVo;
import com.innolux.iws.system.domain.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 统一身份授权快照服务。
 */
@Service
@RequiredArgsConstructor
@DS("identity")
public class IamAuthorizationSnapshotService {

    private final ISysPermissionService permissionService;
    private final ISysRoleService roleService;
    private final ISysDataScopeService dataScopeService;

    /**
     * 计算当前原账号的完整授权快照。
     *
     * @param user 当前账号
     * @return 授权指纹及菜单权限
     */
    public AuthorizationSnapshot snapshot(SysUserVo user) {
        return snapshot(user, new String[0]);
    }

    /**
     * 计算共享授权指纹；应用接入权限由调用方在应用状态中单独校验。
     *
     * @param user                当前账号
     * @param excludedPermissions 不进入共享根指纹的应用接入权限
     * @return 授权指纹及完整菜单权限
     */
    public AuthorizationSnapshot snapshot(SysUserVo user, String... excludedPermissions) {
        Long userId = user.getUserId();
        Set<String> menuPermissions = new TreeSet<>(permissionService.getMenuPermission(userId));
        Set<String> rolePermissions = new TreeSet<>(permissionService.getRolePermission(userId));
        Set<String> excluded = new TreeSet<>();
        if (excludedPermissions != null) {
            Arrays.stream(excludedPermissions)
                .filter(permission -> permission != null && !permission.isBlank())
                .forEach(excluded::add);
        }
        Set<String> sharedMenuPermissions = new TreeSet<>(menuPermissions);
        sharedMenuPermissions.removeAll(excluded);
        Set<String> sharedRolePermissions = new TreeSet<>(rolePermissions);
        sharedRolePermissions.removeAll(excluded);
        List<SysRoleVo> roleVos = new ArrayList<>(roleService.selectRolesByUserId(userId));
        roleVos.sort(Comparator.comparing(
            SysRoleVo::getRoleId,
            Comparator.nullsLast(Long::compareTo)
        ));

        List<RoleDTO> roles = new ArrayList<>(roleVos.size());
        List<String> roleFacts = new ArrayList<>(roleVos.size());
        List<String> customScopes = new ArrayList<>();
        for (SysRoleVo role : roleVos) {
            RoleDTO dto = new RoleDTO();
            dto.setRoleId(role.getRoleId());
            dto.setRoleName(role.getRoleName());
            dto.setRoleKey(role.getRoleKey());
            dto.setDataScope(role.getDataScope());
            roles.add(dto);
            roleFacts.add(fact(
                role.getRoleId(),
                role.getRoleKey(),
                role.getStatus(),
                role.getDataScope()
            ));
            if ("2".equals(role.getDataScope())) {
                customScopes.add(role.getRoleId() + "=" + dataScopeService.getRoleCustom(role.getRoleId()));
            }
        }
        customScopes.sort(String::compareTo);

        Map<String, List<Long>> dataScopeRoleMap = permissionService.getDataScopeRoleMap(roles);
        List<String> dataScopeFacts = new ArrayList<>(dataScopeRoleMap.size());
        dataScopeRoleMap.forEach((permission, roleIds) -> {
            if (excluded.contains(permission)) {
                return;
            }
            List<Long> sortedRoleIds = new ArrayList<>(roleIds);
            sortedRoleIds.sort(Long::compareTo);
            dataScopeFacts.add(permission + "=" + join(sortedRoleIds));
        });
        dataScopeFacts.sort(String::compareTo);

        String raw = join(List.of(
            String.valueOf(user.getUserId()),
            String.valueOf(user.getStatus()),
            String.valueOf(user.getDeptId()),
            String.valueOf(user.getDeptId() == null ? "" : dataScopeService.getDeptAndChild(user.getDeptId())),
            join(sharedMenuPermissions),
            join(sharedRolePermissions),
            join(roleFacts),
            join(customScopes),
            join(dataScopeFacts)
        ));
        return new AuthorizationSnapshot(
            DigestUtil.sha256Hex(raw),
            Set.copyOf(menuPermissions)
        );
    }

    private String fact(Object... values) {
        List<String> parts = new ArrayList<>(values.length);
        for (Object value : values) {
            parts.add(String.valueOf(value));
        }
        return join(parts);
    }

    private String join(Collection<?> values) {
        return values.stream().map(String::valueOf).reduce((left, right) -> left + "\u001f" + right).orElse("");
    }

    /**
     * 当前授权快照。
     *
     * @param fingerprint    完整授权指纹
     * @param menuPermissions 菜单权限
     */
    public record AuthorizationSnapshot(String fingerprint, Set<String> menuPermissions) {
    }
}
