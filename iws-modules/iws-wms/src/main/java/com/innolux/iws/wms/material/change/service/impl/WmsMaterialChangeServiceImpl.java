/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 在调用者事务内锁后当前读并提交位置、流水与 trace；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectCommand;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectExpectation;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectIdentity;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectResult;
import com.innolux.iws.wms.allocation.effect.service.WmsAllocationEffectService;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.material.change.domain.WmsMaterialAllocationConsumption;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeCommand;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeResult;
import com.innolux.iws.wms.material.change.mapper.WmsMaterialChangeMapper;
import com.innolux.iws.wms.material.change.mapper.WmsMaterialFlowInsertRow;
import com.innolux.iws.wms.material.change.mapper.WmsMaterialFlowRow;
import com.innolux.iws.wms.material.change.mapper.WmsMaterialInventoryRow;
import com.innolux.iws.wms.material.change.mapper.WmsMaterialStorageRow;
import com.innolux.iws.wms.material.change.service.WmsMaterialChangeService;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceAppendCommand;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemInput;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceKind;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 内部物料位置事实服务。
 *
 * <p>先锁定库存行，再从权威当前行重新读取版本、数量和预留状态。已有同效果键流水时只回读
 * 已提交 trace，不重复修改库存。所有写入都要求调用者事务，自身不开启新事务。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsMaterialChangeServiceImpl implements WmsMaterialChangeService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Pattern INPUT_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final String FLOW_TYPE_MOVE = "MOVE";

    private static final int TEXT_LIMIT = 500;

    private final WmsMaterialChangeMapper changeMapper;

    private final WmsMaterialTraceService traceService;

    private final WmsLoadChangeService loadChangeService;

    private final WmsAllocationEffectService allocationEffectService;

    private final IdentifierGenerator identifierGenerator;

    /**
     * 在调用者事务内提交库存位置、库存流水和物料 trace。
     *
     * @param command 物料位置命令
     * @return 已提交事实
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsMaterialChangeResult apply(WmsMaterialChangeCommand command) {
        return apply(command, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsMaterialChangeResult apply(
        WmsMaterialChangeCommand command,
        WmsLoadChangeCommand loadChange,
        WmsMaterialAllocationConsumption allocationConsumption
    ) {
        validateCommand(command);
        validateComposite(command, loadChange, allocationConsumption);
        WmsMaterialInventoryRow inventory = changeMapper.selectInventoryForUpdate(command.inventoryId());
        if (inventory == null) {
            throw error(ERROR_INVENTORY_NOT_FOUND, "库存单元不存在: " + command.inventoryId(),
                HttpStatus.HTTP_NOT_FOUND);
        }

        WmsMaterialFlowRow existing = changeMapper.selectFlowByIdempotencyKeyForUpdate(command.effectKey());
        if (existing != null) {
            return replay(command, inventory, existing, loadChange, allocationConsumption);
        }
        if (traceService.findByEffectKey(command.effectKey()).isPresent()) {
            throw error(ERROR_TRACE_MISSING, "同效果键trace存在但库存流水缺失", HttpStatus.HTTP_CONFLICT);
        }
        validateInventory(command, inventory, allocationConsumption != null);

        WmsMaterialStorageRow target = changeMapper.selectStorage(command.targetStorageId());
        validateTargetStorage(target);
        if (Objects.equals(inventory.getStorageId(), target.getStorageId())) {
            throw invalid("目标储位与当前储位相同，未形成物料位置变化");
        }

        int rows = changeMapper.updateInventoryLocation(
            inventory.getInventoryId(),
            inventory.getVersion(),
            target.getStorageId(),
            target.getStorageCode(),
            target.getZoneId(),
            target.getZoneCode(),
            command.actorId(),
            command.occurredAt()
        );
        if (rows != 1) {
            throw error(ERROR_INVENTORY_VERSION_CONFLICT, "锁定后库存版本更新失败",
                HttpStatus.HTTP_CONFLICT);
        }

        WmsMaterialFlowInsertRow flow = toFlow(command, inventory, target);
        if (changeMapper.insertFlow(flow) != 1) {
            throw new IllegalStateException("库存位置流水插入影响行数异常");
        }

        WmsMaterialTraceSnapshot trace = traceService.append(toTraceCommand(command, inventory, target));
        WmsAllocationEffectResult allocationEffect = consumeAllocation(
            command, allocationConsumption, trace
        );
        WmsLoadChangeResult loadChangeResult = applyLoadChange(loadChange, trace);
        return new WmsMaterialChangeResult(
            inventory.getInventoryId(),
            flow.getFlowId(),
            inventory.getStorageId(),
            inventory.getStorageCode(),
            target.getStorageId(),
            target.getStorageCode(),
            inventory.getInventoryQty(),
            trace,
            loadChangeResult,
            allocationEffect
        );
    }

    private WmsMaterialChangeResult replay(
        WmsMaterialChangeCommand command,
        WmsMaterialInventoryRow inventory,
        WmsMaterialFlowRow flow,
        WmsLoadChangeCommand loadChange,
        WmsMaterialAllocationConsumption allocationConsumption
    ) {
        WmsMaterialTraceSnapshot trace = traceService.findByEffectKey(command.effectKey())
            .orElseThrow(() -> error(
                ERROR_TRACE_MISSING,
                "库存流水存在但物料trace缺失",
                HttpStatus.HTTP_CONFLICT
            ));
        if (trace.items().size() != 1
            || !trace.history().inputHash().equals(command.inputHash())
            || !Objects.equals(flow.getInventoryId(), command.inventoryId())
            || !Objects.equals(
                trace.items().getFirst().physicalToStorageId(),
                command.targetStorageId()
            )
            || flow.getToStorageCode() == null
            || !flow.getToStorageCode().equals(trace.items().getFirst().physicalToStorageCode())) {
            throw error(
                ERROR_EFFECT_KEY_CONFLICT,
                "同一effectKey已绑定不同物料位置事实",
                HttpStatus.HTTP_CONFLICT
            );
        }
        WmsAllocationEffectResult allocationEffect = consumeAllocation(
            command, allocationConsumption, trace
        );
        WmsLoadChangeResult loadChangeResult = applyLoadChange(loadChange, trace);
        return new WmsMaterialChangeResult(
            inventory.getInventoryId(),
            flow.getFlowId(),
            trace.items().getFirst().physicalFromStorageId(),
            flow.getFromStorageCode(),
            trace.items().getFirst().physicalToStorageId(),
            flow.getToStorageCode(),
            trace.items().getFirst().quantity(),
            trace,
            loadChangeResult,
            allocationEffect
        );
    }

    private void validateCommand(WmsMaterialChangeCommand command) {
        if (command == null) {
            throw invalid("物料位置命令不能为空");
        }
        if (command.effectKey() == null || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()) {
            throw invalid("effectKey必须为WMS:加小写64位SHA-256");
        }
        if (command.inputHash() == null || !INPUT_HASH_PATTERN.matcher(command.inputHash()).matches()) {
            throw invalid("inputHash必须是小写64位SHA-256");
        }
        requirePositive(command.inventoryId(), "inventoryId");
        requirePositive(command.targetStorageId(), "targetStorageId");
        requirePositive(command.businessId(), "businessId");
        requirePositive(command.rootBusinessId(), "rootBusinessId");
        requireText(command.businessType(), "businessType", 64);
        requireText(command.businessName(), "businessName", 128);
        requireText(command.rootBusinessType(), "rootBusinessType", 64);
        optionalText(command.businessNo(), "businessNo", 100);
        optionalText(command.taskKey(), "taskKey", 96);
        optionalText(command.planCode(), "planCode", 64);
        optionalText(command.planType(), "planType", 64);
        optionalText(command.definitionVersion(), "definitionVersion", 64);
        optionalText(command.evidenceRef(), "evidenceRef", TEXT_LIMIT);
        optionalText(command.reason(), "reason", TEXT_LIMIT);
        optionalText(command.sourceSystem(), "sourceSystem", 64);
        optionalText(command.requestId(), "requestId", 64);
        if (command.occurredAt() == null) {
            throw invalid("occurredAt不能为空");
        }
        if (command.expectedVersion() < 0) {
            throw invalid("expectedVersion不得小于0");
        }
        validateOptionalPositive(command.businessLineId(), "businessLineId");
        validateOptionalPositive(command.planInstanceId(), "planInstanceId");
        validateOptionalPositive(command.eventId(), "eventId");
        validateOptionalPositive(command.attemptId(), "attemptId");
        validateOptionalPositive(command.actorId(), "actorId");
        validateOptionalPositive(command.departmentId(), "departmentId");
    }

    private void validateInventory(
        WmsMaterialChangeCommand command,
        WmsMaterialInventoryRow inventory,
        boolean allowReserved
    ) {
        if (inventory.getVersion() == null || inventory.getVersion() != command.expectedVersion()) {
            throw error(
                ERROR_INVENTORY_VERSION_CONFLICT,
                "库存版本不一致，期望 " + command.expectedVersion() + "，实际 " + inventory.getVersion(),
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (!allowReserved
            && ((inventory.getReservedQty() != null && inventory.getReservedQty().signum() != 0)
            || inventory.getReservedOrderNo() != null)) {
            throw error(
                ERROR_ALLOCATION_UNSUPPORTED,
                "当前物料存在预留或占用，最小位置事实不能消费未接线锁",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (allowReserved && inventory.getReservedQty() != null
            && inventory.getReservedQty().signum() < 0) {
            throw invalid("库存预留数量不能为负数");
        }
        if (inventory.getInventoryQty() == null || inventory.getInventoryQty().signum() <= 0) {
            throw invalid("库存数量必须大于0");
        }
        requireText(inventory.getStockUnitCode(), "stockUnitCode", 100);
        if (inventory.getUnit() != null && inventory.getUnit().length() > 32) {
            throw invalid("库存单位超过最大长度32");
        }
    }

    private void validateTargetStorage(WmsMaterialStorageRow target) {
        if (target == null || !"0".equals(target.getDelFlag())) {
            throw error(
                ERROR_TARGET_STORAGE_INVALID,
                "目标储位不存在或已删除",
                HttpStatus.HTTP_NOT_FOUND
            );
        }
        if (target.getDisableStatus() == null || target.getDisableStatus() != 0) {
            throw error(ERROR_TARGET_STORAGE_INVALID, "目标储位已禁用", HttpStatus.HTTP_CONFLICT);
        }
        if (target.getLockStatus() == null || target.getLockStatus() != 0) {
            throw error(
                ERROR_ALLOCATION_UNSUPPORTED,
                "目标储位处于锁定状态，最小位置事实不能消费未接线锁",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (target.getStatus() == null || target.getStatus() == 3) {
            throw error(ERROR_TARGET_STORAGE_INVALID, "目标储位维护中或状态未知", HttpStatus.HTTP_CONFLICT);
        }
        if (target.getStatus() == 2) {
            throw error(
                ERROR_ALLOCATION_UNSUPPORTED,
                "目标储位处于预占状态，最小位置事实不能消费未接线占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (target.getZoneId() == null) {
            throw error(ERROR_TARGET_STORAGE_INVALID, "目标储位缺少库区", HttpStatus.HTTP_CONFLICT);
        }
        requireText(target.getZoneCode(), "zoneCode", 50);
        requireText(target.getStorageCode(), "storageCode", 64);
    }

    private void validateComposite(
        WmsMaterialChangeCommand command,
        WmsLoadChangeCommand loadChange,
        WmsMaterialAllocationConsumption allocationConsumption
    ) {
        if (loadChange == null && allocationConsumption == null) {
            return;
        }
        if (loadChange != null) {
            if (!Objects.equals(command.effectKey(), loadChange.effectKey())
                || !Objects.equals(command.inputHash(), loadChange.inputHash())
                || !Objects.equals(command.businessType(), loadChange.businessType())
                || command.businessId() != loadChange.businessId()
                || !Objects.equals(command.businessLineId(), loadChange.businessLineId())
                || !Objects.equals(command.taskKey(), loadChange.taskKey())
                || !Objects.equals(command.planInstanceId(), loadChange.planInstanceId())) {
                throw invalid("物料位置与装载变更必须绑定同一业务、任务、实例和效果键");
            }
            if (loadChange.traceId() != null) {
                throw invalid("装载变更traceId由物料事实服务生成，不能由调用方提供");
            }
            if (loadChange.mutations() == null || loadChange.mutations().size() != 1) {
                throw invalid("组合物料位置事实只支持一个装载根位置变更");
            }
            var mutation = loadChange.mutations().getFirst();
            if (mutation == null
                || mutation.targetParentNodeId() != null
                || !"STORAGE".equals(mutation.targetLocationKind())
                || !Objects.equals(mutation.targetStorageId(), command.targetStorageId())) {
                throw invalid("组合物料位置事实必须把同一装载根移动到目标储位");
            }
            Long loadNodeId = changeMapper.selectLoadNodeIdByInventory(command.inventoryId());
            if (loadNodeId == null || loadNodeId != mutation.childNodeId()) {
                throw invalid("组合装载变更的根节点未绑定当前库存单元");
            }
        }
        if (allocationConsumption != null) {
            if (loadChange == null) {
                throw invalid("消费分配锁时必须在同一事务提交装载占用");
            }
            WmsAllocationEffectIdentity identity = allocationConsumption.identity();
            if (identity == null
                || !Objects.equals(command.businessType(), identity.businessType())
                || command.businessId() != identity.businessId()
                || !Objects.equals(command.businessLineId(), identity.businessLineId())
                || !Objects.equals(command.taskKey(), identity.taskKey())
                || !Objects.equals(command.planInstanceId(), identity.planInstanceId())) {
                throw invalid("分配锁消费身份必须与物料事实父业务一致");
            }
            List<WmsAllocationEffectExpectation> expectations =
                allocationConsumption.expectations();
            if (expectations == null || expectations.isEmpty()) {
                throw invalid("分配锁消费必须提供逐锁代次期望");
            }
            long distinct = expectations.stream()
                .map(WmsAllocationEffectExpectation::allocationLockId)
                .distinct()
                .count();
            if (distinct != expectations.size()
                || expectations.stream().anyMatch(item -> item == null
                    || item.allocationLockId() <= 0 || item.expectedGeneration() < 0)) {
                throw invalid("分配锁消费代次期望为空、重复或非法");
            }
        }
    }

    private WmsAllocationEffectResult consumeAllocation(
        WmsMaterialChangeCommand command,
        WmsMaterialAllocationConsumption consumption,
        WmsMaterialTraceSnapshot trace
    ) {
        if (consumption == null) {
            return null;
        }
        long traceId = trace.history().traceId();
        WmsAllocationEffectResult result = allocationEffectService.advance(
            new WmsAllocationEffectCommand(
                consumption.identity(),
                "CONSUMED",
                consumption.expectations(),
                command.effectKey(),
                traceId,
                consumption.evidenceRef(),
                command.occurredAt(),
                command.actorId(),
                command.departmentId()
            )
        );
        List<Long> storageIds = changeMapper.selectAllocationLockStorageIds(
            consumption.expectations().stream()
                .map(WmsAllocationEffectExpectation::allocationLockId)
                .sorted()
                .toList()
        );
        if (storageIds.size() != consumption.expectations().size()
            || storageIds.stream().anyMatch(storageId ->
                !Objects.equals(storageId, command.targetStorageId()))) {
            throw error(
                ERROR_ALLOCATION_UNSUPPORTED,
                "分配锁储位与物料目标储位不一致",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return result;
    }

    private WmsLoadChangeResult applyLoadChange(
        WmsLoadChangeCommand loadChange,
        WmsMaterialTraceSnapshot trace
    ) {
        if (loadChange == null) {
            return null;
        }
        return loadChangeService.apply(new WmsLoadChangeCommand(
            loadChange.effectKey(),
            loadChange.inputHash(),
            loadChange.mutations(),
            loadChange.occurredAt(),
            loadChange.reason(),
            loadChange.evidenceRef(),
            loadChange.sourceSystem(),
            loadChange.requestId(),
            loadChange.businessType(),
            loadChange.businessName(),
            loadChange.businessId(),
            loadChange.businessNo(),
            loadChange.businessLineId(),
            loadChange.taskKey(),
            loadChange.planInstanceId(),
            loadChange.definitionVersion(),
            loadChange.eventId(),
            loadChange.attemptId(),
            loadChange.actorId(),
            loadChange.departmentId(),
            trace.history().traceId()
        ));
    }

    private WmsMaterialFlowInsertRow toFlow(
        WmsMaterialChangeCommand command,
        WmsMaterialInventoryRow inventory,
        WmsMaterialStorageRow target
    ) {
        long flowId = nextId();
        WmsMaterialFlowInsertRow row = new WmsMaterialFlowInsertRow();
        row.setFlowId(flowId);
        row.setFlowNo("MC" + flowId);
        row.setFlowType(FLOW_TYPE_MOVE);
        row.setInventoryId(inventory.getInventoryId());
        row.setStockUnitCode(inventory.getStockUnitCode());
        row.setMaterialCode(inventory.getMaterialCode());
        row.setFromStorageCode(inventory.getStorageCode());
        row.setToStorageCode(target.getStorageCode());
        row.setBeforeQty(inventory.getInventoryQty());
        row.setChangeQty(BigDecimal.ZERO.setScale(6));
        row.setAfterQty(inventory.getInventoryQty());
        row.setSourceOrderType(FLOW_TYPE_MOVE);
        row.setSourceOrderNo(command.businessNo());
        row.setSourceOrderLineId(command.businessLineId());
        row.setIdempotencyKey(command.effectKey());
        row.setOperatorId(command.actorId());
        row.setOccurredAt(command.occurredAt());
        row.setCreateTime(command.occurredAt());
        row.setRemark(command.reason());
        return row;
    }

    private WmsMaterialTraceAppendCommand toTraceCommand(
        WmsMaterialChangeCommand command,
        WmsMaterialInventoryRow inventory,
        WmsMaterialStorageRow target
    ) {
        WmsMaterialTraceItemInput item = new WmsMaterialTraceItemInput(
            "ITEM",
            inventory.getInventoryId(),
            inventory.getStockUnitCode(),
            inventory.getMaterialCode(),
            null,
            null,
            null,
            inventory.getInventoryQty(),
            inventory.getUnit(),
            inventory.getInventoryQty(),
            BigDecimal.ZERO.setScale(6),
            inventory.getInventoryQty(),
            inventory.getStorageId(),
            inventory.getStorageCode(),
            target.getStorageId(),
            target.getStorageCode(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        return new WmsMaterialTraceAppendCommand(
            command.effectKey(),
            command.businessType(),
            command.businessName(),
            command.businessId(),
            command.businessNo(),
            command.businessLineId(),
            command.rootBusinessType(),
            command.rootBusinessId(),
            command.taskKey(),
            command.planInstanceId(),
            command.planCode(),
            command.planType(),
            command.definitionVersion(),
            command.eventId(),
            command.attemptId(),
            command.inputHash(),
            null,
            WmsMaterialTraceKind.FACT,
            command.reason(),
            command.evidenceRef(),
            command.actorId(),
            null,
            null,
            null,
            command.departmentId(),
            null,
            null,
            null,
            null,
            command.sourceSystem(),
            null,
            command.requestId(),
            null,
            command.occurredAt(),
            List.of(item),
            List.of()
        );
    }

    private long nextId() {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("库存位置流水主键生成失败");
        }
        return generated.longValue();
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + "必须大于0");
        }
    }

    private void validateOptionalPositive(Long value, String field) {
        if (value != null && value <= 0) {
            throw invalid(field + "必须大于0或为null");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + "不能为空");
        }
        if (value.length() > maxLength) {
            throw invalid(field + "超过最大长度" + maxLength);
        }
    }

    private void optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid(field + "超过最大长度" + maxLength);
        }
    }

    private ServiceException invalid(String message) {
        return error(ERROR_INVALID_COMMAND, message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException error(String code, String message, int status) {
        return new ServiceException(code + ": " + message, status);
    }
}
