/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 复用原菜单管理权限维护应用、终端和原菜单到受控页面键的映射；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamViewMapping;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.domain.vo.SysMenuVo;
import com.innolux.iws.system.service.IamViewMappingService;
import com.innolux.iws.system.service.ISysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 统一界面映射配置接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/iam/view-mapping")
public class IamViewMappingController {

    private final IamProperties properties;
    private final IamViewMappingService viewMappingService;
    private final ISysMenuService menuService;

    /**
     * 返回可配置应用、终端和构建时页面白名单。
     *
     * @return 配置选项
     */
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("system:menu:query")
    @GetMapping("/options")
    public R<List<ApplicationOption>> options() {
        List<ApplicationOption> result = new ArrayList<>();
        properties.getApplications().forEach((applicationId, application) -> {
            if (!application.isEnabled()) {
                return;
            }
            Set<String> terminals = application.getTerminals();
            List<ViewOption> views = properties.getViews().stream()
                .filter(view -> application.getType().equals(view.getApplicationType()))
                .filter(view -> terminals.contains(view.getTerminal()))
                .map(view -> new ViewOption(
                    view.getTerminal(),
                    view.getViewKey(),
                    view.getName(),
                    view.getRequiredCapabilities() == null
                        ? List.of()
                        : List.copyOf(view.getRequiredCapabilities())
                ))
                .toList();
            result.add(new ApplicationOption(
                applicationId,
                application.getType(),
                application.getName(),
                List.copyOf(terminals),
                views
            ));
        });
        return R.ok(result);
    }

    /**
     * 查询一个原菜单上的全部系统与终端映射。
     *
     * @param menuId 菜单主键
     * @return 映射列表
     */
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("system:menu:query")
    @GetMapping("/{menuId}")
    public R<List<IamViewMapping>> list(@PathVariable Long menuId) {
        return R.ok(viewMappingService.snapshot().mappings().stream()
            .filter(mapping -> menuId.equals(mapping.menuId()))
            .toList());
    }

    /**
     * 保存一个应用、终端和菜单的页面映射。
     *
     * @param mapping 映射
     * @return 更新后的映射版本
     */
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("system:menu:edit")
    @PutMapping
    public R<Long> save(@RequestBody IamViewMapping mapping) {
        IamProperties.Application application = properties.getApplications().get(mapping.applicationId());
        if (application == null || !application.isEnabled()) {
            throw new ServiceException("目标应用未登记或已停用");
        }
        if (mapping.terminal() == null || !application.getTerminals().contains(mapping.terminal())) {
            throw new ServiceException("目标应用未开放该终端");
        }
        SysMenuVo menu = menuService.selectMenuById(mapping.menuId());
        if (menu == null || !SystemConstants.TYPE_MENU.equals(menu.getMenuType())) {
            throw new ServiceException("只能映射现有菜单页面");
        }
        IamProperties.ViewDefinition definition = findViewDefinition(
            application.getType(), mapping.terminal(), mapping.viewKey()
        );
        if (definition == null) {
            throw new ServiceException("页面键未在当前终端构建白名单中登记");
        }
        List<String> requested = mapping.requiredCapabilities() == null
            ? List.of()
            : mapping.requiredCapabilities();
        List<String> allowed = definition.getRequiredCapabilities() == null
            ? List.of()
            : definition.getRequiredCapabilities();
        if (!allowed.containsAll(requested)) {
            throw new ServiceException("页面能力声明超出构建白名单");
        }
        List<String> capabilities = requested.isEmpty() ? allowed : requested;
        return R.ok(viewMappingService.save(new IamViewMapping(
            mapping.applicationId(),
            mapping.terminal(),
            mapping.menuId(),
            mapping.viewKey(),
            List.copyOf(capabilities)
        )).version());
    }

    /**
     * 删除一个应用、终端和菜单的页面映射。
     *
     * @param applicationId 应用标识
     * @param terminal      终端
     * @param menuId        菜单主键
     * @return 更新后的映射版本
     */
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("system:menu:edit")
    @DeleteMapping("/{applicationId}/{terminal}/{menuId}")
    public R<Long> remove(
        @PathVariable String applicationId,
        @PathVariable String terminal,
        @PathVariable Long menuId
    ) {
        return R.ok(viewMappingService.remove(applicationId, terminal, menuId).version());
    }

    /**
     * 查找构建时页面注册项。
     *
     * @param applicationType 应用类型
     * @param terminal        终端
     * @param viewKey         页面键
     * @return 注册项；未登记时返回 null
     */
    private IamProperties.ViewDefinition findViewDefinition(
        String applicationType,
        String terminal,
        String viewKey
    ) {
        return properties.getViews().stream()
            .filter(view -> applicationType.equals(view.getApplicationType()))
            .filter(view -> terminal.equals(view.getTerminal()))
            .filter(view -> viewKey != null && viewKey.equals(view.getViewKey()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 应用配置选项。
     *
     * @param applicationId 应用标识
     * @param applicationType 应用类型
     * @param name          展示名称
     * @param terminals     允许终端
     * @param views         页面白名单
     */
    public record ApplicationOption(
        String applicationId,
        String applicationType,
        String name,
        List<String> terminals,
        List<ViewOption> views
    ) {
    }

    /**
     * 页面白名单项。
     *
     * @param terminal       终端
     * @param viewKey        页面键
     * @param name           展示名称
     * @param requiredCapabilities 允许能力
     */
    public record ViewOption(
        String terminal,
        String viewKey,
        String name,
        List<String> requiredCapabilities
    ) {
    }
}
