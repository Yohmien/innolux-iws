/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则内容版本视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 规则内容版本视图。
 *
 * @param ruleVersionId    规则版本 ID
 * @param ruleDefinitionId 规则定义 ID
 * @param versionNo        版本号
 * @param status           发布状态
 * @param canonicalDigest  规范化内容摘要
 * @param condition        条件表达式
 * @param result           结果表达式
 * @param publishedAt      发布时间
 * @param publishedBy      发布人
 * @author YONGJIE.PENG
 */
public record WmsRuleVersionVo(Long ruleVersionId, Long ruleDefinitionId, Integer versionNo,
                               String status, String canonicalDigest, JsonNode condition,
                               JsonNode result, LocalDateTime publishedAt, Long publishedBy,
                               String bindingSnapshotState, boolean executionReady) {
}
