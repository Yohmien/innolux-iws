/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 验证属性目录过滤与 A04 生效读取线性化；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleEffectivePointerMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则读取集成测试。
 *
 * <p>A04 验收以顺序读写验证同一指针的线性化语义：发布前读取得到 V1，发布后读取得到 V2，
 * 且旧包版本保持不可变可读。</p>
 *
 * @author YONGJIE.PENG
 */
class WmsRuleEffectiveReadIntegrationTest extends WmsRuleTestSupport {

    @Autowired
    private WmsRuleEffectivePointerMapper pointerMapper;

    @Autowired
    private WmsLogicalScopeMapper logicalScopeMapper;

    @Autowired
    private WmsRulePackageVersionMapper packageVersionMapper;

    @Test
    void attributeCatalogReturnsOnlyActiveRows() {
        jdbcTemplate.update("delete from wms_attribute_catalog");
        insertAttribute("materialCode", "MATERIAL", "STRING", "SCALAR", "[\"EQ\",\"IN\"]", null, 1,
            "ACTIVE", "ATTR-1");
        insertAttribute("batchNo", "MATERIAL", "STRING", "SCALAR", "[\"EQ\"]", null, 1,
            "DISABLED", "ATTR-1");

        PageResult<WmsRuleAttributeVo> page = queryService.queryAttributes(new PageQuery(10, 1));

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRows()).singleElement().satisfies(attribute -> {
            assertThat(attribute.attributeKey()).isEqualTo("materialCode");
            assertThat(attribute.sourceType()).isEqualTo("MATERIAL");
            assertThat(attribute.valueType()).isEqualTo("STRING");
            assertThat(attribute.cardinality()).isEqualTo("SCALAR");
            assertThat(attribute.operators()).hasSize(2);
            assertThat(attribute.operators().get(0).asText()).isEqualTo("EQ");
            assertThat(attribute.groupable()).isTrue();
            assertThat(attribute.contractVersion()).isEqualTo("ATTR-1");
            assertThat(attribute.revision()).isEqualTo(1);
        });

