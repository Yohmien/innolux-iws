/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证角色应用菜单覆盖与全局回退规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class RoleMenuScopeRulesTest {

    @Test
    void configuredApplicationMenuOverridesGlobalMenuForThatRole() {
        assertEquals(
            Set.of(11L, 12L),
            RoleMenuScopeRules.effective(
                List.of(1L),
                Map.of(1L, Set.of(1L, 2L)),
                Set.of(1L),
                Map.of(1L, Set.of(11L, 12L))
            )
        );
    }

    @Test
    void configuredEmptyApplicationMenuDoesNotFallBackToGlobalMenu() {
        assertEquals(
            Set.of(),
            RoleMenuScopeRules.effective(
                List.of(1L),
                Map.of(1L, Set.of(1L, 2L)),
                Set.of(1L),
                Map.of()
            )
        );
    }

    @Test
    void unconfiguredRoleKeepsGlobalMenuInMixedRoleUser() {
        assertEquals(
            Set.of(11L, 2L),
            RoleMenuScopeRules.effective(
                List.of(1L, 2L),
                Map.of(1L, Set.of(1L), 2L, Set.of(2L)),
                Set.of(1L),
                Map.of(1L, Set.of(11L))
            )
        );
    }
}
