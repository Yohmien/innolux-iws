/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 单事件执行事实只读快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import java.util.List;
import java.util.Map;

/**
 * 单事件执行事实只读快照。
 *
 * @param eventId 事件 ID
 * @param eventVersion 事件版本
 * @param eventCode 事件编码
 * @param eventName 事件名称
 * @param eventDirection 事件方向
 * @param eventRole 事件角色
 * @param eventTypeKey 事件类型键
 * @param eventTypeVersion 事件类型版本
 * @param callContractKey 调用合同键
 * @param callContractVersion 调用合同版本
 * @param localActionKey 本地动作键
 * @param localActionVersion 本地动作版本
 * @param systemCode 系统编码
 * @param systemName 系统名称
 * @param mode 过账模式
 * @param sourceStateCode 源状态
 * @param targetStateCode 目标状态
 * @param eventStatus 事件状态
 * @param outcome 合同结果
 * @param sent 是否已发送
 * @param checkpoint 检查点
 * @param attemptNo 尝试号
 * @param blockedReason 阻塞原因
 * @param waitReason 等待原因
 * @param expectedCallers 预期来源
 * @param deadline 执行期限
 * @param correlationId 调用链标识
 * @param externalTaskId 外部任务 ID
 * @param inboundReceipt 入站回执
 * @param originOutboundEventId 原出站事件
 * @param relatedEventIds 相关事件
 * @param httpContract HTTP 合同摘要
 * @param validations 校验条件
 * @param requestRetries 请求重投摘要
 * @param allowedActions 动作可用性提示
 * @param reasons 不可用原因
 * @param canRetry 是否可重试
 * @param canRevalidate 是否可重新校验
 * @param canFinalize 是否可最终失败
 * @param asOf 快照时间
 */
public record EventExecutionVO(String eventId, String eventVersion, String eventCode, String eventName,
                               String eventDirection, String eventRole, String eventTypeKey,
                               String eventTypeVersion, String callContractKey, String callContractVersion,
                               String localActionKey, String localActionVersion, String systemCode,
                               String systemName, String mode, String sourceStateCode, String targetStateCode,
                               String eventStatus, String outcome, Boolean sent, String checkpoint,
                               Integer attemptNo, String blockedReason, String waitReason,
                               List<String> expectedCallers, String deadline, String correlationId,
                               String externalTaskId, String inboundReceipt, String originOutboundEventId,
                               List<String> relatedEventIds, HttpContractVO httpContract,
                               List<PlanChainVO.ValidationVO> validations, List<RequestRetryVO> requestRetries,
                               Map<String, ActionDecisionVO> allowedActions, Map<String, String> reasons,
                               Boolean canRetry, Boolean canRevalidate, Boolean canFinalize, String asOf) {

    /**
     * HTTP 合同安全摘要；不包含地址凭据或报文体。
     *
     * @param method HTTP 方法
     * @param targetSystemCode 目标系统
     * @param endpointRef 端点引用
     * @param apiPath 接口路径
     * @param completionType 完成标准
     */
    public record HttpContractVO(String method, String targetSystemCode, String endpointRef, String apiPath,
                                 String completionType) {
    }

    /**
     * 请求重投摘要。
     *
     * @param attemptNo 尝试号
     * @param requestId 请求 ID
     * @param observedAt 观察时间
     * @param result 结果
     */
    public record RequestRetryVO(Integer attemptNo, String requestId, String observedAt, String result) {
    }

    /**
     * 动作可用性提示；写入入口仍执行最终授权和状态校验。
     *
     * @param allowed 是否允许
     * @param reason 不允许原因
     */
    public record ActionDecisionVO(Boolean allowed, String reason) {
    }
}
