/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则定义视图，包含当前版本与全部版本；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

import java.util.List;

/**
 * 规则定义视图。
 *
 * @param ruleDefinitionId  规则定义 ID
 * @param ruleKey           规则键
 * @param purpose           用途
 * @param ruleName          规则名称
 * @param scopeType         作用域类型
 * @param status            定义状态：ACTIVE/DISABLED
 * @param currentVersionId  当前已发布版本 ID
 * @param revision          修订号
 * @param activeVersionCount 已发布版本数量；大于 0 时禁止物理删除
 * @param versions          全部内容版本
 * @author YONGJIE.PENG
 */
public record WmsRuleDefinitionVo(Long ruleDefinitionId, String ruleKey, String purpose,
                                  String ruleName, String scopeType, String status,
                                  Long currentVersionId, Integer revision, long activeVersionCount,
                                  List<WmsRuleVersionVo> versions) {
}
