/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 定义菜单应用范围的共享、可见性和父子归属规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import com.innolux.iws.common.core.utils.StringUtils;

/**
 * 菜单应用范围规则。
 */
public final class MenuApplicationScopeRules {

    /**
     * 所有 IWS 应用共享的菜单范围。
     */
    public static final String SHARED_APPLICATION_ID = "*";

    private MenuApplicationScopeRules() {
    }

    /**
     * 将空白应用标识规范化为共享范围。
     *
     * @param applicationId 应用标识
     * @return 非空应用标识
     */
    public static String normalize(String applicationId) {
        return StringUtils.isBlank(applicationId)
            ? SHARED_APPLICATION_ID
            : applicationId.trim();
    }

    /**
     * 判断菜单定义是否属于选中应用的完整菜单范围。
     *
     * @param menuApplicationId     菜单所属应用
     * @param selectedApplicationId 当前选中应用；为空时表示管理端查看全部
     * @return 是否可见
     */
    public static boolean isVisible(String menuApplicationId, String selectedApplicationId) {
        if (StringUtils.isBlank(selectedApplicationId)) {
            return true;
        }
        String normalizedMenuApplicationId = normalize(menuApplicationId);
        return SHARED_APPLICATION_ID.equals(normalizedMenuApplicationId)
            || selectedApplicationId.trim().equals(normalizedMenuApplicationId);
    }

    /**
     * 判断父菜单能否承载指定应用的子菜单。
     *
     * <p>共享菜单只能挂在共享目录下；应用菜单可以挂在共享目录或同应用目录下。
     * 该约束避免共享节点依赖某个应用，也避免跨应用父子关系产生残缺菜单树。</p>
     *
     * @param parentApplicationId 父菜单所属应用
     * @param childApplicationId  子菜单所属应用
     * @return 是否兼容
     */
    public static boolean isParentCompatible(
        String parentApplicationId,
        String childApplicationId
    ) {
        String normalizedParent = normalize(parentApplicationId);
        String normalizedChild = normalize(childApplicationId);
        return SHARED_APPLICATION_ID.equals(normalizedParent)
            || normalizedParent.equals(normalizedChild);
    }
}
