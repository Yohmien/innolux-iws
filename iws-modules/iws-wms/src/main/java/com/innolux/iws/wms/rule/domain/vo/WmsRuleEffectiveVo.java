/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则生效读取视图，绑定生效指针、包版本内容与逻辑归属版本；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

/**
 * 规则生效读取视图。
 *
 * <p>调用方必须把 {@code pointer.revision} 与 {@code packageVersion.canonicalDigest}、
 * {@code logicalScopeVersion.scopeVersionId} 一并持久绑定，才能冻结本次读取的规则内容和逻辑归属。</p>
 *
 * @param scopeType            作用域类型
 * @param scopeId              作用域内部 ID
 * @param pointer              当前生效指针
 * @param packageVersion       指针指向的规则包版本内容
 * @param logicalScopeVersion  同一作用域当前逻辑归属版本
 * @author YONGJIE.PENG
 */
public record WmsRuleEffectiveVo(String scopeType, Long scopeId, WmsRuleEffectivePointerVo pointer,
                                 WmsRulePackageVersionVo packageVersion,
                                 WmsLogicalScopeVersionVo logicalScopeVersion) {
}
