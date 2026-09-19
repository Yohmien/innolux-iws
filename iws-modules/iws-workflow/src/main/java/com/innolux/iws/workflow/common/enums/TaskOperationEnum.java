/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务操作类型枚举，定义流程任务支持的运行时操作。
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum TaskOperationEnum {

    /**
     * 委派
     */
    DELEGATE_TASK("delegateTask", "委派"),

    /**
     * 转办
     */
    TRANSFER_TASK("transferTask", "转办"),

    /**
     * 加签
     */
    ADD_SIGNATURE("addSignature", "加签"),

    /**
     * 减签
     */
    REDUCTION_SIGNATURE("reductionSignature", "减签");

    /**
     * 操作编码，供接口与前端交互使用。
     */
    private final String code;

    /**
     * 操作描述。
     */
    private final String desc;

    /**
     * 操作编码缓存。
     */
    private static final Map<String, TaskOperationEnum> CODE_MAP = Arrays.stream(values())
        .collect(Collectors.toConcurrentMap(TaskOperationEnum::getCode, Function.identity()));

    /**
     * 根据 code 获取枚举实例。
     *
     * @param code 操作编码
     * @return 对应的枚举实例
     */
    public static TaskOperationEnum getByCode(String code) {
        return CODE_MAP.get(code);
    }

}
