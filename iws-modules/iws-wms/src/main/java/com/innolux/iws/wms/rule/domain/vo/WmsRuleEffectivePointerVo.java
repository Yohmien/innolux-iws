/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包生效指针视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

/**
 * 规则包生效指针视图。
 *
 * @param scopeType        作用域类型
 * @param scopeId          作用域内部 ID
 * @param packageVersionId 当前规则包版本 ID
 * @param revision         CAS 修订号
 * @author YONGJIE.PENG
 */
public record WmsRuleEffectivePointerVo(String scopeType, Long scopeId, Long packageVersionId,
                                        Integer revision) {
}