        WmsRuleTestAuth.login(2L, Set.of());
        assertThatThrownBy(() -> queryService.queryAttributes(new PageQuery(10, 1)))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void readBeforePublishBindsV1AndAfterPublishBindsV2() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-A04", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo firstActive = activate(definition.versions().get(0));
        WmsRulePackageVersionVo firstPackage = createPackage(
            "PKG-A04", Map.of("INBOUND_ACCEPT", firstActive.ruleVersionId())
        );
        WmsRuleEffectiveVo published = publish(
            firstPackage.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L)
        );
        assertThat(published.packageVersion().versionNo()).isEqualTo(1);
        assertThat(published.pointer().revision()).isEqualTo(1);

        WmsRuleVersionVo secondDraft = copyDraft(firstActive.ruleVersionId());
        WmsRuleVersionVo secondActive = activate(editDraft(
            secondDraft,
            """
                {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
                "attribute":"batchNo","operator":"EQ","value":"B-02"}}
                """,
            DEFAULT_RESULT
        ));
        WmsRulePackageVersionVo secondPackage = createPackage(
            "PKG-A04", Map.of("INBOUND_ACCEPT", secondActive.ruleVersionId())
        );

        WmsRuleEffectiveVo beforePublish = queryService.getEffective(SCOPE_TYPE, SCOPE_ID);
        assertThat(beforePublish.packageVersion().packageVersionId())
            .isEqualTo(firstPackage.packageVersionId());
        assertThat(beforePublish.packageVersion().versionNo()).isEqualTo(1);
        assertThat(beforePublish.packageVersion().canonicalDigest())
            .isEqualTo(firstPackage.canonicalDigest());
        assertThat(beforePublish.pointer().revision()).isEqualTo(1);
        assertThat(beforePublish.logicalScopeVersion().versionNo()).isEqualTo(1);

        WmsRuleEffectiveVo afterPublish = publish(
            secondPackage.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L)
        );
        assertThat(afterPublish.packageVersion().packageVersionId())
            .isEqualTo(secondPackage.packageVersionId());
        assertThat(afterPublish.packageVersion().versionNo()).isEqualTo(2);
        assertThat(afterPublish.packageVersion().canonicalDigest())
            .isEqualTo(secondPackage.canonicalDigest());
        assertThat(afterPublish.pointer().revision()).isEqualTo(2);

        WmsRuleEffectiveVo reread = queryService.getEffective(SCOPE_TYPE, SCOPE_ID);
        assertThat(reread.packageVersion().versionNo()).isEqualTo(2);
        assertThat(reread.packageVersion().members()).singleElement().satisfies(member ->
            assertThat(member.ruleVersion().condition().path("root").path("attribute").asText())
                .isEqualTo("batchNo")
        );

        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_rule_package_version where package_version_id = ?",
            String.class, firstPackage.packageVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
            "select revision from wms_rule_effective_pointer where scope_type = ? and scope_id = ?",
            Integer.class, SCOPE_TYPE, SCOPE_ID
        )).isEqualTo(2);
    }

    @Test
    void effectiveReadBeforeAnyPublishIsNotFound() {
        assertThatThrownBy(() -> queryService.getEffective(SCOPE_TYPE, 999L))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_EFFECTIVE_NOT_FOUND");
                assertThat(error.getCode()).isEqualTo(404);
            });
    }

    @Test
    void indexedCodeLookupsPreserveCaseAndTrailingSpaceExactness() {
        jdbcTemplate.update(
            "insert into wms_rule_effective_pointer "
                + "(scope_type, scope_id, package_version_id, revision) values "
                + "('WAREHOUSE', 70000, 101, 1), ('warehouse', 70000, 102, 1), "
                + "('WAREHOUSE ', 70000, 103, 1), ('WAREHOUSE', 70001, 104, 1)"
        );
        jdbcTemplate.update(
            "insert into wms_logical_scope "
                + "(scope_code, scope_name, lifecycle_status, revision) values "
                + "('WAREHOUSE:70000', 'upper', 'ACTIVE', 1), "
                + "('warehouse:70000', 'lower', 'ACTIVE', 1), "
                + "('WAREHOUSE:70000 ', 'trailing', 'ACTIVE', 1), "
                + "('WAREHOUSE:70001', 'next', 'ACTIVE', 1)"
        );
        String digestA = "a".repeat(64);
        String digestB = "b".repeat(64);
        String digestC = "c".repeat(64);
        String digestD = "d".repeat(64);
        jdbcTemplate.update(
            "insert into wms_rule_package_version "
                + "(package_code, version_no, canonical_digest, status) values "
                + "('PKG-EXACT', 1, ?, 'DRAFT'), ('pkg-exact', 1, ?, 'DRAFT'), "
                + "('PKG-EXACT ', 1, ?, 'DRAFT'), ('PKG-EXACT', 2, ?, 'DRAFT')",
            digestA, digestB, digestC, digestD
        );

        assertThat(pointerMapper.selectByScopeForShare("WAREHOUSE", 70000L))
            .extracting(pointer -> pointer.getPackageVersionId())
            .isEqualTo(101L);
        assertThat(pointerMapper.selectByScopeForShare("warehouse", 70000L))
            .extracting(pointer -> pointer.getPackageVersionId())
            .isEqualTo(102L);
        assertThat(pointerMapper.selectByScopeForShare("WAREHOUSE ", 70000L))
            .extracting(pointer -> pointer.getPackageVersionId())
            .isEqualTo(103L);
        assertThat(logicalScopeMapper.selectByCode("WAREHOUSE:70000").getScopeName())
            .isEqualTo("upper");
        assertThat(logicalScopeMapper.selectByCode("warehouse:70000").getScopeName())
            .isEqualTo("lower");
        assertThat(logicalScopeMapper.selectByCode("WAREHOUSE:70000 ").getScopeName())
            .isEqualTo("trailing");
        assertThat(packageVersionMapper.selectMaxVersionNo("PKG-EXACT")).isEqualTo(2);
        assertThat(packageVersionMapper.selectMaxVersionNo("pkg-exact")).isEqualTo(1);
        assertThat(packageVersionMapper.selectMaxVersionNo("PKG-EXACT ")).isEqualTo(1);
    }

    private void insertAttribute(String attributeKey, String sourceType, String valueType,
                                 String cardinality, String operatorsJson, String unit, int groupable,
                                 String status, String contractVersion) {
        jdbcTemplate.update(
            "insert into wms_attribute_catalog (attribute_key, source_type, value_type, cardinality, "
                + "operators_json, unit, scale, groupable, status, contract_version, revision) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
            attributeKey, sourceType, valueType, cardinality, operatorsJson, unit, null, groupable,
            status, contractVersion
        );
    }
}
