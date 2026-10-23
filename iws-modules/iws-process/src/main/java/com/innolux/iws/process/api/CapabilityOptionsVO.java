/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 已注册能力目录；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import com.innolux.iws.process.runtime.BranchConditionDescriptor;
import com.innolux.iws.process.runtime.CallContractDescriptor;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;

/**
 * 配置页只读能力目录。
 *
 * @param eventTypes 事件类型
 * @param callContracts 调用合同
 * @param localActions 本地动作
 * @param validators 状态校验器
 * @param branchConditions 分支条件
 */
public record CapabilityOptionsVO(java.util.List<EventTypeDescriptor> eventTypes,
                                  java.util.List<CallContractDescriptor> callContracts,
                                  java.util.List<LocalActionDescriptor> localActions,
                                  java.util.List<StateValidatorDescriptor> validators,
                                  java.util.List<BranchConditionDescriptor> branchConditions) {
}
