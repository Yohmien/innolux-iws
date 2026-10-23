/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : T2 冻结出站目标身份；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * SEND_INTENT 持久化的完整目标身份。
 *
 * @param addressId endpoint 地址 ID
 * @param addressRevision endpoint 地址对象版本
 * @param catalogRevision 目录版本
 * @param environmentCode 环境编码
 * @param targetSystemCode 目标系统
 * @param contractKey 合同键
 * @param contractVersion 合同版本
 * @param httpMethod HTTP 方法
 * @param apiPath 接口路径
 * @param resourceKey CALL 资源键
 * @param logicalCallKey 逻辑调用键
 * @param baseUrl 基础地址
 * @param callScopeEventId 挂靠的原 CALL 事件 ID
 * @param recoveryQuery 是否为对账 QUERY 恢复
 */
public record SendTarget(String addressId, String addressRevision, String catalogRevision, String environmentCode,
                         String targetSystemCode, String contractKey, String contractVersion,
                         String httpMethod, String apiPath, String resourceKey,
                         String logicalCallKey, String baseUrl, Long callScopeEventId,
                         boolean recoveryQuery) {

    /**
     * 正常出站目标兼容构造。
     */
    public SendTarget(String addressId, String catalogRevision, String environmentCode,
                      String targetSystemCode, String contractKey, String contractVersion,
                      String httpMethod, String apiPath, String resourceKey,
                      String logicalCallKey, String baseUrl) {
        this(addressId, null, catalogRevision, environmentCode, targetSystemCode, contractKey,
            contractVersion, httpMethod, apiPath, resourceKey, logicalCallKey, baseUrl, null, false);
    }
}
