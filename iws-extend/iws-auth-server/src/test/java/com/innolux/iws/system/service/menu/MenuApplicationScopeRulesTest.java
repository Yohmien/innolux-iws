/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证菜单定义按应用隔离及共享菜单父子规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class MenuApplicationScopeRulesTest {

    @Test
    void selectedApplicationContainsOnlyItsOwnAndSharedMenus() {
        assertTrue(MenuApplicationScopeRules.isVisible("*", "iws-a"));
        assertTrue(MenuApplicationScopeRules.isVisible("iws-a", "iws-a"));
        assertFalse(MenuApplicationScopeRules.isVisible("iws-b", "iws-a"));
    }

    @Test
    void appSpecificMenuCanUseSharedOrSameApplicationParent() {
        assertTrue(MenuApplicationScopeRules.isParentCompatible("*", "iws-a"));
        assertTrue(MenuApplicationScopeRules.isParentCompatible("iws-a", "iws-a"));
        assertFalse(MenuApplicationScopeRules.isParentCompatible("iws-b", "iws-a"));
    }

    @Test
    void sharedMenuCannotDependOnAnApplicationSpecificParent() {
        assertFalse(MenuApplicationScopeRules.isParentCompatible("iws-a", "*"));
    }
}
