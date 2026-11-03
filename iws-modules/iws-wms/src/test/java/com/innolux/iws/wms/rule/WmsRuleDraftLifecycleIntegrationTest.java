/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 验证规则定义创建、草稿编辑、ACTIVE 拒绝原位修改、复制与退役；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.rule.controller.WmsRuleController;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionRetireCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则草稿生命周期集成测试。
 *
 * @author YONGJIE.PENG
 */
class WmsRuleDraftLifecycleIntegrationTest extends WmsRuleTestSupport {

    @Test
    void createDefinitionStoresFirstDraftAndReproducibleDigest() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-A", "INBOUND_ACCEPT", SCOPE_TYPE);

        assertThat(definition.status()).isEqualTo("ACTIVE");
        assertThat(definition.revision()).isEqualTo(1);
        assertThat(definition.currentVersionId()).isNull();
        assertThat(definition.activeVersionCount()).isZero();
        assertThat(definition.versions()).singleElement().satisfies(version -> {
            assertThat(version.status()).isEqualTo("DRAFT");
            assertThat(version.versionNo()).isEqualTo(1);
            assertThat(version.canonicalDigest()).matches("^[0-9a-f]{64}$");
            assertThat(version.condition().path("root").path("attribute").asText())
                .isEqualTo("materialCode");
            assertThat(version.result().path("type").asText()).isEqualTo("FILTER");
            assertThat(version.publishedAt()).isNull();
            assertThat(version.bindingSnapshotState()).isEqualTo("LEGACY_UNBOUND");
            assertThat(version.executionReady()).isFalse();
        });

