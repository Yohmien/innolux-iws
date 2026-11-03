/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 在调用者事务内追加完整成功事实并读取不可变快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceAppendCommand;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceEdgeInput;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceEdgeSnapshot;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceHistorySnapshot;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemInput;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemRef;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemSnapshot;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceKind;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.mapper.WmsMaterialTraceEdgeRow;
import com.innolux.iws.wms.material.trace.mapper.WmsMaterialTraceHistoryRow;
import com.innolux.iws.wms.material.trace.mapper.WmsMaterialTraceItemRow;
import com.innolux.iws.wms.material.trace.mapper.WmsMaterialTraceMapper;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内部物料 trace 追加服务。
 *
 * <p>服务只读取和插入三张 trace 表，不查询或修改库存，不从当前名称表重建历史。事实头、
 * 物料项和家谱边必须与调用者事务一起提交或回滚。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsMaterialTraceServiceImpl implements WmsMaterialTraceService {

    private static final Pattern EFFECT_KEY_PATTERN =
        Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Pattern INPUT_HASH_PATTERN =
        Pattern.compile("^[0-9a-f]{64}$");

    private static final int DECIMAL_SCALE = 6;

    private static final int DECIMAL_INTEGER_DIGITS = 14;

    private static final int MAX_TRACE_ID_ATTEMPTS = 3;

    private final WmsMaterialTraceMapper traceMapper;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    /**
     * 在调用者现有事务内追加完整成功事实。
     *
     * @param command 追加命令
     * @return 已写入完整快照
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsMaterialTraceSnapshot append(WmsMaterialTraceAppendCommand command) {
        validateEnvelope(command);
        WmsMaterialTraceSnapshot existing = loadByEffectKey(command.effectKey());
        if (existing != null) {
            return replay(existing, command.inputHash());
        }

        PreparedTrace prepared = prepare(command);
        try {
            assertWritten(traceMapper.insertHistory(prepared.history()), "trace事实头");
        } catch (DuplicateKeyException duplicateKey) {
            WmsMaterialTraceHistoryRow concurrent = traceMapper.selectHistoryByEffectKeyForUpdate(
                command.effectKey()
            );
            if (concurrent == null) {
                throw duplicateKey;
            }
            return replay(loadSnapshot(concurrent.getTraceId()), command.inputHash());
        }

        for (WmsMaterialTraceItemRow item : prepared.items()) {
            assertWritten(traceMapper.insertItem(item), "trace物料项");
        }
        for (WmsMaterialTraceEdgeRow edge : prepared.edges()) {
            assertWritten(traceMapper.insertEdge(edge), "trace家谱边");
        }

        WmsMaterialTraceSnapshot written = loadSnapshot(prepared.history().getTraceId());
        if (written.items().size() != prepared.items().size()
            || written.edges().size() != prepared.edges().size()) {
            throw new IllegalStateException("物料trace写入后快照数量不一致");
        }
        return written;
    }

    /**
     * 按效果键读取已写入快照。
     *
     * @param effectKey 领域效果键
     * @return 快照；不存在时为空
     */
    @Override
    public Optional<WmsMaterialTraceSnapshot> findByEffectKey(String effectKey) {
        validateEffectKey(effectKey);
        return Optional.ofNullable(loadByEffectKey(effectKey));
    }

    /**
     * 按 trace ID 读取已写入快照。
     *
     * @param traceId trace ID
     * @return 快照；不存在时为空
     */
    @Override
    public Optional<WmsMaterialTraceSnapshot> findByTraceId(long traceId) {
        if (traceId <= 0) {
            throw invalid(ERROR_INVALID_COMMAND, "traceId必须大于0");
        }
        return traceMapper.selectHistoryByTraceId(traceId) == null
            ? Optional.empty()
            : Optional.of(loadSnapshot(traceId));
    }

    private PreparedTrace prepare(WmsMaterialTraceAppendCommand command) {
        Map<String, WmsMaterialTraceItemInput> currentInputs = validateItems(command.items());
        long traceId = nextUniqueTraceId();
        Map<Long, WmsMaterialTraceItemRow> originalItems = loadOriginalItems(command, traceId);
        LocalDateTime recordedAt = LocalDateTime.now();

        Map<String, ResolvedItem> currentItems = new LinkedHashMap<>();
        List<WmsMaterialTraceItemRow> itemRows = new ArrayList<>(currentInputs.size());
        currentInputs.forEach((itemRef, input) -> {
            long itemId = nextId("trace物料项");
            currentItems.put(itemRef, new ResolvedItem(itemId, input.unit()));
            itemRows.add(toItemRow(command, traceId, itemId, input, recordedAt));
        });

        List<WmsMaterialTraceEdgeRow> edgeRows = toEdgeRows(
            command,
            traceId,
            currentItems,
            originalItems,
            recordedAt
        );
        validateAcyclic(edgeRows);
        return new PreparedTrace(
            toHistoryRow(command, traceId, recordedAt),
            List.copyOf(itemRows),
            List.copyOf(edgeRows)
        );
    }

    private void validateEnvelope(WmsMaterialTraceAppendCommand command) {
        if (command == null) {
            throw invalid(ERROR_INVALID_COMMAND, "追加命令不能为空");
        }
        validateEffectKey(command.effectKey());
        if (command.inputHash() == null || !INPUT_HASH_PATTERN.matcher(command.inputHash()).matches()) {
            throw invalid(ERROR_INVALID_COMMAND, "inputHash必须是小写64位SHA-256");
        }
        if (command.traceKind() == null) {
            throw invalid(ERROR_INVALID_COMMAND, "traceKind不能为空");
        }
        if (command.traceKind() == WmsMaterialTraceKind.FACT) {
            if (command.reverseTraceId() != null) {
                throw invalid(ERROR_COMPENSATION_INVALID, "FACT不得引用reverseTraceId");
            }
        } else if (command.reverseTraceId() == null || command.reverseTraceId() <= 0) {
            throw invalid(ERROR_COMPENSATION_INVALID, "COMPENSATION必须引用真实原trace");
        }
        requirePositive(command.businessId(), "businessId");
        requirePositive(command.rootBusinessId(), "rootBusinessId");
        validateOptionalPositive(command.businessLineId(), "businessLineId");
        validateOptionalPositive(command.planInstanceId(), "planInstanceId");
        validateOptionalPositive(command.eventId(), "eventId");
        validateOptionalPositive(command.attemptId(), "attemptId");
        validateOptionalPositive(command.actorId(), "actorId");
        validateOptionalPositive(command.departmentId(), "departmentId");
        requireText("businessType", command.businessType(), 64);
        requireText("businessName", command.businessName(), 128);
        requireText("rootBusinessType", command.rootBusinessType(), 64);
        optionalText("businessNo", command.businessNo(), 100);
        optionalText("taskKey", command.taskKey(), 96);
        optionalText("planCode", command.planCode(), 64);
        optionalText("planType", command.planType(), 64);
        optionalText("definitionVersion", command.definitionVersion(), 64);
        optionalText("reason", command.reason(), 500);
        optionalText("evidenceRef", command.evidenceRef(), 500);
        optionalText("actorName", command.actorName(), 128);
        optionalText("initiatorId", command.initiatorId(), 128);
        optionalText("initiatorName", command.initiatorName(), 128);
        optionalText("departmentName", command.departmentName(), 128);
        optionalText("executionSubject", command.executionSubject(), 16);
        optionalText("executionAccount", command.executionAccount(), 64);
        optionalText("onBehalfOf", command.onBehalfOf(), 128);
        optionalText("sourceSystem", command.sourceSystem(), 64);
        optionalText("terminalId", command.terminalId(), 128);
        optionalText("requestId", command.requestId(), 64);
        optionalText("sourceTraceId", command.sourceTraceId(), 128);
        if (command.occurredAt() == null) {
            throw invalid(ERROR_INVALID_COMMAND, "occurredAt不能为空");
        }
        if (command.items().isEmpty()) {
            throw invalid(ERROR_INVALID_COMMAND, "物料事实至少需要一个item");
        }
    }

    private Map<String, WmsMaterialTraceItemInput> validateItems(
        List<WmsMaterialTraceItemInput> inputs
    ) {
        Map<String, WmsMaterialTraceItemInput> byRef = new LinkedHashMap<>();
        for (WmsMaterialTraceItemInput input : inputs) {
            if (input == null) {
                throw invalid(ERROR_INVALID_COMMAND, "物料项不能为空");
            }
            requireText("itemRef", input.itemRef(), 64);
            if (byRef.putIfAbsent(input.itemRef(), input) != null) {
                throw invalid(ERROR_INVALID_COMMAND, "itemRef重复: " + input.itemRef());
            }
            requirePositive(input.inventoryId(), "inventoryId");
            requireText("stockUnitCode", input.stockUnitCode(), 100);
            optionalText("materialCode", input.materialCode(), 50);
            validateOptionalPositive(input.productionInfoId(), "productionInfoId");
            if (input.productionRevision() != null && input.productionRevision() < 1) {
                throw invalid(ERROR_INVALID_COMMAND, "productionRevision必须大于0");
            }
            optionalText("unit", input.unit(), 32);
            validateQuantity("quantity", input.quantity(), true);
            validateQuantity("beforeQty", input.beforeQty(), true);
            validateQuantity("deltaQty", input.deltaQty(), false);
            validateQuantity("afterQty", input.afterQty(), true);
            validateOptionalPositive(input.physicalFromStorageId(), "physicalFromStorageId");
            optionalText("physicalFromStorageCode", input.physicalFromStorageCode(), 64);
            validateOptionalPositive(input.physicalToStorageId(), "physicalToStorageId");
            optionalText("physicalToStorageCode", input.physicalToStorageCode(), 64);
            validateOptionalPositive(input.logicalFromScopeVersionId(), "logicalFromScopeVersionId");
            validateOptionalPositive(input.logicalToScopeVersionId(), "logicalToScopeVersionId");
            validateOptionalPositive(input.loadParentBeforeId(), "loadParentBeforeId");
            validateOptionalPositive(input.loadParentAfterId(), "loadParentAfterId");
            validateOptionalPositive(input.loadRootBeforeId(), "loadRootBeforeId");
            validateOptionalPositive(input.loadRootAfterId(), "loadRootAfterId");
            optionalText("lifecycleBefore", input.lifecycleBefore(), 32);
            optionalText("lifecycleAfter", input.lifecycleAfter(), 32);
            validateOptionalPositive(input.rulePackageVersionId(), "rulePackageVersionId");
        }
        return byRef;
    }

    private Map<Long, WmsMaterialTraceItemRow> loadOriginalItems(
        WmsMaterialTraceAppendCommand command,
        long newTraceId
    ) {
        if (command.traceKind() == WmsMaterialTraceKind.FACT) {
            return Map.of();
        }
        validateCompensationChain(command.reverseTraceId(), newTraceId);
        Map<Long, WmsMaterialTraceItemRow> byId = new HashMap<>();
        for (WmsMaterialTraceItemRow item : traceMapper.selectItemsByTraceId(command.reverseTraceId())) {
            byId.put(item.getItemId(), item);
        }
        return byId;
    }

    private void validateCompensationChain(long reverseTraceId, long newTraceId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = reverseTraceId;
        while (cursor != null) {
            if (cursor == newTraceId || !visited.add(cursor)) {
                throw invalid(ERROR_COMPENSATION_INVALID, "补偿引用形成自环或环");
            }
            WmsMaterialTraceHistoryRow row = traceMapper.selectHistoryByTraceIdForShare(cursor);
            if (row == null) {
                throw invalid(ERROR_COMPENSATION_INVALID, "补偿引用的原trace不存在: " + cursor);
            }
            cursor = row.getReverseTraceId();
        }
    }

    private List<WmsMaterialTraceEdgeRow> toEdgeRows(
        WmsMaterialTraceAppendCommand command,
        long traceId,
        Map<String, ResolvedItem> currentItems,
        Map<Long, WmsMaterialTraceItemRow> originalItems,
        LocalDateTime recordedAt
    ) {
        List<WmsMaterialTraceEdgeRow> rows = new ArrayList<>(command.edges().size());
        Set<String> uniqueEdges = new HashSet<>();
        for (WmsMaterialTraceEdgeInput input : command.edges()) {
            if (input == null) {
                throw edgeInvalid("家谱边不能为空");
            }
            validateQuantity("edge.quantity", input.quantity(), false);
            if (input.quantity() == null || input.quantity().signum() <= 0) {
                throw edgeInvalid("家谱边数量必须大于0");
            }
            requireText(ERROR_EDGE_INVALID, "edge.unit", input.unit(), 32);
            if (input.groupNo() < 1) {
                throw edgeInvalid("groupNo必须大于0");
            }
            ResolvedItem source = resolveItem(
                command,
                input.source(),
                currentItems,
                originalItems
            );
            ResolvedItem target = resolveItem(
                command,
                input.target(),
                currentItems,
                originalItems
            );
            if (source.itemId() == target.itemId()) {
                throw edgeInvalid("家谱边不得自环");
            }
            if (source.unit() == null || target.unit() == null
                || !source.unit().equals(target.unit())
                || !source.unit().equals(input.unit())) {
                throw edgeInvalid("家谱边两端及边数量单位必须完全一致");
            }
            String uniqueKey = source.itemId() + ":" + target.itemId() + ":" + input.groupNo();
            if (!uniqueEdges.add(uniqueKey)) {
                throw edgeInvalid("同一trace、来源、目标和组序号不能重复");
            }
            WmsMaterialTraceEdgeRow row = new WmsMaterialTraceEdgeRow();
            row.setTraceEdgeId(nextId("trace家谱边"));
            row.setTraceId(traceId);
            row.setSourceItemId(source.itemId());
            row.setTargetItemId(target.itemId());
            row.setQuantity(input.quantity());
            row.setUnit(input.unit());
            row.setGroupNo(input.groupNo());
            row.setRecordedAt(recordedAt);
            row.setCreateBy(command.actorId());
            row.setCreateDept(command.departmentId());
            row.setCreateTime(recordedAt);
            rows.add(row);
        }
        return rows;
    }

    private ResolvedItem resolveItem(
        WmsMaterialTraceAppendCommand command,
        WmsMaterialTraceItemRef reference,
        Map<String, ResolvedItem> currentItems,
        Map<Long, WmsMaterialTraceItemRow> originalItems
    ) {
        if (reference == null) {
            throw edgeInvalid("家谱边端点引用不能为空");
        }
        boolean current = reference.itemRef() != null;
        boolean original = reference.originalTraceItemId() != null;
        if (current == original) {
            throw edgeInvalid("家谱边端点必须且只能指定一种引用");
        }
        if (current) {
            ResolvedItem item = currentItems.get(reference.itemRef());
            if (item == null) {
                throw edgeInvalid("本次批次不存在itemRef: " + reference.itemRef());
            }
            return item;
        }
        if (command.traceKind() != WmsMaterialTraceKind.COMPENSATION) {
            throw edgeInvalid("FACT不得引用原trace物料项");
        }
        WmsMaterialTraceItemRow item = originalItems.get(reference.originalTraceItemId());
        if (item == null) {
            throw edgeInvalid("原trace物料项不存在: " + reference.originalTraceItemId());
        }
        if (!Objects.equals(item.getTraceId(), command.reverseTraceId())) {
            throw edgeInvalid("原trace物料项不属于reverseTraceId");
        }
        return new ResolvedItem(item.getItemId(), item.getUnit());
    }

    private void validateAcyclic(List<WmsMaterialTraceEdgeRow> edges) {
        Map<Long, Set<Long>> outgoing = new LinkedHashMap<>();
        Set<Long> nodes = new LinkedHashSet<>();
        for (WmsMaterialTraceEdgeRow edge : edges) {
            nodes.add(edge.getSourceItemId());
            nodes.add(edge.getTargetItemId());
            outgoing.computeIfAbsent(edge.getSourceItemId(), ignored -> new LinkedHashSet<>())
                .add(edge.getTargetItemId());
        }
        Map<Long, VisitState> states = new HashMap<>();
        for (Long node : nodes) {
            visit(node, outgoing, states);
        }
    }

    private void visit(Long node, Map<Long, Set<Long>> outgoing, Map<Long, VisitState> states) {
        VisitState state = states.get(node);
        if (state == VisitState.VISITING) {
            throw edgeInvalid("生产家谱边形成环");
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(node, VisitState.VISITING);
        for (Long target : outgoing.getOrDefault(node, Set.of())) {
            visit(target, outgoing, states);
        }
        states.put(node, VisitState.VISITED);
    }

    private WmsMaterialTraceHistoryRow toHistoryRow(
        WmsMaterialTraceAppendCommand command,
        long traceId,
        LocalDateTime recordedAt
    ) {
        WmsMaterialTraceHistoryRow row = new WmsMaterialTraceHistoryRow();
        row.setTraceId(traceId);
        row.setEffectKey(command.effectKey());
        row.setBusinessType(command.businessType());
        row.setBusinessName(command.businessName());
        row.setBusinessId(command.businessId());
        row.setBusinessNo(command.businessNo());
        row.setBusinessLineId(command.businessLineId());
        row.setRootBusinessType(command.rootBusinessType());
        row.setRootBusinessId(command.rootBusinessId());
        row.setTaskKey(command.taskKey());
        row.setPlanInstanceId(command.planInstanceId());
        row.setPlanCode(command.planCode());
        row.setPlanType(command.planType());
        row.setDefinitionVersion(command.definitionVersion());
        row.setEventId(command.eventId());
        row.setAttemptId(command.attemptId());
        row.setInputHash(command.inputHash());
        row.setReverseTraceId(command.reverseTraceId());
        row.setTraceKind(command.traceKind().name());
        row.setEffectResult(EFFECT_RESULT_APPLIED);
        row.setReason(command.reason());
        row.setEvidenceRef(command.evidenceRef());
        row.setActorId(command.actorId());
        row.setActorName(command.actorName());
        row.setInitiatorId(command.initiatorId());
        row.setInitiatorName(command.initiatorName());
        row.setDepartmentId(command.departmentId());
        row.setDepartmentName(command.departmentName());
        row.setExecutionSubject(command.executionSubject());
        row.setExecutionAccount(command.executionAccount());
        row.setOnBehalfOf(command.onBehalfOf());
        row.setSourceSystem(command.sourceSystem());
        row.setTerminalId(command.terminalId());
        row.setRequestId(command.requestId());
        row.setSourceTraceId(command.sourceTraceId());
        row.setOccurredAt(command.occurredAt());
        row.setRecordedAt(recordedAt);
        row.setCreateBy(command.actorId());
        row.setCreateDept(command.departmentId());
        row.setCreateTime(recordedAt);
        return row;
    }

    private WmsMaterialTraceItemRow toItemRow(
        WmsMaterialTraceAppendCommand command,
        long traceId,
        long itemId,
        WmsMaterialTraceItemInput input,
        LocalDateTime recordedAt
    ) {
        WmsMaterialTraceItemRow row = new WmsMaterialTraceItemRow();
        row.setItemId(itemId);
        row.setTraceId(traceId);
        row.setInventoryId(input.inventoryId());
        row.setStockUnitCode(input.stockUnitCode());
        row.setMaterialCode(input.materialCode());
        row.setProductionInfoId(input.productionInfoId());
        row.setProductionRevision(input.productionRevision());
        row.setProductionSnapshotJson(toJson(input.productionSnapshot()));
        row.setQuantity(input.quantity());
        row.setUnit(input.unit());
        row.setBeforeQty(input.beforeQty());
        row.setDeltaQty(input.deltaQty());
        row.setAfterQty(input.afterQty());
        row.setPhysicalFromStorageId(input.physicalFromStorageId());
        row.setPhysicalFromStorageCode(input.physicalFromStorageCode());
        row.setPhysicalToStorageId(input.physicalToStorageId());
        row.setPhysicalToStorageCode(input.physicalToStorageCode());
        row.setLogicalFromScopeVersionId(input.logicalFromScopeVersionId());
        row.setLogicalToScopeVersionId(input.logicalToScopeVersionId());
        row.setLoadParentBeforeId(input.loadParentBeforeId());
        row.setLoadParentAfterId(input.loadParentAfterId());
        row.setLoadRootBeforeId(input.loadRootBeforeId());
        row.setLoadRootAfterId(input.loadRootAfterId());
        row.setLifecycleBefore(input.lifecycleBefore());
        row.setLifecycleAfter(input.lifecycleAfter());
        row.setRulePackageVersionId(input.rulePackageVersionId());
        row.setRecordedAt(recordedAt);
        row.setCreateBy(command.actorId());
        row.setCreateDept(command.departmentId());
        row.setCreateTime(recordedAt);
        return row;
    }

    private WmsMaterialTraceSnapshot loadByEffectKey(String effectKey) {
        WmsMaterialTraceHistoryRow history = traceMapper.selectHistoryByEffectKey(effectKey);
        return history == null ? null : loadSnapshot(history.getTraceId());
    }

    private WmsMaterialTraceSnapshot loadSnapshot(long traceId) {
        WmsMaterialTraceHistoryRow history = traceMapper.selectHistoryByTraceIdForShare(traceId);
        if (history == null) {
            throw new IllegalStateException("物料trace事实头不存在: " + traceId);
        }
        List<WmsMaterialTraceItemSnapshot> items = traceMapper.selectItemsByTraceId(traceId)
            .stream()
            .map(this::toItemSnapshot)
            .toList();
        List<WmsMaterialTraceEdgeSnapshot> edges = traceMapper.selectEdgesByTraceId(traceId)
            .stream()
            .map(this::toEdgeSnapshot)
            .toList();
        return new WmsMaterialTraceSnapshot(toHistorySnapshot(history), items, edges);
    }

    private WmsMaterialTraceHistorySnapshot toHistorySnapshot(WmsMaterialTraceHistoryRow row) {
        return new WmsMaterialTraceHistorySnapshot(
            row.getTraceId(),
            row.getEffectKey(),
            row.getBusinessType(),
            row.getBusinessName(),
            row.getBusinessId(),
            row.getBusinessNo(),
            row.getBusinessLineId(),
            row.getRootBusinessType(),
            row.getRootBusinessId(),
            row.getTaskKey(),
            row.getPlanInstanceId(),
            row.getPlanCode(),
            row.getPlanType(),
            row.getDefinitionVersion(),
            row.getEventId(),
            row.getAttemptId(),
            row.getInputHash(),
            row.getReverseTraceId(),
            WmsMaterialTraceKind.valueOf(row.getTraceKind()),
            row.getEffectResult(),
            row.getReason(),
            row.getEvidenceRef(),
            row.getActorId(),
            row.getActorName(),
            row.getInitiatorId(),
            row.getInitiatorName(),
            row.getDepartmentId(),
            row.getDepartmentName(),
            row.getExecutionSubject(),
            row.getExecutionAccount(),
            row.getOnBehalfOf(),
            row.getSourceSystem(),
            row.getTerminalId(),
            row.getRequestId(),
            row.getSourceTraceId(),
            row.getOccurredAt(),
            row.getRecordedAt(),
            row.getCreateDept(),
            row.getCreateBy(),
            row.getCreateTime(),
            row.getUpdateBy(),
            row.getUpdateTime()
        );
    }

    private WmsMaterialTraceItemSnapshot toItemSnapshot(WmsMaterialTraceItemRow row) {
        return new WmsMaterialTraceItemSnapshot(
            row.getItemId(),
            row.getTraceId(),
            row.getInventoryId(),
            row.getStockUnitCode(),
            row.getMaterialCode(),
            row.getProductionInfoId(),
            row.getProductionRevision(),
            fromJson(row.getProductionSnapshotJson()),
            row.getQuantity(),
            row.getUnit(),
            row.getBeforeQty(),
            row.getDeltaQty(),
            row.getAfterQty(),
            row.getPhysicalFromStorageId(),
            row.getPhysicalFromStorageCode(),
            row.getPhysicalToStorageId(),
            row.getPhysicalToStorageCode(),
            row.getLogicalFromScopeVersionId(),
            row.getLogicalToScopeVersionId(),
            row.getLoadParentBeforeId(),
            row.getLoadParentAfterId(),
            row.getLoadRootBeforeId(),
            row.getLoadRootAfterId(),
            row.getLifecycleBefore(),
            row.getLifecycleAfter(),
            row.getRulePackageVersionId(),
            row.getRecordedAt(),
            row.getCreateDept(),
            row.getCreateBy(),
            row.getCreateTime(),
            row.getUpdateBy(),
            row.getUpdateTime()
        );
    }

    private WmsMaterialTraceEdgeSnapshot toEdgeSnapshot(WmsMaterialTraceEdgeRow row) {
        return new WmsMaterialTraceEdgeSnapshot(
            row.getTraceEdgeId(),
            row.getTraceId(),
            row.getSourceItemId(),
            row.getTargetItemId(),
            row.getQuantity(),
            row.getUnit(),
            row.getGroupNo(),
            row.getRecordedAt(),
            row.getCreateDept(),
            row.getCreateBy(),
            row.getCreateTime(),
            row.getUpdateBy(),
            row.getUpdateTime()
        );
    }

    private WmsMaterialTraceSnapshot replay(WmsMaterialTraceSnapshot snapshot, String inputHash) {
        if (!snapshot.history().inputHash().equals(inputHash)) {
            throw new ServiceException(
                ERROR_EFFECT_KEY_CONFLICT + ": 同一effectKey已绑定不同inputHash",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return snapshot;
    }

    private void validateEffectKey(String effectKey) {
        if (effectKey == null || !EFFECT_KEY_PATTERN.matcher(effectKey).matches()) {
            throw invalid(ERROR_INVALID_COMMAND, "effectKey必须为WMS:加小写64位SHA-256");
        }
    }

    private void validateQuantity(String field, BigDecimal value, boolean nonNegative) {
        if (value == null) {
            return;
        }
        if (value.scale() > DECIMAL_SCALE) {
            throw quantityInvalid(field + "原始小数位超过6位");
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int integerDigits = normalized.precision() - normalized.scale();
        if (normalized.signum() == 0) {
            integerDigits = 1;
        }
        if (integerDigits > DECIMAL_INTEGER_DIGITS) {
            throw quantityInvalid(field + "整数位超过14位");
        }
        if (nonNegative && value.signum() < 0) {
            throw quantityInvalid(field + "不得为负数");
        }
    }

    private long nextUniqueTraceId() {
        for (int attempt = 0; attempt < MAX_TRACE_ID_ATTEMPTS; attempt++) {
            long traceId = nextId("trace事实头");
            if (traceMapper.selectHistoryByTraceId(traceId) == null) {
                return traceId;
            }
        }
        throw new IllegalStateException("无法分配唯一物料trace主键");
    }

    private long nextId(String target) {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException(target + "主键生成失败");
        }
        return generated.longValue();
    }

    private String toJson(JsonNode node) {
        return node == null ? null : jsonMapper.writeValueAsString(node);
    }

    private JsonNode fromJson(String text) {
        return text == null ? null : jsonMapper.readTree(text);
    }

    private void requireText(String field, String value, int maxLength) {
        requireText(ERROR_INVALID_COMMAND, field, value, maxLength);
    }

    private void requireText(String errorCode, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(
                errorCode + ": " + field + "不能为空",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        optionalText(errorCode, field, value, maxLength);
    }

    private void optionalText(String field, String value, int maxLength) {
        optionalText(ERROR_INVALID_COMMAND, field, value, maxLength);
    }

    private void optionalText(String errorCode, String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ServiceException(
                errorCode + ": " + field + "超过最大长度" + maxLength,
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(ERROR_INVALID_COMMAND, field + "必须大于0");
        }
    }

    private void validateOptionalPositive(Long value, String field) {
        if (value != null && value <= 0) {
            throw invalid(ERROR_INVALID_COMMAND, field + "必须大于0或为null");
        }
    }

    private void assertWritten(int affectedRows, String target) {
        if (affectedRows != 1) {
            throw new IllegalStateException(target + "插入影响行数异常: " + affectedRows);
        }
    }

    private ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException quantityInvalid(String message) {
        return invalid(ERROR_QUANTITY_INVALID, message);
    }

    private ServiceException edgeInvalid(String message) {
        return invalid(ERROR_EDGE_INVALID, message);
    }

    private record PreparedTrace(
        WmsMaterialTraceHistoryRow history,
        List<WmsMaterialTraceItemRow> items,
        List<WmsMaterialTraceEdgeRow> edges
    ) {
    }

    private record ResolvedItem(long itemId, String unit) {
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
