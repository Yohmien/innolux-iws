/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 单动作与状态校验能力契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.OperationKind;

import java.util.List;

/**
 * 单次 HTTP 调用合同描述。
 *
 * @param key 合同键
 * @param version 合同版本
 * @param displayName 中文名
 * @param operationKind 动作类型
 * @param direction 方向，仅 INBOUND/OUTBOUND
 * @param purpose 用途
 * @param completion 完成合同
 * @param httpMethod HTTP 方法
 * @param apiPath 相对接口路径
 * @param resourceKey 调用资源合同键，容量控制必须使用该键
 * @param callerCandidates 可信入站来源候选系统，来自注册能力而非业务请求自报
 */
public record CallContractDescriptor(String key, String version, String displayName, OperationKind operationKind,
                                     EventDirection direction, CallPurpose purpose,
                                     CompletionContract completion, String httpMethod, String apiPath,
                                     String resourceKey, List<String> callerCandidates) {

    /**
     * 固化来源候选，未声明的能力默认空集合。
     */
    public CallContractDescriptor {
        callerCandidates = callerCandidates == null ? List.of() : List.copyOf(callerCandidates);
    }

    /**
     * 兼容未声明来源候选的现有能力。
     *
     * @param key 合同键
     * @param version 合同版本
     * @param displayName 中文名
     * @param operationKind 动作类型
     * @param direction 方向
     * @param purpose 用途
     * @param completion 完成合同
     * @param httpMethod HTTP 方法
     * @param apiPath 相对接口路径
     * @param resourceKey 调用资源合同键
     */
    public CallContractDescriptor(String key, String version, String displayName, OperationKind operationKind,
                                  EventDirection direction, CallPurpose purpose, CompletionContract completion,
                                  String httpMethod, String apiPath, String resourceKey) {
        this(key, version, displayName, operationKind, direction, purpose, completion, httpMethod, apiPath,
            resourceKey, List.of());
    }

    /**
     * 兼容资源键引入前的九参数构造。
     *
     * @param key 合同键
     * @param version 合同版本
     * @param displayName 中文名
     * @param operationKind 动作类型
     * @param direction 方向
     * @param purpose 用途
     * @param completion 完成合同
     * @param httpMethod HTTP 方法
     * @param apiPath 相对接口路径
     */
    public CallContractDescriptor(String key, String version, String displayName, OperationKind operationKind,
                                  EventDirection direction, CallPurpose purpose, CompletionContract completion,
                                  String httpMethod, String apiPath) {
        this(key, version, displayName, operationKind, direction, purpose, completion, httpMethod, apiPath,
            null, List.of());
    }
}
