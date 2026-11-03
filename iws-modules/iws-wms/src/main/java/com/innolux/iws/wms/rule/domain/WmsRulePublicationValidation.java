/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布
 * @Version      : V1.0
 * @Description  : 返回规则发布校验冻结的包、作用域和空间摘要；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.domain;

import java.util.List;

/**
 * 规则发布校验结果。
 *
 * @param packageVersionId 规则包版本 ID
 * @param scopeType 作用域类型
 * @param scopeId 作用域 ID
 * @param canonicalDigest 规则包稳定摘要
 * @param storageIds 升序储位快照
 * @param previousPackageVersionId 校验时生效包版本，可为空
 * @param previousPointerRevision 校验时生效指针修订，可为空
 */
public record WmsRulePublicationValidation(
    long packageVersionId,
    String scopeType,
    long scopeId,
    String canonicalDigest,
    List<Long> storageIds,
    Long previousPackageVersionId,
    Integer previousPointerRevision
) {
    public WmsRulePublicationValidation {
        storageIds = List.copyOf(storageIds);
    }
}
