/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : IWS 主站按实际 classpath 生成当前用户路由；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamViewContext;
import com.innolux.iws.common.auth.domain.IamViewContextItem;
import com.innolux.iws.common.auth.domain.IamViewMapping;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.domain.SysMenu;
import com.innolux.iws.system.domain.vo.RouterVo;
import com.innolux.iws.system.service.ISysMenuService;
import com.innolux.iws.system.service.IamViewMappingService;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.web.config.IamClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IWS 当前用户菜单路由。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/menu")
public class IwsCurrentMenuController {

    private final ISysMenuService menuService;
    private final IamViewMappingService viewMappingService;
    private final IamClientProperties properties;
    private final PermissionService permissionService;

    /**
     * 获取当前 IWS 用户可访问路由。
     *
     * @return 路由列表
     */
    @GetMapping("/getRouters")
    public R<List<RouterVo>> getRouters() {
        List<SysMenu> menus = menuService.selectMenuTreeByUserId(LoginHelper.getUserId());
        return R.ok(menuService.buildMenus(menus));
    }

    /**
     * 按当前后端实际部署、账号权限和中心受控映射返回页面上下文。
     *
     * @param terminal WEB 或 PDA
     * @return 当前应用会话页面上下文
     */
    @GetMapping("/view-context")
    public R<IamViewContext> getViewContext(
        @RequestParam(defaultValue = IamProtocolConstants.TERMINAL_WEB) String terminal
    ) {
        String normalizedTerminal = terminal.toUpperCase(java.util.Locale.ROOT);
        if (!IamProtocolConstants.TERMINAL_WEB.equals(normalizedTerminal)
            && !IamProtocolConstants.TERMINAL_PDA.equals(normalizedTerminal)) {
            throw new ServiceException("终端类型不合法", 400);
        }
        Object sessionId = StpUtil.getTokenSession().get(IamProtocolConstants.APPLICATION_SESSION_ID_KEY);
        Object frontendId = StpUtil.getTokenSession().get(IamProtocolConstants.FRONTEND_ID_SESSION_KEY);
        Object generation = StpUtil.getTokenSession().get(
            IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY
        );
        Object sessionTerminal = StpUtil.getTokenSession().get(IamProtocolConstants.TERMINAL_SESSION_KEY);
        if (sessionId == null || frontendId == null || sessionTerminal == null
            || !(generation instanceof Number number)) {
            throw new ServiceException("统一身份应用会话上下文不完整", 401);
        }
        if (!normalizedTerminal.equals(sessionTerminal.toString())) {
            throw new ServiceException("页面终端与当前应用会话不匹配", 403);
        }
        List<SysMenu> menus = menuService.selectMenuTreeByUserId(LoginHelper.getUserId());
        IamViewMappingService.MappingSnapshot snapshot = viewMappingService.snapshot();
        Map<Long, IamViewMapping> mappings = viewMappingService.find(
            properties.getApplicationId(), normalizedTerminal
        );
        Set<String> permissions = permissionService.getMenuPermission(LoginHelper.getUserId());
        List<IamViewContextItem> items = new ArrayList<>();
        collectViewItems(menus, mappings, permissions, items);
        return R.ok(new IamViewContext(
            IamProtocolConstants.ISSUER,
            LoginHelper.getUserId(),
            properties.getApplicationId(),
            properties.getApplicationType(),
            frontendId.toString(),
            normalizedTerminal,
            sessionId.toString(),
            number.longValue(),
            snapshot.version(),
            items
        ));
    }

    /**
     * 递归收集页面项；后端部署与账号菜单权限由原菜单服务先行过滤。
     *
     * @param menus       当前菜单树
     * @param mappings    当前应用和终端映射
     * @param permissions 当前账号权限
     * @param result      收集结果
     */
    private void collectViewItems(
        List<SysMenu> menus,
        Map<Long, IamViewMapping> mappings,
        Set<String> permissions,
        List<IamViewContextItem> result
    ) {
        if (menus == null) {
            return;
        }
        for (SysMenu menu : menus) {
            IamViewMapping mapping = mappings.get(menu.getMenuId());
            String viewKey = mapping == null ? menu.getComponent() : mapping.viewKey();
            List<String> requiredCapabilities = mapping == null
                ? List.of()
                : mapping.requiredCapabilities() == null ? List.of() : mapping.requiredCapabilities();
            if (hasCapabilities(permissions, requiredCapabilities)) {
                result.add(new IamViewContextItem(
                    menu.getMenuId(),
                    menu.getParentId(),
                    viewKey,
                    requiredCapabilities
                ));
            }
            collectViewItems(menu.getChildren(), mappings, permissions, result);
        }
    }

    /**
     * 判断当前账号是否具备页面声明能力。
     *
     * @param permissions 当前权限
     * @param required    页面所需能力
     * @return 是否具备
     */
    private boolean hasCapabilities(Set<String> permissions, List<String> required) {
        return permissions.contains("*:*:*")
            || required.stream().allMatch(permissions::contains);
    }
}
