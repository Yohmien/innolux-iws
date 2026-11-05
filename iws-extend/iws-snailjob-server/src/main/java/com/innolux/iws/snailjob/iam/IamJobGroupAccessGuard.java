/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 复用原生用户会话并按固定资源映射校验 Job group 访问边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.model.request.UserSessionVO;
import com.aizuda.snailjob.server.web.util.UserSessionUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;

/**
 * 使用 SnailJob 原生用户会话和固定资源表执行访问校验。
 */
@Component
@RequiredArgsConstructor
public class IamJobGroupAccessGuard {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 校验普通用户是否拥有目标 group。
     *
     * @param groupName 目标 group
     */
    public void requireGroup(String groupName) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (groupName == null || groupName.isBlank()
            || user.getGroupNames() == null
            || !user.getGroupNames().contains(groupName)) {
            throw new IamJobGroupAccessDeniedException("当前用户无权访问 Job group: " + groupName);
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sj_group_config WHERE namespace_id = ? AND group_name = ?",
            Integer.class,
            user.getNamespaceId(),
            groupName
        );
        if (count == null || count == 0) {
            throw new IamJobGroupAccessDeniedException("当前 namespace 不存在 Job group: " + groupName);
        }
    }

    /**
     * 校验普通用户是否有权访问指定资源。
     *
     * @param type 固定资源类型
     * @param id 资源主键
     */
    public void requireResource(IamJobResourceType type, Long id) {
        requireResource(type, id, null);
    }

    /**
     * 校验资源可由当前用户访问，并可选核对请求声明的 group。
     *
     * @param type 固定资源类型
     * @param id 资源主键
     * @param requestedGroupName 请求声明的 group
     */
    public void requireResource(
        IamJobResourceType type,
        Long id,
        String requestedGroupName
    ) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (id == null) {
            throw new IamJobGroupAccessDeniedException("Job 资源主键不能为空");
        }
        ResourceScope scope = findResource(type, id);
        if (scope == null) {
            throw new IamJobGroupAccessDeniedException("Job 资源不存在或当前用户无权访问");
        }
        requireScope(user, type, scope);
        if (requestedGroupName != null
            && !requestedGroupName.isBlank()
            && !Objects.equals(requestedGroupName, scope.groupName())) {
            throw new IamJobGroupAccessDeniedException("请求 group 与目标资源不一致");
        }
    }

    /**
     * 在控制器执行前整体预检批量资源。
     *
     * @param type 固定资源类型
     * @param ids 资源主键
     */
    public void requireResources(IamJobResourceType type, Collection<Long> ids) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (ids == null || ids.isEmpty()) {
            throw new IamJobGroupAccessDeniedException("Job 资源主键不能为空");
        }
        for (Long id : ids) {
            requireResource(type, id);
        }
    }

    /**
     * 校验普通用户只操作当前 namespace。
     *
     * @param namespaceId 请求 namespace
     */
    public void requireCurrentNamespace(String namespaceId) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (namespaceId == null || !Objects.equals(namespaceId, user.getNamespaceId())) {
            throw new IamJobGroupAccessDeniedException("当前用户无权跨 namespace 访问 Job 资源");
        }
    }

    /**
     * 校验普通用户未使用空 group 导出整个 namespace。
     *
     * @param groupName 请求 group
     */
    public void requireExportGroup(String groupName) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (groupName == null || groupName.isBlank()) {
            throw new IamJobGroupAccessDeniedException("普通用户导出必须指定已授权 Job group");
        }
        requireGroup(groupName);
    }

    /**
     * 判断当前用户是否为 Job 原生管理员。
     *
     * @return 是否为管理员
     */
    public boolean isAdministrator() {
        return currentUser().isAdmin();
    }

    /**
     * 判断资源是否属于当前普通用户可访问范围。
     *
     * @param type 固定资源类型
     * @param id 资源主键
     * @return 是否可访问
     */
    public boolean isResourceAccessible(IamJobResourceType type, Long id) {
        UserSessionVO user = currentUser();
        if (user.isAdmin()) {
            return true;
        }
        if (id == null) {
            return false;
        }
        ResourceScope scope = findResource(type, id);
        try {
            requireScope(user, type, scope);
            return true;
        } catch (IamJobGroupAccessDeniedException ignored) {
            return false;
        }
    }

    /**
     * 判断当前普通用户是否拥有目标 group。
     *
     * @param groupName 目标 group
     * @return 是否可访问
     */
    public boolean isGroupAccessible(String groupName) {
        try {
            requireGroup(groupName);
            return true;
        } catch (IamJobGroupAccessDeniedException ignored) {
            return false;
        }
    }

    private void requireScope(
        UserSessionVO user,
        IamJobResourceType type,
        ResourceScope scope
    ) {
        if (scope == null || !Objects.equals(scope.namespaceId(), user.getNamespaceId())) {
            throw new IamJobGroupAccessDeniedException("当前用户无权跨 namespace 访问 Job 资源");
        }
        if (type.groupScoped()
            && (scope.groupName() == null
            || user.getGroupNames() == null
            || !user.getGroupNames().contains(scope.groupName()))) {
            throw new IamJobGroupAccessDeniedException(
                "当前用户无权访问 Job group: " + scope.groupName()
            );
        }
    }

    private ResourceScope findResource(IamJobResourceType type, Long id) {
        String groupColumn = type.groupScoped() ? "group_name" : "NULL AS group_name";
        return jdbcTemplate.query(
                "SELECT namespace_id, " + groupColumn + " FROM " + type.tableName() + " WHERE id = ?",
                (resultSet, rowNum) -> new ResourceScope(
                    resultSet.getString("namespace_id"),
                    resultSet.getString("group_name")
                ),
                id
            )
            .stream()
            .findFirst()
            .orElse(null);
    }

    private UserSessionVO currentUser() {
        UserSessionVO user = UserSessionUtils.currentUserSession();
        if (user == null) {
            throw new IamJobGroupAccessDeniedException("Job 统一身份会话不存在");
        }
        return user;
    }

    /**
     * 资源所属的原生 namespace 和 group。
     */
    private record ResourceScope(String namespaceId, String groupName) {
    }
}
