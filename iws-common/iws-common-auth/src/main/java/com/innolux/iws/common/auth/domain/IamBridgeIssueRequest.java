/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 外层 IWS 为嵌入应用申请一次性桥接票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 嵌入应用桥接票据申请。
 *
 * @param targetApplicationId 目标应用标识
 * @param sourceFrontendId 来源受信前端
 * @param sourceSessionId 来源应用会话标识
 * @param sourceSessionGeneration 来源应用会话代次
 * @param sourceTerminal 来源终端
 * @param rootSid 中心根会话标识
 * @param returnPath 目标应用返回路径
 */
public record IamBridgeIssueRequest(
    String targetApplicationId,
    String sourceFrontendId,
    String sourceSessionId,
    long sourceSessionGeneration,
    String sourceTerminal,
    String rootSid,
    String returnPath
) {
}
