/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 验证发布校验回滚、首次 revision=1、二次 CAS 与逻辑归属版本递增；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.rule.domain.WmsRulePublicationValidation;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.process.WmsRuleActivateAction;
import com.innolux.iws.wms.rule.process.WmsRulePlanContract;
import com.innolux.iws.wms.rule.process.WmsRuleValidateAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则包发布集成测试。
 *
 * @author YONGJIE.PENG
 */
class WmsRulePublishIntegrationTest extends WmsRuleTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void createPackageRejectsDraftReferenceWithoutWriting() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-P1", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo draft = definition.versions().get(0);

        assertThatThrownBy(() -> createPackage(
            "PKG-P1", Map.of("INBOUND_ACCEPT", draft.ruleVersionId())
        ))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_VERSION_NOT_ACTIVE");
                assertThat(error.getCode()).isEqualTo(409);
            });

        assertThat(countRows("wms_rule_package_version", "package_code = ?", "PKG-P1"))
            .isZero();
        assertThat(countRows("wms_rule_package_member", "1 = 1")).isZero();
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_storage", "1 = 1")).isZero();
    }

    @Test
    void firstPublishCreatesRevisionOnePointerAndScopeSnapshot() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-P2", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-P2", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );

        WmsRuleEffectiveVo effective = publish(
            packageVersion.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(20L, 10L)
        );

        assertThat(effective.pointer().revision()).isEqualTo(1);
        assertThat(effective.pointer().scopeType()).isEqualTo(SCOPE_TYPE);
        assertThat(effective.pointer().scopeId()).isEqualTo(SCOPE_ID);
        assertThat(effective.pointer().packageVersionId()).isEqualTo(packageVersion.packageVersionId());
        assertThat(effective.packageVersion().status()).isEqualTo("ACTIVE");
        assertThat(effective.packageVersion().publishedAt()).isNotNull();
        assertThat(effective.packageVersion().canonicalDigest()).isEqualTo(packageVersion.canonicalDigest());
        assertThat(effective.packageVersion().members()).singleElement().satisfies(member -> {
            assertThat(member.purpose()).isEqualTo("INBOUND_ACCEPT");
            assertThat(member.ruleVersion().status()).isEqualTo("ACTIVE");
        });
        assertThat(effective.logicalScopeVersion().scopeCode()).isEqualTo("WAREHOUSE:501");
        assertThat(effective.logicalScopeVersion().versionNo()).isEqualTo(1);
        assertThat(effective.logicalScopeVersion().status()).isEqualTo("ACTIVE");
        assertThat(effective.logicalScopeVersion().rulePackageVersionId())
            .isEqualTo(packageVersion.packageVersionId());
        assertThat(effective.logicalScopeVersion().storageIds()).containsExactly(10L, 20L);
        assertThat(effective.logicalScopeVersion().spaceSnapshotDigest()).matches("^[0-9a-f]{64}$");

        assertThat(countRows("wms_rule_effective_pointer", "scope_type = ? and scope_id = ?",
            SCOPE_TYPE, SCOPE_ID)).isEqualTo(1);
        assertThat(countRows("wms_logical_scope", "scope_code = ?", "WAREHOUSE:501")).isEqualTo(1);
        assertThat(countRows("wms_logical_scope_storage", "scope_version_id = ?",
            effective.logicalScopeVersion().scopeVersionId())).isEqualTo(2);
    }

    @Test
    void planValidationFreezesInputWithoutActivatingPackageOrPointer() {
        WmsRuleDefinitionVo definition = createDefinition(
            "RULE-PLAN-VALIDATE",
            "INBOUND_ACCEPT",
            SCOPE_TYPE
        );
        WmsRuleVersionVo active = activate(definition.versions().getFirst());
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-PLAN-VALIDATE",
            Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackagePublishCommand command = publishCommand(
            packageVersion.packageVersionId(),
            newCommandKey("plan-validate")
        );

        WmsRulePublicationValidation validation = inTransaction(() ->
            publishService.validateWithinTransaction(command));

        assertThat(validation.packageVersionId()).isEqualTo(packageVersion.packageVersionId());
        assertThat(validation.canonicalDigest()).isEqualTo(packageVersion.canonicalDigest());
        assertThat(validation.storageIds()).containsExactly(10L, 20L);
        assertThat(validation.previousPointerRevision()).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_rule_package_version where package_version_id = ?",
            String.class,
            packageVersion.packageVersionId()
        )).isEqualTo("DRAFT");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope", "1 = 1")).isZero();
    }

    @Test
    void planActivationCommitsPackagePointerAndLogicalScopeTogether() {
        WmsRuleDefinitionVo definition = createDefinition(
            "RULE-PLAN-ACTIVATE",
            "INBOUND_ACCEPT",
            SCOPE_TYPE
        );
        WmsRuleVersionVo active = activate(definition.versions().getFirst());
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-PLAN-ACTIVATE",
            Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackagePublishCommand command = publishCommand(
            packageVersion.packageVersionId(),
            newCommandKey("plan-activate")
        );

        inTransaction(() -> publishService.validateWithinTransaction(command));
        WmsRuleEffectiveVo effective = inTransaction(() ->
            publishService.publishWithinTransaction(command));

        assertThat(effective.pointer().packageVersionId())
            .isEqualTo(packageVersion.packageVersionId());
        assertThat(effective.logicalScopeVersion().versionNo()).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_rule_package_version where package_version_id = ?",
            String.class,
            packageVersion.packageVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isOne();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isOne();
    }

    @Test
    void validateAndActivateActionsCommitThroughFrozenTaskIdentityAndReplay() {
        WmsRuleDefinitionVo definition = createDefinition(
            "RULE-PLAN-ACTION",
            "INBOUND_ACCEPT",
            SCOPE_TYPE
        );
        WmsRuleVersionVo active = activate(definition.versions().getFirst());
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-PLAN-ACTION",
            Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackagePublishCommand command = publishCommand(
            packageVersion.packageVersionId(),
            newCommandKey("plan-action")
        );
        ProcessInstanceMapper instances = instanceFor(command);
        WmsFrozenActionInputReader inputReader = mock(WmsFrozenActionInputReader.class);
        WmsRuleValidateAction validate = new WmsRuleValidateAction(
            publishService, instances, JSON, inputReader);
        WmsRuleActivateAction activateAction = new WmsRuleActivateAction(
            publishService, instances, JSON, inputReader);

        ActionResult validated = inTransaction(() -> validate.execute(actionContext(
            command,
            WmsRulePlanContract.VALIDATE_LOCAL_EFFECT_KEY
        )));
        assertThat(validated.checkpointJson()).contains("\"status\":\"VALIDATED\"");
        assertThat(packageStatus(packageVersion.packageVersionId())).isEqualTo("DRAFT");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();

        ActionResult activated = inTransaction(() -> activateAction.execute(actionContext(
            command,
            WmsRulePlanContract.ACTIVATE_LOCAL_EFFECT_KEY
        )));
        ActionResult replayed = inTransaction(() -> activateAction.execute(actionContext(
            command,
            WmsRulePlanContract.ACTIVATE_LOCAL_EFFECT_KEY
        )));

        assertThat(activated.checkpointJson()).contains("\"status\":\"PUBLISHED\"");
        assertThat(replayed.checkpointJson()).isEqualTo(activated.checkpointJson());
        assertThat(packageStatus(packageVersion.packageVersionId())).isEqualTo("ACTIVE");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isOne();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isOne();

        WmsRulePackagePublishCommand different =
            publishCommand(packageVersion.packageVersionId(), command.getCommandKey());
        different.setStorageIds(List.of(10L));
        assertThatThrownBy(() -> inTransaction(() -> activateAction.execute(actionContext(
            different,
            WmsRulePlanContract.ACTIVATE_LOCAL_EFFECT_KEY
        )))).isInstanceOf(ServiceException.class);
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isOne();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isOne();
    }

    @Test
    void activateActionRollsBackPackagePointerAndScopeWhenPointerWriteFails() {
        WmsRuleDefinitionVo definition = createDefinition(
            "RULE-PLAN-ROLLBACK",
            "INBOUND_ACCEPT",
            SCOPE_TYPE
        );
        WmsRuleVersionVo active = activate(definition.versions().getFirst());
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-PLAN-ROLLBACK",
            Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackagePublishCommand command = publishCommand(
            packageVersion.packageVersionId(),
            newCommandKey("plan-action-rollback")
        );
        ProcessInstanceMapper instances = instanceFor(command);
        WmsRuleActivateAction action = new WmsRuleActivateAction(
            publishService,
            instances,
            JSON,
            mock(WmsFrozenActionInputReader.class)
        );
        WmsRuleTestDatabase.executeAsRoot("""
            create trigger trg_wms_rule_pointer_failure
            before insert on wms_rule_effective_pointer
            for each row
            signal sqlstate '45000' set message_text = 'forced pointer failure'
            """);
        try {
            assertThatThrownBy(() -> inTransaction(() -> action.execute(actionContext(
                command,
                WmsRulePlanContract.ACTIVATE_LOCAL_EFFECT_KEY
            )))).isInstanceOf(RuntimeException.class);
        } finally {
            WmsRuleTestDatabase.executeAsRoot(
                "drop trigger if exists trg_wms_rule_pointer_failure");
        }

        assertThat(packageStatus(packageVersion.packageVersionId())).isEqualTo("DRAFT");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isZero();
    }

    private WmsRulePackagePublishCommand publishCommand(
        Long packageVersionId,
        String commandKey
    ) {
        WmsRulePackagePublishCommand command = new WmsRulePackagePublishCommand();
        command.setCommandKey(commandKey);
        command.setPackageVersionId(packageVersionId);
        command.setScopeType(SCOPE_TYPE);
        command.setScopeId(SCOPE_ID);
        command.setStorageIds(List.of(20L, 10L));
        command.setReason("规则发布计划集成测试");
        return command;
    }

    private ProcessInstanceMapper instanceFor(WmsRulePackagePublishCommand command) {
        ProcessInstanceMapper mapper = mock(ProcessInstanceMapper.class);
        ProcessInstance instance = new ProcessInstance();
        instance.setId(9001L);
        instance.setBusinessType(WmsRulePlanContract.BUSINESS_TYPE);
        instance.setBusinessId(String.valueOf(command.getPackageVersionId()));
        instance.setTaskKey(command.getCommandKey());
        when(mapper.selectById(9001L)).thenReturn(instance);
        return mapper;
    }

    private ActionContext actionContext(
        WmsRulePackagePublishCommand command,
        String localEffectKey
    ) {
        return new ActionContext(
            1001L,
            9001L,
            localEffectKey,
            2001L,
            "rule-action-test",
            EventDirection.INTERNAL,
            PostingMode.AUTOMATIC,
            "WMS:" + command.getCommandKey(),
            localEffectKey,
            command.getCommandKey(),
            "a".repeat(64),
            JSON.writeValueAsString(command),
            "wms-business-task:9101",
            1L,
            30_000L,
            Map.of()
        );
    }

    private <T> T inTransaction(Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return Objects.requireNonNull(template.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }));
    }

    @Test
    void secondPublishSwitchesPointerByRevisionCasAndIncrementsScopeVersion() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-P3", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo firstActive = activate(definition.versions().get(0));
        WmsRulePackageVersionVo firstPackage = createPackage(
            "PKG-P3", Map.of("INBOUND_ACCEPT", firstActive.ruleVersionId())
        );
        WmsRuleEffectiveVo first = publish(
            firstPackage.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L, 20L)
        );

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
            "PKG-P3", Map.of("INBOUND_ACCEPT", secondActive.ruleVersionId())
        );
        assertThat(secondPackage.versionNo()).isEqualTo(2);

        WmsRuleEffectiveVo second = publish(
            secondPackage.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L, 30L)
        );

        assertThat(second.pointer().revision()).isEqualTo(2);
        assertThat(second.pointer().packageVersionId()).isEqualTo(secondPackage.packageVersionId());
        assertThat(second.packageVersion().versionNo()).isEqualTo(2);
        assertThat(second.logicalScopeVersion().versionNo()).isEqualTo(2);
        assertThat(second.logicalScopeVersion().storageIds()).containsExactly(10L, 30L);
        assertThat(second.logicalScopeVersion().scopeVersionId())
            .isNotEqualTo(first.logicalScopeVersion().scopeVersionId());

        assertThat(countRows("wms_rule_effective_pointer", "scope_type = ? and scope_id = ?",
            SCOPE_TYPE, SCOPE_ID)).isEqualTo(1);
        assertThat(countRows("wms_logical_scope_version", "logical_scope_id = ?",
            first.logicalScopeVersion().logicalScopeId())).isEqualTo(2);
        assertThat(countRows("wms_logical_scope", "1 = 1")).isEqualTo(1);
        assertThat(packageStatus(firstPackage.packageVersionId())).isEqualTo("ACTIVE");
    }

    @Test
    void publishRequiresInventoryRulePublishPermission() {
        WmsRuleTestAuth.login(2L, Set.of("inventory:rule:query", "inventory:rule:draft"));
        assertThatThrownBy(() -> publish(999999L, SCOPE_TYPE, SCOPE_ID, List.of(10L)))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void publishOnlyUserCanPublishAndReplayWithoutQueryPermission() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-P4", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-P4", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        String commandKey = newCommandKey("publish-only");
        grantRole(2L, 200L, "inventory:rule:publish");
        grantCompany(200L, COMPANY_ID);
        WmsRuleTestAuth.login(2L, Set.of("inventory:rule:publish"));

        WmsRuleEffectiveVo published = publish(
            packageVersion.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L), commandKey
        );
        WmsRuleEffectiveVo replayed = publish(
            packageVersion.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L), commandKey
        );

        assertThat(published.pointer().packageVersionId()).isEqualTo(packageVersion.packageVersionId());
        assertThat(replayed.pointer()).isEqualTo(published.pointer());
        assertThat(replayed.packageVersion()).isEqualTo(published.packageVersion());
        assertThat(countRows("wms_command_attempt", "command_key = ?", commandKey)).isEqualTo(1);
        assertThat(countRows("wms_object_audit", "command_key = ?", commandKey)).isEqualTo(1);

        assertThatThrownBy(() -> queryService.getEffective(SCOPE_TYPE, SCOPE_ID))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void createPackageRejectsMixedDefinitionScopeTypesWithoutWritingPackage() {
        WmsRuleDefinitionVo warehouse = createDefinition("RULE-SCOPE-W", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleDefinitionVo storage = createDefinition("RULE-SCOPE-S", "OUTBOUND_ALLOCATE", "STORAGE");
        WmsRuleVersionVo warehouseActive = activate(warehouse.versions().get(0));
        WmsRuleVersionVo storageActive = activate(storage.versions().get(0));

        assertThatThrownBy(() -> createPackage(
            "PKG-SCOPE-MIXED",
            Map.of(
                "INBOUND_ACCEPT", warehouseActive.ruleVersionId(),
                "OUTBOUND_ALLOCATE", storageActive.ruleVersionId()
            )
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith("RULE_PACKAGE_SCOPE_MISMATCH");
            assertThat(error.getCode()).isEqualTo(409);
        });

        assertThat(countRows("wms_rule_package_version", "package_code = ?", "PKG-SCOPE-MIXED"))
            .isZero();
        assertThat(countRows("wms_rule_package_member", "1 = 1")).isZero();
    }

    @Test
    void publishRejectsScopeMismatchAndRollsBackPackagePointerAndSnapshot() {
        WmsRuleDefinitionVo definition = createDefinition("RULE-SCOPE-P", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo existingPackage = createPackage(
            "PKG-SCOPE-P", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRuleEffectiveVo existing = publish(
            existingPackage.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(10L)
        );
        WmsRulePackageVersionVo rejectedPackage = createPackage(
            "PKG-SCOPE-P", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );

        assertThatThrownBy(() -> publish(
            rejectedPackage.packageVersionId(), "STORAGE", 601L, List.of(20L)
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith("RULE_PACKAGE_SCOPE_MISMATCH");
            assertThat(error.getCode()).isEqualTo(409);
        });

        assertThat(packageStatus(rejectedPackage.packageVersionId())).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForMap(
            "select package_version_id, revision from wms_rule_effective_pointer "
                + "where scope_type = ? and scope_id = ?",
            SCOPE_TYPE, SCOPE_ID
        )).satisfies(row -> {
            assertThat(row.get("package_version_id")).isEqualTo(existingPackage.packageVersionId());
            assertThat(row.get("revision")).isEqualTo(existing.pointer().revision());
        });
        assertThat(countRows("wms_rule_effective_pointer", "scope_type = ? and scope_id = ?",
            "STORAGE", 601L)).isZero();
        assertThat(countRows("wms_logical_scope", "scope_code = ?", "STORAGE:601")).isZero();
        assertThat(countRows("wms_logical_scope_version", "rule_package_version_id = ?",
            rejectedPackage.packageVersionId())).isZero();
        assertThat(countRows("wms_logical_scope_storage", "storage_id = ?", 20L)).isZero();
        assertThat(countRows("wms_logical_scope", "scope_code = ?", "WAREHOUSE:501")).isEqualTo(1);
        assertThat(countRows("wms_logical_scope_version", "rule_package_version_id = ?",
            existingPackage.packageVersionId())).isEqualTo(1);
    }

    private String packageStatus(Long packageVersionId) {
        return jdbcTemplate.queryForObject(
            "select status from wms_rule_package_version where package_version_id = ?",
            String.class, packageVersionId
        );
    }
}
