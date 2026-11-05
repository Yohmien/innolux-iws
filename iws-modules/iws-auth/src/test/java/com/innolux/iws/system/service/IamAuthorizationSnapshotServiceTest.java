package com.innolux.iws.system.service;

import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.domain.vo.SysRoleVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamAuthorizationSnapshotServiceTest {

    @Test
    void applicationAccessChangeDoesNotChangeSharedRootFingerprint() {
        Set<String> permissions = new TreeSet<>(
            Set.of("iam:app:iws:a", "iam:app:iws:b", "monitor:admin:list")
        );
        ISysPermissionService permissionService = mock(ISysPermissionService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        ISysDataScopeService dataScopeService = mock(ISysDataScopeService.class);
        when(permissionService.getMenuPermission(1001L)).thenAnswer(invocation -> Set.copyOf(permissions));
        when(permissionService.getRolePermission(1001L)).thenReturn(Set.of("role-a"));
        when(permissionService.getDataScopeRoleMap(anyList())).thenReturn(Map.of());
        when(roleService.selectRolesByUserId(1001L)).thenReturn(List.of());
        when(dataScopeService.getDeptAndChild(10L)).thenReturn("10");

        IamAuthorizationSnapshotService service =
            new IamAuthorizationSnapshotService(permissionService, roleService, dataScopeService);
        SysUserVo user = user();
        String[] excluded = {"iam:app:iws:a", "iam:app:iws:b", "monitor:admin:list"};
        String before = service.snapshot(user, excluded).fingerprint();

        permissions.remove("iam:app:iws:a");
        IamAuthorizationSnapshotService.AuthorizationSnapshot after =
            service.snapshot(user, excluded);

        assertEquals(before, after.fingerprint());
        assertFalse(after.menuPermissions().contains("iam:app:iws:a"));
        assertTrue(after.menuPermissions().contains("monitor:admin:list"));
    }

    @Test
    void sharedDepartmentAuthorizationChangeStillChangesFingerprint() {
        ISysPermissionService permissionService = mock(ISysPermissionService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        ISysDataScopeService dataScopeService = mock(ISysDataScopeService.class);
        when(permissionService.getMenuPermission(1001L)).thenReturn(Set.of("wms:stock:list"));
        when(permissionService.getRolePermission(1001L)).thenReturn(Set.of("role-a"));
        when(permissionService.getDataScopeRoleMap(List.of())).thenReturn(Map.of());
        when(roleService.selectRolesByUserId(1001L)).thenReturn(List.of());
        AtomicReference<String> deptTree = new AtomicReference<>("10");
        when(dataScopeService.getDeptAndChild(10L)).thenAnswer(invocation -> deptTree.get());

        IamAuthorizationSnapshotService service =
            new IamAuthorizationSnapshotService(permissionService, roleService, dataScopeService);
        SysUserVo user = user();
        String before = service.snapshot(user, "iam:app:iws:a").fingerprint();
        deptTree.set("10,11");
        String after = service.snapshot(user, "iam:app:iws:a").fingerprint();

        assertNotEquals(before, after);
    }

    @Test
    void nonAccessFunctionPermissionChangeStillChangesFingerprint() {
        Set<String> permissions = new TreeSet<>(
            Set.of("iam:app:iws:a", "wms:stock:list")
        );
        ISysPermissionService permissionService = mock(ISysPermissionService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        ISysDataScopeService dataScopeService = mock(ISysDataScopeService.class);
        when(permissionService.getMenuPermission(1001L)).thenAnswer(invocation -> Set.copyOf(permissions));
        when(permissionService.getRolePermission(1001L)).thenReturn(Set.of());
        when(permissionService.getDataScopeRoleMap(List.of())).thenReturn(Map.of());
        when(roleService.selectRolesByUserId(1001L)).thenReturn(List.of());
        when(dataScopeService.getDeptAndChild(10L)).thenReturn("10");

        IamAuthorizationSnapshotService service =
            new IamAuthorizationSnapshotService(permissionService, roleService, dataScopeService);
        String before = service.snapshot(user(), "iam:app:iws:a").fingerprint();
        permissions.add("wms:stock:edit");
        String after = service.snapshot(user(), "iam:app:iws:a").fingerprint();

        assertNotEquals(before, after);
    }

    @Test
    void roleDataScopeAndCustomDepartmentRangeChangesStillChangeFingerprint() {
        ISysPermissionService permissionService = mock(ISysPermissionService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        ISysDataScopeService dataScopeService = mock(ISysDataScopeService.class);
        when(permissionService.getMenuPermission(1001L)).thenReturn(Set.of("wms:stock:list"));
        when(permissionService.getRolePermission(1001L)).thenReturn(Set.of());
        when(permissionService.getDataScopeRoleMap(anyList())).thenReturn(Map.of());
        when(dataScopeService.getDeptAndChild(10L)).thenReturn("10");
        AtomicReference<String> customScope = new AtomicReference<>("10");
        when(dataScopeService.getRoleCustom(2001L)).thenAnswer(invocation -> customScope.get());

        SysRoleVo role = new SysRoleVo();
        role.setRoleId(2001L);
        role.setRoleKey("warehouse");
        role.setStatus("0");
        role.setDataScope("2");
        when(roleService.selectRolesByUserId(1001L)).thenReturn(List.of(role));

        IamAuthorizationSnapshotService service =
            new IamAuthorizationSnapshotService(permissionService, roleService, dataScopeService);
        String before = service.snapshot(user(), "iam:app:iws:a").fingerprint();
        customScope.set("10,11");
        String afterCustom = service.snapshot(user(), "iam:app:iws:a").fingerprint();
        role.setDataScope("1");
        String afterDataScope = service.snapshot(user(), "iam:app:iws:a").fingerprint();

        assertNotEquals(before, afterCustom);
        assertNotEquals(afterCustom, afterDataScope);
    }

    @Test
    void accountStatusAndDepartmentAssignmentChangesStillChangeFingerprint() {
        ISysPermissionService permissionService = mock(ISysPermissionService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        ISysDataScopeService dataScopeService = mock(ISysDataScopeService.class);
        when(permissionService.getMenuPermission(1001L)).thenReturn(Set.of());
        when(permissionService.getRolePermission(1001L)).thenReturn(Set.of());
        when(permissionService.getDataScopeRoleMap(List.of())).thenReturn(Map.of());
        when(roleService.selectRolesByUserId(1001L)).thenReturn(List.of());
        when(dataScopeService.getDeptAndChild(10L)).thenReturn("10");
        when(dataScopeService.getDeptAndChild(20L)).thenReturn("20");

        IamAuthorizationSnapshotService service =
            new IamAuthorizationSnapshotService(permissionService, roleService, dataScopeService);
        SysUserVo user = user();
        String before = service.snapshot(user).fingerprint();
        user.setDeptId(20L);
        String afterDept = service.snapshot(user).fingerprint();
        user.setStatus("1");
        String afterStatus = service.snapshot(user).fingerprint();

        assertNotEquals(before, afterDept);
        assertNotEquals(afterDept, afterStatus);
    }

    private SysUserVo user() {
        SysUserVo user = new SysUserVo();
        user.setUserId(1001L);
        user.setStatus("0");
        user.setDeptId(10L);
        return user;
    }
}
