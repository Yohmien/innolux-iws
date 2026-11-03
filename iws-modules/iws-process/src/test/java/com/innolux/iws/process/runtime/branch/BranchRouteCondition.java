/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 只读取已持久检查点 route 的分支条件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.branch;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.runtime.BranchCondition;
import com.innolux.iws.process.runtime.BranchConditionDescriptor;

import java.util.Map;

/**
 * 条件缺少冻结事实时直接失败，避免空事实被默认分支掩盖。
 */
final class BranchRouteCondition implements BranchCondition {

    private final String key;

    private final String expected;

    BranchRouteCondition(String key, String expected) {
        this.key = key;
        this.expected = expected;
    }

    @Override
    public BranchConditionDescriptor describe() {
        return new BranchConditionDescriptor(key, "1", "按已持久检查点 route 选择 " + expected);
    }

    @Override
    public boolean matches(Map<String, String> facts) {
        String route = facts.get("checkpoint.route");
        if (route == null || route.isBlank()) {
            throw new ServiceException("分支条件缺少已持久检查点事实 checkpoint.route");
        }
        return expected.equals(route);
    }
}
