/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包成员视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

/**
 * 规则包成员视图。
 *
 * @param purpose       成员用途
 * @param ruleVersionId 规则版本 ID
 * @param revision      修订号
 * @param scopeType     规则定义作用域类型
 * @param ruleVersion   规则版本内容；版本被删除时为 null
 * @author YONGJIE.PENG
 */
public record WmsRulePackageMemberVo(String purpose, Long ruleVersionId, Integer revision,
                                     String scopeType,
                                     WmsRuleVersionVo ruleVersion) {
}