        Long versionId = definition.versions().get(0).ruleVersionId();
        Map<String, Object> stored = jdbcTemplate.queryForMap(
            "select condition_json, result_json, canonical_digest from wms_rule_version "
                + "where rule_version_id = ?",
            versionId
        );
        String recomputed = WmsRuleDigest.ruleVersionDigest(
            json(String.valueOf(stored.get("condition_json"))),
            json(String.valueOf(stored.get("result_json"))),
            null
        );
        assertThat(recomputed).isEqualTo(stored.get("canonical_digest"));
    }

    @Test
    void draftCanBeEditedInPlaceAndDigestFollowsContent() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-B", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo draft = definition.versions().get(0);

        WmsRuleVersionVo edited = editDraft(
            draft,
            """
                {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
                "attribute":"batchNo","operator":"EQ","value":"B-01"}}
                """,
            DEFAULT_RESULT
        );

        assertThat(edited.status()).isEqualTo("DRAFT");
        assertThat(edited.versionNo()).isEqualTo(1);
        assertThat(edited.canonicalDigest()).isNotEqualTo(draft.canonicalDigest());
        assertThat(edited.condition().path("root").path("attribute").asText())
            .isEqualTo("batchNo");
        assertThat(edited.result().path("value").asBoolean()).isTrue();

        WmsRuleVersionVo staleEdit = draft;
        assertThatThrownBy(() -> editDraft(
            staleEdit,
            """
                {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
                "attribute":"supplierCode","operator":"EQ","value":"SP-01"}}
                """,
            DEFAULT_RESULT
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith("RULE_CONTENT_CHANGED");
            assertThat(error.getCode()).isEqualTo(409);
        });
    }

    @Test
    void activeVersionContentCannotBeModifiedInPlace() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-C", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));

        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.publishedAt()).isNotNull();
        assertThat(active.publishedBy()).isNotNull();
        assertThat(active.bindingSnapshotState()).isEqualTo("READY");
        assertThat(active.executionReady()).isTrue();

        assertThatThrownBy(() -> editDraft(
            active,
            """
                {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
                "attribute":"batchNo","operator":"EQ","value":"B-01"}}
                """,
            DEFAULT_RESULT
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith("RULE_VERSION_IMMUTABLE");
            assertThat(error.getCode()).isEqualTo(409);
        });

        jdbcTemplate.update(
            "update wms_rule_version set status = 'RETIRED' where rule_version_id = ?",
            active.ruleVersionId()
        );
        assertThatThrownBy(() -> editDraft(
            active,
            """
                {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
                "attribute":"batchNo","operator":"EQ","value":"B-01"}}
                """,
            DEFAULT_RESULT
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith("RULE_VERSION_RETIRED");
            assertThat(error.getCode()).isEqualTo(409);
        });
    }

    @Test
    void copyActiveVersionCreatesNextDraftWithSameContent() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-D", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));

        WmsRuleVersionVo second = copyDraft(active.ruleVersionId());
        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("DRAFT");
        assertThat(second.canonicalDigest()).isNotEqualTo(active.canonicalDigest());
        assertThat(second.bindingSnapshotState()).isEqualTo("LEGACY_UNBOUND");
        assertThat(second.condition().path("root").path("value").asText())
            .isEqualTo("MAT-01");

        WmsRuleVersionVo third = copyDraft(active.ruleVersionId());
        assertThat(third.versionNo()).isEqualTo(3);
        assertThat(third.status()).isEqualTo("DRAFT");

        assertThatThrownBy(() -> copyDraft(second.ruleVersionId()))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_VERSION_NOT_ACTIVE");
                assertThat(error.getCode()).isEqualTo(409);
            });
    }

    @Test
    void retireDefinitionKeepsVersionsAndStopsNewReferences() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-E", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        int currentRevision = jdbcTemplate.queryForObject(
            "select revision from wms_rule_definition where rule_definition_id = ?",
            Integer.class, definition.ruleDefinitionId()
        );

        WmsRuleDefinitionVo retired = retire(definition, currentRevision);
        assertThat(retired.status()).isEqualTo("DISABLED");
        assertThat(retired.revision()).isEqualTo(currentRevision + 1);
        assertThat(retired.activeVersionCount()).isEqualTo(1);
        assertThat(retired.versions()).singleElement()
            .satisfies(version -> assertThat(version.status()).isEqualTo("ACTIVE"));
        assertThat(countRows("wms_rule_version", "rule_definition_id = ?",
            definition.ruleDefinitionId())).isEqualTo(1);

        assertThatThrownBy(() -> retire(definition, currentRevision + 1))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_DEFINITION_RETIRED");
                assertThat(error.getCode()).isEqualTo(409);
            });

        assertThatThrownBy(() -> copyDraft(active.ruleVersionId()))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_DEFINITION_NOT_ACTIVE");
                assertThat(error.getCode()).isEqualTo(409);
            });
    }

    @Test
    void draftCommandsRequireInventoryRuleDraftPermission() {
        WmsRuleTestAuth.login(2L, Set.of("inventory:rule:query"));
        assertThatThrownBy(() -> createDefinition("RULE-F", "INBOUND_ACCEPT", SCOPE_TYPE))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void controllerDeclaresRulePermissionsAndRoutes() throws Exception {
        RequestMapping base = WmsRuleController.class.getAnnotation(RequestMapping.class);
        assertThat(base.value()).containsExactly("/wms/inventory/rule");

        assertPermission("attributes", new Class<?>[]{PageQuery.class}, "inventory:rule:query");
        assertPermission("effective", new Class<?>[]{String.class, Long.class}, "inventory:rule:query");
        assertPermission("createDefinition", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRuleDefinitionCreateCommand.class}, "inventory:rule:draft");
        assertPermission("editDraft", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRuleDraftEditCommand.class}, "inventory:rule:draft");
        assertPermission("copyDraft", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRuleVersionCopyCommand.class}, "inventory:rule:draft");
        assertPermission("retireDefinition", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRuleDefinitionRetireCommand.class}, "inventory:rule:draft");
        assertPermission("activateVersion", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRuleVersionActivateCommand.class}, "inventory:rule:publish");
        assertPermission("createPackage", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRulePackageCreateCommand.class}, "inventory:rule:publish");
        assertPermission("publishPackage", new Class<?>[]{com.innolux.iws.wms.rule.domain.command.
            WmsRulePackagePublishCommand.class}, "inventory:rule:publish");

        Method attributes = WmsRuleController.class.getDeclaredMethod("attributes", PageQuery.class);
        assertThat(attributes.getAnnotation(GetMapping.class).value()).containsExactly("/attributes");
        Method effective = WmsRuleController.class.getDeclaredMethod("effective", String.class, Long.class);
        assertThat(effective.getAnnotation(GetMapping.class).value()).containsExactly("/effective");
        Method publish = WmsRuleController.class.getDeclaredMethod("publishPackage",
            com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand.class);
        assertThat(publish.getAnnotation(PostMapping.class).value()).containsExactly("/packages/publish");
        Method edit = WmsRuleController.class.getDeclaredMethod("editDraft",
            com.innolux.iws.wms.rule.domain.command.WmsRuleDraftEditCommand.class);
        assertThat(edit.getAnnotation(PutMapping.class).value()).containsExactly("/versions/draft");
    }

    private WmsRuleDefinitionVo retire(WmsRuleDefinitionVo definition, int expectedRevision) {
        WmsRuleDefinitionRetireCommand command = new WmsRuleDefinitionRetireCommand();
        command.setCommandKey(newCommandKey("retire"));
        command.setRuleDefinitionId(definition.ruleDefinitionId());
        command.setExpectedRevision(expectedRevision);
        command.setReason("集成测试退役");
        return draftService.retireDefinition(command);
    }

    private static void assertPermission(String methodName, Class<?>[] parameterTypes,
                                         String expectedPermission) throws Exception {
        Method method = WmsRuleController.class.getDeclaredMethod(methodName, parameterTypes);
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
        assertThat(permission).as(methodName).isNotNull();
        assertThat(permission.value()).containsExactly(expectedPermission);
    }
}
