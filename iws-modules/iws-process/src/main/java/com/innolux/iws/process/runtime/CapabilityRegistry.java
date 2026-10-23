/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : Spring 能力注册表；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件类型、调用合同、本地动作、校验器和分支条件的精确版本注册表。
 *
 * <p>重复的 {@code (key, version)} 直接配置失败；运行时不反射类名、不回退最新版本。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class CapabilityRegistry {

    private final Map<String, EventTypeDescriptor> eventTypes = new LinkedHashMap<>();

    private final Map<String, CallContractDescriptor> callContracts = new LinkedHashMap<>();

    private final Map<String, SingleActionOperation> operations = new LinkedHashMap<>();

    private final Map<String, LocalActionDescriptor> localActionDescriptors = new LinkedHashMap<>();

    private final Map<String, LocalAction> localActions = new LinkedHashMap<>();

    private final Map<String, StateValidatorDescriptor> validators = new LinkedHashMap<>();

    private final Map<String, StateValidator> validatorBeans = new LinkedHashMap<>();

    private final Map<String, BranchConditionDescriptor> conditions = new LinkedHashMap<>();

    private final Map<String, BranchCondition> conditionBeans = new LinkedHashMap<>();

    /**
     * 汇总当前 Spring 容器内的能力。
     *
     * @param eventTypeProviders 事件类型提供者
     * @param operations 单动作实现
     * @param localActions 本地动作
     * @param validators 状态校验器
     * @param conditions 分支条件
     */
    public CapabilityRegistry(List<EventTypeProvider> eventTypeProviders,
                              List<SingleActionOperation> operations,
                              List<LocalAction> localActions,
                              List<StateValidator> validators,
                              List<BranchCondition> conditions) {
        for (EventTypeProvider provider : eventTypeProviders) {
            for (EventTypeDescriptor descriptor : provider.describe()) {
                putUnique(this.eventTypes, key(descriptor.key(), descriptor.version()), descriptor, "事件类型");
            }
        }
        for (SingleActionOperation operation : operations) {
            for (CallContractDescriptor descriptor : operation.describe()) {
                putUnique(callContracts, key(descriptor.key(), descriptor.version()), descriptor, "调用合同");
                putUnique(this.operations, key(descriptor.key(), descriptor.version()), operation, "调用实现");
            }
        }
        for (LocalAction action : localActions) {
            LocalActionDescriptor descriptor = action.describe();
            putUnique(localActionDescriptors, key(descriptor.key(), descriptor.version()), descriptor, "本地动作");
            putUnique(this.localActions, key(descriptor.key(), descriptor.version()), action, "本地动作实现");
        }
        for (StateValidator validator : validators) {
            StateValidatorDescriptor descriptor = validator.describe();
            putUnique(this.validators, key(descriptor.key(), descriptor.version()), descriptor, "状态校验器");
            putUnique(validatorBeans, key(descriptor.key(), descriptor.version()), validator, "状态校验器实现");
        }
        for (BranchCondition condition : conditions) {
            BranchConditionDescriptor descriptor = condition.describe();
            putUnique(this.conditions, key(descriptor.key(), descriptor.version()), descriptor, "分支条件");
            putUnique(conditionBeans, key(descriptor.key(), descriptor.version()), condition, "分支条件实现");
        }
    }

    /**
     * 获取事件类型描述。
     *
     * @param key 类型键
     * @param version 类型版本
     * @return 描述
     */
    public EventTypeDescriptor eventType(String key, String version) {
        return required(eventTypes, key, version, "事件类型");
    }

    /**
     * 获取调用合同描述。
     *
     * @param key 合同键
     * @param version 合同版本
     * @return 描述
     */
    public CallContractDescriptor callContract(String key, String version) {
        return required(callContracts, key, version, "调用合同");
    }

    /**
     * 获取调用实现。
     *
     * @param key 合同键
     * @param version 合同版本
     * @return 实现
     */
    public SingleActionOperation operation(String key, String version) {
        return required(operations, key, version, "调用实现");
    }

    /**
     * 获取本地动作描述。
     *
     * @param key 动作键
     * @param version 动作版本
     * @return 描述
     */
    public LocalActionDescriptor localActionDescriptor(String key, String version) {
        return required(localActionDescriptors, key, version, "本地动作");
    }

    /**
     * 获取本地动作实现。
     *
     * @param key 动作键
     * @param version 动作版本
     * @return 实现
     */
    public LocalAction localAction(String key, String version) {
        return required(localActions, key, version, "本地动作实现");
    }

    /**
     * 获取状态校验器。
     *
     * @param key 校验器键
     * @param version 校验器版本
     * @return 校验器
     */
    public StateValidator validator(String key, String version) {
        return required(validatorBeans, key, version, "状态校验器");
    }

    /**
     * 获取分支条件。
     *
     * @param key 条件键
     * @param version 条件版本
     * @return 条件
     */
    public BranchCondition condition(String key, String version) {
        return required(conditionBeans, key, version, "分支条件");
    }

    /**
     * 返回全部调用合同描述，供配置页只读选择。
     *
     * @return 合同描述
     */
    public List<CallContractDescriptor> callContracts() {
        return List.copyOf(callContracts.values());
    }

    /**
     * 返回全部本地动作描述，供配置页只读选择。
     *
     * @return 动作描述
     */
    public List<LocalActionDescriptor> localActions() {
        return List.copyOf(localActionDescriptors.values());
    }

    /**
     * 返回全部事件类型。
     *
     * @return 事件类型描述
     */
    public List<EventTypeDescriptor> eventTypes() {
        return List.copyOf(eventTypes.values());
    }

    /**
     * 返回全部状态校验器。
     *
     * @return 校验器描述
     */
    public List<StateValidatorDescriptor> validators() {
        return List.copyOf(validators.values());
    }

    /**
     * 返回全部分支条件。
     *
     * @return 条件描述
     */
    public List<BranchConditionDescriptor> conditions() {
        return List.copyOf(conditions.values());
    }

    private static <T> T required(Map<String, T> map, String key, String version, String type) {
        T value = map.get(key(key, version));
        if (value == null) {
            throw new ServiceException("%s能力不可用: %s@%s", type, key, version);
        }
        return value;
    }

    private static <T> void putUnique(Map<String, T> map, String key, T value, String type) {
        if (map.putIfAbsent(key, value) != null) {
            throw new IllegalStateException("重复注册" + type + "能力: " + key);
        }
    }

    private static String key(String key, String version) {
        return key + "@" + version;
    }
}
