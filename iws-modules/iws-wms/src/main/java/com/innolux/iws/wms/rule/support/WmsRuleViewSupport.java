/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则实体到只读视图的转换；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.support;

import cn.hutool.core.util.StrUtil;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeVersion;
import com.innolux.iws.wms.rule.domain.WmsRuleAttributeRow;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleEffectivePointer;
import com.innolux.iws.wms.rule.domain.WmsRulePackageVersion;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.vo.WmsLogicalScopeVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectivePointerVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageMemberVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 规则视图转换。
 *
 * @author YONGJIE.PENG
 */
public final class WmsRuleViewSupport {

    private WmsRuleViewSupport() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @param text JSON 文本
     * @return JSON 节点；空白文本返回 null
     */
    public static JsonNode readJson(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        return JsonUtils.getJsonMapper().readTree(text);
    }

    /**
     * 转换属性目录视图。
     *
     * @param row 属性目录行
     * @return 属性目录视图
     */
    public static WmsRuleAttributeVo toAttributeVo(WmsRuleAttributeRow row) {
        return new WmsRuleAttributeVo(
            row.getAttributeKey(),
            row.getSourceType(),
            row.getValueType(),
            row.getCardinality(),
            readJson(row.getOperatorsJson()),
            row.getUnit(),
            row.getScale(),
            row.getGroupable(),
            row.getContractVersion(),
            row.getRevision()
        );
    }

    /**
     * 转换规则版本视图。
     *
     * @param version 规则版本
     * @return 规则版本视图；入参为 null 时返回 null
     */
    public static WmsRuleVersionVo toVersionVo(WmsRuleVersion version) {
        return toVersionVo(version, version == null || version.getAttributeBindingsJson() == null ? "LEGACY_UNBOUND" : "UNVERIFIED", false);
    }

    public static WmsRuleVersionVo toVersionVo(WmsRuleVersion version, String bindingSnapshotState,
                                              boolean executionReady) {
        if (version == null) {
            return null;
        }
        return new WmsRuleVersionVo(
            version.getRuleVersionId(),
            version.getRuleDefinitionId(),
            version.getVersionNo(),
            version.getStatus(),
            version.getCanonicalDigest(),
            readJson(version.getConditionJson()),
            readJson(version.getResultJson()),
            version.getPublishedAt(),
            version.getPublishedBy(),
            bindingSnapshotState,
            executionReady
        );
    }

    /**
     * 转换规则定义视图。
     *
     * @param definition 规则定义
     * @param versions   全部版本，按版本号升序
     * @return 规则定义视图
     */
    public static WmsRuleDefinitionVo toDefinitionVo(WmsRuleDefinition definition,
                                                     List<WmsRuleVersionVo> versions) {
        long activeCount = versions.stream()
            .filter(version -> WmsRuleContract.STATUS_ACTIVE.equals(version.status()))
            .count();
        return new WmsRuleDefinitionVo(
            definition.getRuleDefinitionId(),
            definition.getRuleKey(),
            definition.getPurpose(),
            definition.getRuleName(),
            definition.getScopeType(),
            definition.getStatus(),
            definition.getCurrentVersionId(),
            definition.getRevision(),
            activeCount,
            versions
        );
    }

    /**
     * 转换生效指针视图。
     *
     * @param pointer 生效指针
     * @return 生效指针视图
     */
    public static WmsRuleEffectivePointerVo toPointerVo(WmsRuleEffectivePointer pointer) {
        return new WmsRuleEffectivePointerVo(
            pointer.getScopeType(),
            pointer.getScopeId(),
            pointer.getPackageVersionId(),
            pointer.getRevision()
        );
    }

    /**
     * 转换规则包版本视图。
     *
     * @param packageVersion 规则包版本
     * @param members        成员视图
     * @return 规则包版本视图
     */
    public static WmsRulePackageVersionVo toPackageVo(WmsRulePackageVersion packageVersion,
                                                      List<WmsRulePackageMemberVo> members) {
        return new WmsRulePackageVersionVo(
            packageVersion.getPackageVersionId(),
            packageVersion.getPackageCode(),
            packageVersion.getVersionNo(),
            packageVersion.getCanonicalDigest(),
            packageVersion.getStatus(),
            packageVersion.getPublishedAt(),
            packageVersion.getPublishedBy(),
            members
        );
    }

    /**
     * 转换逻辑归属版本视图。
     *
     * @param version    逻辑归属版本
     * @param scopeCode  逻辑归属编码
     * @param storageIds 储位快照
     * @return 逻辑归属版本视图
     */
    public static WmsLogicalScopeVersionVo toScopeVersionVo(WmsLogicalScopeVersion version,
                                                            String scopeCode,
                                                            List<Long> storageIds) {
        return new WmsLogicalScopeVersionVo(
            version.getScopeVersionId(),
            version.getLogicalScopeId(),
            scopeCode,
            version.getVersionNo(),
            version.getRulePackageVersionId(),
            version.getSpaceSnapshotDigest(),
            version.getStatus(),
            version.getPublishedAt(),
            version.getPublishedBy(),
            storageIds
        );
    }
}
