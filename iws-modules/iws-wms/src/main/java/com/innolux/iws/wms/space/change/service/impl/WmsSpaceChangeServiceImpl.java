/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 持久变更单、父链锁、配置版本、生效指针与CAS原子提交；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeResult;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeValidation;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.space.domain.WmsSpaceHierarchy;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 空间生命周期持久化实现。 */
@Service
public class WmsSpaceChangeServiceImpl implements WmsSpaceChangeService {

    private static final Set<String> CHANGE_TYPES = Set.of("CREATE", "UPDATE", "RETIRE");

    private static final Set<String> OBJECT_TYPES = Set.of("WAREHOUSE", "ZONE", "STORAGE");

    private static final Set<String> FINAL_APPLIED_STATUSES =
        Set.of("APPLIED", "COMPLETED");

    private static final String PARENT_LOCATION_VERSION_KEY = "parentLocationVersionId";

    private static final String MATERIAL_PARENT_LOCATION_VERSION_KEY =
        "materialParentLocationVersionId";

    private static final String WAREHOUSE_LOCATION_VERSION_KEY =
        "warehouseLocationVersionId";

    private final JdbcTemplate jdbcTemplate;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    private final WmsCompanyAccessPolicy accessPolicy;

    public WmsSpaceChangeServiceImpl(
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator,
        JsonMapper jsonMapper,
        WmsCompanyAccessPolicy accessPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.identifierGenerator = identifierGenerator;
        this.jsonMapper = jsonMapper;
        this.accessPolicy = accessPolicy;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long createOrder(
        WmsSpaceChangeCommand command,
        String inputHash,
        String inputJson
    ) {
        validateCommand(command);
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE);
        OrderRow existing = findOrderByCommandKey(command.getCommandKey());
        if (existing != null) {
            if (!Objects.equals(existing.inputHash(), inputHash)
                || !sameJson(existing.inputJson(), inputJson)) {
                throw conflict("同一命令键已绑定不同空间变更输入");
            }
            return existing.orderId();
        }
        long orderId = nextId();
        try {
            jdbcTemplate.update("""
                insert into wms_space_change_order
                    (change_order_id, command_key, change_type, object_type, object_id,
                     expected_revision, company_id, warehouse_id, zone_id, input_hash,
                     input_json, status, revision, reason, create_dept, create_by,
                     update_by, create_time, update_time)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', 1, ?, ?, ?, ?,
                        current_timestamp(3), current_timestamp(3))
                """,
                orderId,
                command.getCommandKey(),
                normalize(command.getChangeType()),
                normalize(command.getObjectType()),
                command.getObjectId(),
                command.getExpectedRevision(),
                command.getCompanyId(),
                command.getWarehouseId(),
                command.getZoneId(),
                inputHash,
                inputJson,
                limit(command.getReason(), 500),
                LoginHelper.getDeptId(),
                LoginHelper.getUserId(),
                LoginHelper.getUserId()
            );
        } catch (DuplicateKeyException error) {
            OrderRow raced = findOrderByCommandKeyCurrent(command.getCommandKey());
            if (raced == null || !Objects.equals(raced.inputHash(), inputHash)) {
                throw conflict("同一命令键已并发绑定不同空间变更输入");
            }
            return raced.orderId();
        }
        return orderId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsSpaceChangeValidation validateWithinTransaction(long changeOrderId) {
        OrderRow order = lockOrder(changeOrderId);
        if (!"CREATED".equals(order.status())
            && !"VALIDATED".equals(order.status())) {
            return validation(order);
        }
        Long actorId = initiatorId(order);
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE, actorId);
        WmsSpaceChangeCommand command = readCommand(order.inputJson());
        Resolved resolved = freezeParentVersion(resolve(order, command, false, actorId));
        String configJson = WmsRuleDigest.canonical(
            jsonMapper.readTree(jsonMapper.writeValueAsString(resolved.config())));
        String configDigest = WmsRuleDigest.sha256(configJson);
        long objectId = resolved.objectId();
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set object_id = ?, company_id = ?, warehouse_id = ?, zone_id = ?,
                   validated_config_json = ?, canonical_digest = ?,
                   status = 'VALIDATED', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where change_order_id = ? and status = ?
            """,
            objectId,
            resolved.companyId(),
            resolved.warehouseId(),
            resolved.zoneId(),
            configJson,
            configDigest,
            LoginHelper.getUserId(),
            changeOrderId,
            order.status()
        );
        if (rows != 1) {
            throw conflict("空间变更校验发生并发竞争");
        }
        return validation(lockOrder(changeOrderId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsSpaceChangeResult applyWithinTransaction(long changeOrderId) {
        OrderRow order = lockOrder(changeOrderId);
        if (FINAL_APPLIED_STATUSES.contains(order.status())) {
            return result(order);
        }
        if (!"VALIDATED".equals(order.status())) {
            throw conflict("空间变更必须先完成 VALIDATED 校验");
        }
        Long actorId = initiatorId(order);
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE, actorId);
        Resolved resolved = resolvedFromOrder(order);
        accessPolicy.lockAndCheckCompanyForShare(resolved.companyId(), actorId);
        lockParentChain(order.objectType(), resolved.objectId(), resolved);
        ObjectRevision current = lockTarget(order.objectType(), resolved.objectId());
        validateLockedTarget(order, resolved, current);
        if (current != null && order.expectedRevision() != null
            && current.revision() != order.expectedRevision()) {
            throw versionConflict("空间对象修订号已变化");
        }
        validateLockedParents(resolved);

        int nextRevision = writeObject(order, resolved, current);
        long previousVersionId = currentVersionId(order.objectType(), resolved.objectId());
        if (previousVersionId > 0) {
            jdbcTemplate.update("""
                update wms_location_version
                   set status = 'RETIRED', update_by = ?, update_time = current_timestamp(3)
                 where location_version_id = ? and status = 'ACTIVE'
                """, LoginHelper.getUserId(), previousVersionId);
        }
        int nextVersionNo = nextLocationVersionNo(order.objectType(), resolved.objectId());
        Long parentVersionId = parentVersionId(resolved);
        long locationVersionId = nextId();
        jdbcTemplate.update("""
            insert into wms_location_version
                (location_version_id, object_type, object_id, version_no,
                 parent_location_version_id, config_json, canonical_digest, status,
                 create_dept, create_by, update_by)
            values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """,
            locationVersionId,
            order.objectType(),
            resolved.objectId(),
            nextVersionNo,
            parentVersionId,
            order.validatedConfigJson(),
            order.canonicalDigest(),
            LoginHelper.getDeptId(),
            LoginHelper.getUserId(),
            LoginHelper.getUserId()
        );
        int pointerRevision = switchEffective(
            order.objectType(), resolved.objectId(), locationVersionId);
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set status = 'APPLIED', location_version_id = ?,
                   location_effective_revision = ?, object_revision = ?,
                   revision = revision + 1, update_by = ?,
                   update_time = current_timestamp(3)
             where change_order_id = ? and status = 'VALIDATED'
            """,
            locationVersionId,
            pointerRevision,
            nextRevision,
            LoginHelper.getUserId(),
            changeOrderId
        );
        if (rows != 1) {
            throw conflict("空间变更应用发生并发竞争");
        }
        return result(lockOrder(changeOrderId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsSpaceChangeResult verifyWithinTransaction(long changeOrderId) {
        OrderRow order = lockOrder(changeOrderId);
        if ("COMPLETED".equals(order.status())) {
            return result(order);
        }
        if (!"APPLIED".equals(order.status())) {
            throw conflict("空间变更必须先完成 APPLIED");
        }
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE, initiatorId(order));
        Long pointerVersion = jdbcTemplate.query("""
            select location_version_id, revision
              from wms_location_effective
             where object_type = ? and object_id = ?
            """, (rs, rowNum) -> rs.getLong("location_version_id"),
            order.objectType(), order.objectId()).stream().findFirst().orElse(null);
        if (!Objects.equals(pointerVersion, order.locationVersionId())) {
            throw conflict("空间生效指针未指向本次版本");
        }
        ObjectRevision current = requireTarget(order.objectType(), order.objectId());
        if (order.objectRevision() == null || current.revision() != order.objectRevision()) {
            throw conflict("空间对象修订回读不一致");
        }
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set status = 'COMPLETED', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where change_order_id = ? and status = 'APPLIED'
            """, LoginHelper.getUserId(), changeOrderId);
        if (rows != 1) {
            throw conflict("空间变更核验发生并发竞争");
        }
        return result(lockOrder(changeOrderId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsSpaceChangeValidation checkRetirementWithinTransaction(long changeOrderId) {
        OrderRow order = lockOrder(changeOrderId);
        if ("CHECKED".equals(order.status()) || "RETIRED".equals(order.status())
            || "COMPLETED".equals(order.status())) {
            return validation(order);
        }
        if (!"CREATED".equals(order.status())
            || !"RETIRE".equals(order.changeType())) {
            throw conflict("退役检查只接受 CREATED 的 RETIRE 变更单");
        }
        Long actorId = initiatorId(order);
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE, actorId);
        Resolved resolved = freezeParentVersion(
            resolve(order, readCommand(order.inputJson()), true, actorId));
        lockParentChain(order.objectType(), resolved.objectId(), resolved);
        validateLockedParents(resolved);
        assertNoRetirementReferences(order.objectType(), resolved.objectId());
        String checkedConfigJson = WmsRuleDigest.canonical(
            jsonMapper.readTree(jsonMapper.writeValueAsString(resolved.config())));
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set object_id = ?, company_id = ?, warehouse_id = ?, zone_id = ?,
                   validated_config_json = ?, canonical_digest = ?,
                   status = 'CHECKED', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where change_order_id = ? and status = 'CREATED'
            """,
            resolved.objectId(),
            resolved.companyId(),
            resolved.warehouseId(),
            resolved.zoneId(),
            checkedConfigJson,
            WmsRuleDigest.sha256(checkedConfigJson),
            LoginHelper.getUserId(),
            changeOrderId
        );
        if (rows != 1) {
            throw conflict("空间退役检查发生并发竞争");
        }
        return validation(lockOrder(changeOrderId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsSpaceChangeResult retireWithinTransaction(long changeOrderId) {
        OrderRow order = lockOrder(changeOrderId);
        if ("RETIRED".equals(order.status()) || "COMPLETED".equals(order.status())) {
            return result(order);
        }
        if (!"CHECKED".equals(order.status())) {
            throw conflict("空间退役必须先完成 CHECKED");
        }
        Long actorId = initiatorId(order);
        accessPolicy.checkPermissionForShare(PERMISSION_CHANGE, actorId);
        Resolved resolved = resolvedFromOrder(order);
        accessPolicy.lockAndCheckCompanyForShare(resolved.companyId(), actorId);
        lockParentChain(order.objectType(), resolved.objectId(), resolved);
        ObjectRevision current = requireTarget(order.objectType(), resolved.objectId());
        if (!"ACTIVE".equals(current.lifecycleStatus())) {
            throw conflict("空间对象已不处于 ACTIVE 生命周期");
        }
        if (order.expectedRevision() != null
            && current.revision() != order.expectedRevision()) {
            throw versionConflict("空间对象修订号已变化");
        }
        validateLockedParents(resolved);
        assertNoRetirementReferences(order.objectType(), resolved.objectId());
        int nextRevision = retireObject(order.objectType(), resolved.objectId(), current.revision());
        long locationVersionId = nextId();
        long previousVersionId = currentVersionId(order.objectType(), resolved.objectId());
        if (previousVersionId > 0) {
            jdbcTemplate.update("""
                update wms_location_version
                   set status = 'RETIRED', update_by = ?, update_time = current_timestamp(3)
                 where location_version_id = ? and status = 'ACTIVE'
                """, LoginHelper.getUserId(), previousVersionId);
        }
        jdbcTemplate.update("""
            insert into wms_location_version
                (location_version_id, object_type, object_id, version_no,
                 parent_location_version_id, config_json, canonical_digest, status,
                 create_dept, create_by, update_by)
            values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """,
            locationVersionId,
            order.objectType(),
            resolved.objectId(),
            nextLocationVersionNo(order.objectType(), resolved.objectId()),
            parentVersionId(resolved),
            order.validatedConfigJson(),
            order.canonicalDigest(),
            LoginHelper.getDeptId(),
            LoginHelper.getUserId(),
            LoginHelper.getUserId()
        );
        int pointerRevision = switchEffective(
            order.objectType(), resolved.objectId(), locationVersionId);
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set status = 'RETIRED', location_version_id = ?,
                   location_effective_revision = ?, object_revision = ?,
                   revision = revision + 1, update_by = ?,
                   update_time = current_timestamp(3)
             where change_order_id = ? and status = 'CHECKED'
            """,
            locationVersionId,
            pointerRevision,
            nextRevision,
            LoginHelper.getUserId(),
            changeOrderId
        );
        if (rows != 1) {
            throw conflict("空间退役提交发生并发竞争");
        }
        return result(lockOrder(changeOrderId));
    }

    private Resolved resolve(
        OrderRow order,
        WmsSpaceChangeCommand command,
        boolean retire,
        Long actorId
    ) {
        String changeType = normalize(order.changeType());
        String objectType = normalize(order.objectType());
        long objectId = order.objectId() == null
            ? (command.getObjectId() == null ? nextId() : command.getObjectId())
            : order.objectId();
        Map<String, Object> config = new LinkedHashMap<>();
        if ("UPDATE".equals(changeType) || retire) {
            if (command.getObjectId() == null) {
                throw invalid("更新或退役必须提供 objectId");
            }
            objectId = command.getObjectId();
            config.putAll(loadConfig(objectType, objectId));
            if ("UPDATE".equals(changeType) || retire) {
                requireActiveLifecycle(config, objectType);
            }
            assertUpdateStructureUnchanged(objectType, config, command);
            long companyId = longValue(config.get("companyId"), "companyId");
            accessPolicy.lockAndCheckCompanyForShare(companyId, actorId);
        }
        if (!retire) {
            mergeCommand(config, command, objectType, changeType);
        }
        Resolved resolved = switch (objectType) {
            case "WAREHOUSE" -> resolveWarehouse(objectId, config, changeType, retire);
            case "ZONE" -> resolveZone(objectId, config, changeType, retire);
            case "STORAGE" -> resolveStorage(objectId, config, changeType, retire);
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
        // 更新可以把库区/储位迁到其他仓库，目标公司也必须重新授权。
        accessPolicy.lockAndCheckCompany(resolved.companyId(), actorId);
        return resolved;
    }

    private Resolved resolveWarehouse(
        long objectId,
        Map<String, Object> config,
        String changeType,
        boolean retire
    ) {
        long companyId = requirePositive(config.get("companyId"), "companyId");
        String code = requireText(config.get("code"), "code", 64);
        String name = requireText(config.get("name"), "name", 100);
        if (!retire) {
            assertUniqueWarehouse(companyId, code, "CREATE".equals(changeType) ? null : objectId);
        }
        config.put("companyId", companyId);
        config.put("code", code);
        config.put("name", name);
        config.put("warehouseTypeCode", textOrDefault(config.get("warehouseTypeCode"), "GENERAL"));
        config.put("typeConfigVersionId", nullableLong(config.get("typeConfigVersionId")));
        config.put("enabled", boolOrDefault(config.get("enabled"), false));
        config.put("remark", blankToNull(config.get("remark")));
        config.put("lifecycleStatus", retire ? "RETIRED" : "ACTIVE");
        return new Resolved(
            "WAREHOUSE", objectId, companyId, null, null, config,
            "CREATE".equals(changeType) ? null : readRevision("WAREHOUSE", objectId)
        );
    }

    private Resolved resolveZone(
        long objectId,
        Map<String, Object> config,
        String changeType,
        boolean retire
    ) {
        long warehouseId = requirePositive(config.get("warehouseId"), "warehouseId");
        WarehouseRow warehouse = requireWarehouse(warehouseId);
        if (!"ACTIVE".equals(warehouse.lifecycleStatus())) {
            throw invalid("库区父仓库必须处于 ACTIVE 生命周期");
        }
        String code = requireText(config.get("code"), "code", 50);
        String name = requireText(config.get("name"), "name", 100);
        String zoneKind = normalize(textOrDefault(config.get("zoneKind"), "MATERIAL"));
        if (!WmsSpaceHierarchy.supportsZoneKind(zoneKind)) {
            throw invalid("zoneKind 必须为 MATERIAL/DEVICE");
        }
        Long parentZoneId = nullableLong(config.get("parentZoneId"));
        if (parentZoneId != null && parentZoneId == objectId) {
            throw invalid("库区不能以自身为父库区");
        }
        ZoneRow parent = parentZoneId == null ? null : requireZone(parentZoneId);
        if (parent != null && !"ACTIVE".equals(parent.lifecycleStatus())) {
            throw invalid("父库区必须处于 ACTIVE 生命周期");
        }
        if (!WmsSpaceHierarchy.validZoneParent(
            zoneKind, parentZoneId, parent == null ? null : parent.zoneKind(),
            parent == null ? null : parent.warehouseId(), warehouseId)) {
            throw invalid("库区父链必须为仓库→MATERIAL→DEVICE，禁止跳层或同类嵌套");
        }
        if (!retire) {
            assertUniqueZone(code, "CREATE".equals(changeType) ? null : objectId);
        }
        config.put("warehouseId", warehouseId);
        config.put("companyId", warehouse.companyId());
        config.put("code", code);
        config.put("name", name);
        config.put("zoneKind", zoneKind);
        config.put("zoneType", intOrDefault(config.get("zoneType"), 1));
        config.put("parentZoneId", parentZoneId);
        config.put("allowInbound", boolOrDefault(config.get("allowInbound"), true) ? 1 : 0);
        config.put("allowOutbound", boolOrDefault(config.get("allowOutbound"), true) ? 1 : 0);
        config.put("status", intOrDefault(config.get("status"), 0));
        config.put("sortNo", intOrDefault(config.get("sortNo"), 0));
        config.put("remark", blankToNull(config.get("remark")));
        config.put("lifecycleStatus", retire ? "RETIRED" : "ACTIVE");
        return new Resolved(
            "ZONE", objectId, warehouse.companyId(), warehouseId, null, config,
            "CREATE".equals(changeType) ? null : readRevision("ZONE", objectId)
        );
    }

    private Resolved resolveStorage(
        long objectId,
        Map<String, Object> config,
        String changeType,
        boolean retire
    ) {
        long zoneId = requirePositive(config.get("zoneId"), "zoneId");
        ZoneRow zone = requireZone(zoneId);
        if (!"ACTIVE".equals(zone.lifecycleStatus())) {
            throw invalid("储位父库区必须处于 ACTIVE 生命周期");
        }
        WarehouseRow warehouse = requireWarehouse(zone.warehouseId());
        if (!"ACTIVE".equals(warehouse.lifecycleStatus())) {
            throw invalid("储位父仓库必须处于 ACTIVE 生命周期");
        }
        if (!WmsSpaceHierarchy.validStorageParent(
            warehouse.warehouseId(), zone.warehouseId(), zone.zoneKind())) {
            throw invalid("储位必须直属同仓 DEVICE 库区，禁止挂到 MATERIAL 或跳层");
        }
        if (zone.parentZoneId() == null) {
            throw invalid("设备库区缺少物料父库区");
        }
        ZoneRow material = requireZone(zone.parentZoneId());
        if (!"ACTIVE".equals(material.lifecycleStatus())
            || !WmsSpaceHierarchy.validZoneParent(
                zone.zoneKind(), zone.parentZoneId(), material.zoneKind(),
                material.warehouseId(), warehouse.warehouseId())) {
            throw invalid("储位父链中的物料库区生命周期或归属不一致");
        }
        String code = requireText(config.get("code"), "code", 64);
        normalizeAddress(config);
        if (!retire) {
            assertUniqueStorage(code, warehouse.warehouseId(), config,
                "CREATE".equals(changeType) ? null : objectId);
        }
        config.put("zoneId", zoneId);
        config.put("warehouseId", warehouse.warehouseId());
        config.put("companyId", warehouse.companyId());
        config.put("code", code);
        config.put("storageType", intOrDefault(config.get("storageType"), 1));
        config.put("status", intOrDefault(config.get("status"), 0));
        config.put("disableStatus", intOrDefault(config.get("disableStatus"), 1));
        config.put("portCode", blankToNull(config.get("portCode")));
        config.put("wcsPointCode", blankToNull(config.get("wcsPointCode")));
        config.put("remark", blankToNull(config.get("remark")));
        config.put("lifecycleStatus", retire ? "RETIRED" : "ACTIVE");
        return new Resolved(
            "STORAGE", objectId, warehouse.companyId(), warehouse.warehouseId(), zoneId, config,
            "CREATE".equals(changeType) ? null : readRevision("STORAGE", objectId)
        );
    }

    private int writeObject(OrderRow order, Resolved resolved, ObjectRevision current) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = LoginHelper.getUserId();
        Map<String, Object> config = resolved.config();
        return switch (order.objectType()) {
            case "WAREHOUSE" -> {
                if (current == null) {
                    jdbcTemplate.update("""
                        insert into wms_warehouse
                            (warehouse_id, company_id, warehouse_code, warehouse_name,
                             warehouse_type_code, type_config_version_id, lifecycle_status,
                             enabled, revision, remark, create_dept, create_by,
                             update_by, create_time, update_time)
                        values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?)
                        """,
                        resolved.objectId(), resolved.companyId(), config.get("code"),
                        config.get("name"), config.get("warehouseTypeCode"),
                        config.get("typeConfigVersionId"),
                        Boolean.TRUE.equals(config.get("enabled")) ? 1 : 0,
                        config.get("remark"), LoginHelper.getDeptId(), userId, userId,
                        now, now
                    );
                    yield 1;
                }
                int next = current.revision() + 1;
                int rows = jdbcTemplate.update("""
                    update wms_warehouse
                       set warehouse_code = ?, warehouse_name = ?,
                           warehouse_type_code = ?, type_config_version_id = ?,
                           enabled = ?, revision = ?,
                           remark = ?, update_by = ?, update_time = ?
                     where warehouse_id = ? and revision = ?
                    """,
                    config.get("code"), config.get("name"),
                    config.get("warehouseTypeCode"), config.get("typeConfigVersionId"),
                    Boolean.TRUE.equals(config.get("enabled")) ? 1 : 0, next,
                    config.get("remark"), userId, now,
                    resolved.objectId(), current.revision()
                );
                requireCas(rows, "仓库");
                yield next;
            }
            case "ZONE" -> {
                if (current == null) {
                    jdbcTemplate.update("""
                        insert into wms_warehouse_zone
                            (zone_id, zone_code, zone_name, zone_type, zone_kind,
                             lifecycle_status, parent_zone_id, warehouse_id, allow_inbound,
                             allow_outbound, status, revision, sort_no, del_flag, remark,
                             create_dept, create_by, update_by, create_time, update_time)
                        values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 1, ?, '0', ?,
                                ?, ?, ?, ?, ?)
                        """,
                        resolved.objectId(), config.get("code"), config.get("name"),
                        config.get("zoneType"), config.get("zoneKind"),
                        config.get("parentZoneId"), resolved.warehouseId(),
                        config.get("allowInbound"), config.get("allowOutbound"),
                        config.get("status"), config.get("sortNo"), config.get("remark"),
                        LoginHelper.getDeptId(), userId, userId, now, now
                    );
                    yield 1;
                }
                int next = current.revision() + 1;
                int rows = jdbcTemplate.update("""
                    update wms_warehouse_zone
                       set zone_code = ?, zone_name = ?, zone_type = ?,
                           allow_inbound = ?, allow_outbound = ?,
                           status = ?, revision = ?, sort_no = ?, del_flag = '0',
                           remark = ?, update_by = ?, update_time = ?
                     where zone_id = ? and revision = ?
                    """,
                    config.get("code"), config.get("name"), config.get("zoneType"),
                    config.get("allowInbound"), config.get("allowOutbound"),
                    config.get("status"), next,
                    config.get("sortNo"), config.get("remark"), userId, now,
                    resolved.objectId(), current.revision()
                );
                requireCas(rows, "库区");
                yield next;
            }
            case "STORAGE" -> {
                if (current == null) {
                    jdbcTemplate.update("""
                        insert into wms_storage
                            (storage_id, storage_code, zone_id, warehouse_id, storage_type,
                             status, disable_status, lifecycle_status, version, del_flag,
                             aisle_text, row_text, level_text, column_text, depth_text,
                             port_code, wcs_point_code, remark, create_dept, create_by,
                             update_by, create_time, update_time)
                        values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, '0',
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        resolved.objectId(), config.get("code"), resolved.zoneId(),
                        resolved.warehouseId(), config.get("storageType"),
                        config.get("status"), config.get("disableStatus"),
                        config.get("aisleText"), config.get("rowText"),
                        config.get("levelText"), config.get("columnText"),
                        config.get("depthText"), config.get("portCode"),
                        config.get("wcsPointCode"), config.get("remark"),
                        LoginHelper.getDeptId(), userId, userId, now, now
                    );
                    yield 1;
                }
                int next = current.revision() + 1;
                int rows = jdbcTemplate.update("""
                    update wms_storage
                       set storage_code = ?,
                           storage_type = ?, status = ?, disable_status = ?,
                           version = ?, del_flag = '0',
                           aisle_text = ?, row_text = ?, level_text = ?,
                           column_text = ?, depth_text = ?, port_code = ?,
                           wcs_point_code = ?, remark = ?, update_by = ?, update_time = ?
                     where storage_id = ? and version = ?
                    """,
                    config.get("code"), config.get("storageType"), config.get("status"),
                    config.get("disableStatus"), next, config.get("aisleText"),
                    config.get("rowText"), config.get("levelText"),
                    config.get("columnText"), config.get("depthText"),
                    config.get("portCode"), config.get("wcsPointCode"),
                    config.get("remark"), userId, now,
                    resolved.objectId(), current.revision()
                );
                requireCas(rows, "储位");
                yield next;
            }
            default -> throw invalid("不支持的空间对象类型: " + order.objectType());
        };
    }

    private int retireObject(String objectType, long objectId, int currentRevision) {
        int next = currentRevision + 1;
        int rows = switch (objectType) {
            case "WAREHOUSE" -> jdbcTemplate.update("""
                update wms_warehouse
                   set lifecycle_status = 'RETIRED', enabled = 0, revision = ?,
                       update_by = ?, update_time = current_timestamp(3)
                 where warehouse_id = ? and revision = ?
                """, next, LoginHelper.getUserId(), objectId, currentRevision);
            case "ZONE" -> jdbcTemplate.update("""
                update wms_warehouse_zone
                   set lifecycle_status = 'RETIRED', status = 0, revision = ?,
                       update_by = ?, update_time = current_timestamp(3)
                 where zone_id = ? and revision = ?
                """, next, LoginHelper.getUserId(), objectId, currentRevision);
            case "STORAGE" -> jdbcTemplate.update("""
                update wms_storage
                   set lifecycle_status = 'RETIRED', disable_status = 1, version = ?,
                       update_by = ?, update_time = current_timestamp(3)
                 where storage_id = ? and version = ?
                """, next, LoginHelper.getUserId(), objectId, currentRevision);
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
        requireCas(rows, objectType);
        return next;
    }

    private void lockParents(Resolved resolved) {
        lockParentChain(resolved.objectType(), resolved.objectId(), resolved);
    }

    private Long parentVersionId(Resolved resolved) {
        Long frozen = nullableLong(resolved.config().get(PARENT_LOCATION_VERSION_KEY));
        return frozen == null ? currentParentVersionId(resolved) : frozen;
    }

    private Long currentParentVersionId(Resolved resolved) {
        if ("ZONE".equals(resolved.objectType())) {
            Long parentZoneId = nullableLong(resolved.config().get("parentZoneId"));
            return parentZoneId == null
                ? currentVersionId("WAREHOUSE", resolved.warehouseId())
                : currentVersionId("ZONE", parentZoneId);
        }
        if ("STORAGE".equals(resolved.objectType())) {
            return currentVersionId("ZONE", resolved.zoneId());
        }
        return null;
    }

    private int switchEffective(String objectType, long objectId, long versionId) {
        List<Integer> existing = jdbcTemplate.query("""
            select revision from wms_location_effective
             where object_type = ? and object_id = ? for update
            """, (rs, rowNum) -> rs.getInt("revision"), objectType, objectId);
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                insert into wms_location_effective
                    (location_effective_id, object_type, object_id, location_version_id,
                     revision, create_dept, create_by, update_by)
                values (?, ?, ?, ?, 1, ?, ?, ?)
                """, nextId(), objectType, objectId, versionId,
                LoginHelper.getDeptId(), LoginHelper.getUserId(), LoginHelper.getUserId());
            return 1;
        }
        int current = existing.getFirst();
        int next = current + 1;
        int rows = jdbcTemplate.update("""
            update wms_location_effective
               set location_version_id = ?, revision = ?, update_by = ?,
                   update_time = current_timestamp(3)
             where object_type = ? and object_id = ? and revision = ?
            """, versionId, next, LoginHelper.getUserId(), objectType, objectId, current);
        requireCas(rows, "空间生效指针");
        return next;
    }

    private long currentVersionId(String objectType, long objectId) {
        return jdbcTemplate.query("""
            select location_version_id from wms_location_effective
             where object_type = ? and object_id = ?
            """, (rs, rowNum) -> rs.getLong("location_version_id"),
            objectType, objectId).stream().findFirst().orElse(0L);
    }

    private int nextLocationVersionNo(String objectType, long objectId) {
        Integer value = jdbcTemplate.queryForObject("""
            select coalesce(max(version_no), 0) + 1 from wms_location_version
             where object_type = ? and object_id = ?
            """, Integer.class, objectType, objectId);
        return value == null ? 1 : value;
    }

    private Map<String, Object> loadConfig(String objectType, long objectId) {
        return switch (objectType) {
            case "WAREHOUSE" -> {
                Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select company_id, warehouse_code, warehouse_name, warehouse_type_code,
                           type_config_version_id, lifecycle_status, enabled, revision, remark
                      from wms_warehouse where warehouse_id = ?
                    """, objectId);
                yield configMap(
                    "companyId", row.get("company_id"),
                    "code", row.get("warehouse_code"),
                    "name", row.get("warehouse_name"),
                    "warehouseTypeCode", row.get("warehouse_type_code"),
                    "typeConfigVersionId", row.get("type_config_version_id"),
                    "lifecycleStatus", row.get("lifecycle_status"),
                    "enabled", ((Number) row.get("enabled")).intValue() == 1,
                    "remark", row.get("remark")
                );
            }
            case "ZONE" -> {
                Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select z.zone_code, z.zone_name, z.zone_type, z.zone_kind,
                           z.lifecycle_status, z.parent_zone_id, z.warehouse_id,
                           z.allow_inbound, z.allow_outbound, z.status, z.revision,
                           z.sort_no, z.remark, w.company_id
                      from wms_warehouse_zone z
                      join wms_warehouse w on w.warehouse_id = z.warehouse_id
                     where z.zone_id = ?
                    """, objectId);
                yield configMap(
                    "companyId", row.get("company_id"),
                    "warehouseId", row.get("warehouse_id"),
                    "code", row.get("zone_code"),
                    "name", row.get("zone_name"),
                    "zoneType", row.get("zone_type"),
                    "zoneKind", row.get("zone_kind"),
                    "parentZoneId", row.get("parent_zone_id"),
                    "allowInbound", ((Number) row.get("allow_inbound")).intValue() == 1,
                    "allowOutbound", ((Number) row.get("allow_outbound")).intValue() == 1,
                    "status", row.get("status"),
                    "sortNo", row.get("sort_no"),
                    "remark", row.get("remark"),
                    "lifecycleStatus", row.get("lifecycle_status")
                );
            }
            case "STORAGE" -> {
                Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select s.storage_code, s.zone_id, s.warehouse_id, s.storage_type,
                           s.status, s.disable_status, s.lifecycle_status, s.version,
                           s.aisle_text, s.row_text, s.level_text, s.column_text,
                           s.depth_text, s.port_code, s.wcs_point_code, s.remark,
                           w.company_id
                      from wms_storage s
                      join wms_warehouse w on w.warehouse_id = s.warehouse_id
                     where s.storage_id = ?
                    """, objectId);
                yield configMap(
                    "companyId", row.get("company_id"),
                    "warehouseId", row.get("warehouse_id"),
                    "zoneId", row.get("zone_id"),
                    "code", row.get("storage_code"),
                    "storageType", row.get("storage_type"),
                    "status", row.get("status"),
                    "disableStatus", row.get("disable_status"),
                    "aisleText", row.get("aisle_text"),
                    "rowText", row.get("row_text"),
                    "levelText", row.get("level_text"),
                    "columnText", row.get("column_text"),
                    "depthText", row.get("depth_text"),
                    "portCode", row.get("port_code"),
                    "wcsPointCode", row.get("wcs_point_code"),
                    "remark", row.get("remark"),
                    "lifecycleStatus", row.get("lifecycle_status")
                );
            }
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
    }

    private void mergeCommand(
        Map<String, Object> target,
        WmsSpaceChangeCommand source,
        String objectType,
        String changeType
    ) {
        if (!"UPDATE".equals(changeType)) {
            put(target, "companyId", source.getCompanyId());
            put(target, "warehouseId", source.getWarehouseId());
            put(target, "zoneId", source.getZoneId());
            put(target, "parentZoneId", source.getParentZoneId());
            put(target, "zoneKind", source.getZoneKind());
        }
        put(target, "code", source.getCode());
        put(target, "name", source.getName());
        put(target, "warehouseTypeCode", source.getWarehouseTypeCode());
        put(target, "typeConfigVersionId", source.getTypeConfigVersionId());
        put(target, "zoneType", source.getZoneType());
        put(target, "allowInbound", source.getAllowInbound());
        put(target, "allowOutbound", source.getAllowOutbound());
        put(target, "enabled", source.getEnabled());
        put(target, "status", source.getStatus());
        put(target, "disableStatus", source.getDisableStatus());
        put(target, "storageType", source.getStorageType());
        put(target, "sortNo", source.getSortNo());
        put(target, "aisleText", source.getAisleText());
        put(target, "rowText", source.getRowText());
        put(target, "levelText", source.getLevelText());
        put(target, "columnText", source.getColumnText());
        put(target, "depthText", source.getDepthText());
        put(target, "portCode", source.getPortCode());
        put(target, "wcsPointCode", source.getWcsPointCode());
        put(target, "remark", source.getRemark());
        if ("CREATE".equals(changeType) && "STORAGE".equals(objectType)) {
            target.putIfAbsent("status", 0);
            target.putIfAbsent("disableStatus", 1);
        }
        if ("CREATE".equals(changeType) && "WAREHOUSE".equals(objectType)) {
            target.putIfAbsent("enabled", false);
        }
        if ("CREATE".equals(changeType) && "ZONE".equals(objectType)) {
            target.putIfAbsent("status", 0);
        }
    }

    private void assertUniqueWarehouse(long companyId, String code, Long excludeId) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*) from wms_warehouse
             where company_id = ? and BINARY warehouse_code = BINARY ?
               and (? is null or warehouse_id <> ?)
            """, Long.class, companyId, code, excludeId, excludeId);
        if (count != null && count > 0) {
            throw conflict("同一公司仓库编码已存在");
        }
    }

    private void assertUniqueZone(String code, Long excludeId) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*) from wms_warehouse_zone
             where BINARY zone_code = BINARY ?
               and (? is null or zone_id <> ?)
            """, Long.class, code, excludeId, excludeId);
        if (count != null && count > 0) {
            throw conflict("库区编码已存在");
        }
    }

    private void assertUniqueStorage(
        String code,
        long warehouseId,
        Map<String, Object> config,
        Long excludeId
    ) {
        Long codeCount = jdbcTemplate.queryForObject("""
            select count(*) from wms_storage
             where BINARY storage_code = BINARY ?
               and (? is null or storage_id <> ?)
            """, Long.class, code, excludeId, excludeId);
        if (codeCount != null && codeCount > 0) {
            throw conflict("储位编码已存在");
        }
        Long addressCount = jdbcTemplate.queryForObject("""
            select count(*) from wms_storage
             where warehouse_id = ?
               and aisle_text <=> ? and row_text <=> ? and level_text <=> ?
               and column_text <=> ? and depth_text <=> ?
               and (? is null or storage_id <> ?)
            """, Long.class, warehouseId,
            config.get("aisleText"), config.get("rowText"), config.get("levelText"),
            config.get("columnText"), config.get("depthText"), excludeId, excludeId);
        if (addressCount != null && addressCount > 0) {
            throw conflict("同一仓库五项地址已存在");
        }
    }

    private void assertNoRetirementReferences(String objectType, long objectId) {
        long references = switch (objectType) {
            case "WAREHOUSE" -> jdbcTemplate.queryForObject("""
                select
                    (select count(*) from wms_warehouse_zone where warehouse_id = ?)
                  + (select count(*) from wms_storage where warehouse_id = ?)
                """, Long.class, objectId, objectId);
            case "ZONE" -> jdbcTemplate.queryForObject("""
                select
                    (select count(*) from wms_warehouse_zone where parent_zone_id = ?)
                  + (select count(*) from wms_storage where zone_id = ?)
                """, Long.class, objectId, objectId);
            case "STORAGE" -> jdbcTemplate.queryForObject("""
                select
                    (select count(*) from wms_container where storage_id = ?)
                  + (select count(*) from wms_inventory where storage_id = ?)
                  + (select count(*) from wms_storage_occupancy
                      where storage_id = ? and status = 'ACTIVE')
                  + (select count(*) from wms_allocation_lock
                      where storage_id = ? and status = 'LOCKED')
                  + (select count(*) from wms_inventory_reservation
                      where inventory_id in (select inventory_id from wms_inventory
                                             where storage_id = ?)
                        and status = 'ACTIVE')
                """, Long.class, objectId, objectId, objectId, objectId, objectId);
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
        if (references > 0) {
            throw new ServiceException(
                ERROR_RETIRE_BLOCKED + ": 空间对象仍有子项、库存、占用或活动锁",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    private Resolved freezeParentVersion(Resolved resolved) {
        if ("ZONE".equals(resolved.objectType()) || "STORAGE".equals(resolved.objectType())) {
            Long parentVersion = currentParentVersionId(resolved);
            if (parentVersion == null || parentVersion <= 0) {
                throw conflict("空间父对象缺少当前生效版本，拒绝校验");
            }
            resolved.config().put(PARENT_LOCATION_VERSION_KEY, parentVersion);
            Long warehouseVersion = currentVersionId("WAREHOUSE", resolved.warehouseId());
            if (warehouseVersion == null || warehouseVersion <= 0) {
                throw conflict("空间父仓库缺少当前生效版本，拒绝校验");
            }
            resolved.config().put(WAREHOUSE_LOCATION_VERSION_KEY, warehouseVersion);
            if ("STORAGE".equals(resolved.objectType())) {
                ZoneRow zone = requireZone(resolved.zoneId());
                Long materialVersion = currentVersionId("ZONE", zone.parentZoneId());
                if (materialVersion == null || materialVersion <= 0) {
                    throw conflict("储位物料父库区缺少当前生效版本，拒绝校验");
                }
                resolved.config().put(MATERIAL_PARENT_LOCATION_VERSION_KEY, materialVersion);
            }
        }
        return resolved;
    }

    private void requireActiveLifecycle(Map<String, Object> config, String objectType) {
        String lifecycle = blankToNull(config.get("lifecycleStatus"));
        if (!"ACTIVE".equals(lifecycle)) {
            throw conflict(objectType + " 已不处于 ACTIVE 生命周期，普通更新和退役均拒绝");
        }
    }

    private void assertUpdateStructureUnchanged(
        String objectType,
        Map<String, Object> current,
        WmsSpaceChangeCommand command
    ) {
        switch (objectType) {
            case "WAREHOUSE" -> {
                if (command.getCompanyId() != null
                    && !Objects.equals(command.getCompanyId(), nullableLong(current.get("companyId")))) {
                    throw invalid("普通仓库更新不得改变公司归属；请使用明确的领域调整命令");
                }
            }
            case "ZONE" -> {
                assertSameStructure(current, "warehouseId", command.getWarehouseId(), "仓库归属");
                assertSameStructure(current, "parentZoneId", command.getParentZoneId(), "父库区");
                assertSameStructure(current, "zoneKind", command.getZoneKind(), "库区种类");
            }
            case "STORAGE" -> {
                assertSameStructure(current, "warehouseId", command.getWarehouseId(), "仓库归属");
                assertSameStructure(current, "zoneId", command.getZoneId(), "所属库区");
            }
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        }
    }

    private void assertSameStructure(
        Map<String, Object> current,
        String key,
        Object requested,
        String label
    ) {
        if (requested == null) {
            return;
        }
        Object existing = current.get(key);
        boolean same = existing instanceof Number left && requested instanceof Number right
            ? left.longValue() == right.longValue()
            : Objects.equals(existing, requested);
        if (!same) {
            throw invalid("普通更新不得改变" + label + "；请使用明确的领域调整命令");
        }
    }

    private void lockParentChain(String objectType, long objectId, Resolved resolved) {
        ParentIds ids = parentIds(objectType, objectId, resolved);
        for (Long warehouseId : new TreeSet<>(ids.warehouseIds())) {
            jdbcTemplate.queryForList(
                "select warehouse_id from wms_warehouse where warehouse_id = ? for update",
                warehouseId
            );
        }
        for (Long zoneId : new TreeSet<>(ids.zoneIds())) {
            jdbcTemplate.queryForList(
                "select zone_id from wms_warehouse_zone where zone_id = ? and del_flag = '0' for update",
                zoneId
            );
        }
    }

    private ParentIds parentIds(String objectType, long objectId, Resolved resolved) {
        Set<Long> warehouses = new LinkedHashSet<>();
        Set<Long> zones = new LinkedHashSet<>();
        if (resolved.warehouseId() != null) {
            warehouses.add(resolved.warehouseId());
        }
        if ("ZONE".equals(objectType)) {
            Long parentZoneId = nullableLong(resolved.config().get("parentZoneId"));
            if (parentZoneId != null) {
                zones.add(parentZoneId);
            }
        } else if ("STORAGE".equals(objectType)) {
            if (resolved.zoneId() != null) {
                zones.add(resolved.zoneId());
            }
            Long parentZoneId = jdbcTemplate.query("""
                select parent_zone_id from wms_warehouse_zone
                 where zone_id = ? and del_flag = '0'
                """, (rs, rowNum) -> nullableLongColumn(rs, "parent_zone_id"),
                resolved.zoneId()).stream().findFirst().orElse(null);
            if (parentZoneId != null) {
                zones.add(parentZoneId);
            }
        }
        // 对更新对象先读取旧父链，再与新父链合并；当前标准范围只允许同一父链，
        // 但锁集合仍按旧/新两侧稳定 ID 排序，防止未来调整命令形成反向锁序。
        if (objectId > 0) {
            switch (objectType) {
                case "WAREHOUSE" -> warehouses.add(objectId);
                case "ZONE" -> {
                    CurrentZone current = currentZone(objectId);
                    if (current != null) {
                        warehouses.add(current.warehouseId());
                        if (current.parentZoneId() != null) {
                            zones.add(current.parentZoneId());
                        }
                    }
                }
                case "STORAGE" -> {
                    CurrentStorage current = currentStorage(objectId);
                    if (current != null) {
                        warehouses.add(current.warehouseId());
                        zones.add(current.zoneId());
                        CurrentZone zone = currentZone(current.zoneId());
                        if (zone != null && zone.parentZoneId() != null) {
                            zones.add(zone.parentZoneId());
                        }
                    }
                }
                default -> throw invalid("不支持的空间对象类型: " + objectType);
            }
        }
        return new ParentIds(warehouses, zones);
    }

    private void validateLockedTarget(
        OrderRow order,
        Resolved resolved,
        ObjectRevision current
    ) {
        if ("CREATE".equals(order.changeType())) {
            if (current != null) {
                throw conflict("空间对象身份已被占用");
            }
            return;
        }
        if (current == null) {
            throw new ServiceException("空间对象不存在", HttpStatus.HTTP_NOT_FOUND);
        }
        if (!"ACTIVE".equals(current.lifecycleStatus())) {
            throw conflict("空间对象已不处于 ACTIVE 生命周期");
        }
        if (order.expectedRevision() == null
            || current.revision() != order.expectedRevision()) {
            throw versionConflict("空间对象修订号已变化");
        }
    }

    private void validateLockedParents(Resolved resolved) {
        if ("WAREHOUSE".equals(resolved.objectType())) {
            return;
        }
        WarehouseRow warehouse = lockWarehouse(resolved.warehouseId());
        if (warehouse == null || !"ACTIVE".equals(warehouse.lifecycleStatus())
            || !Objects.equals(warehouse.companyId(), resolved.companyId())) {
            throw versionConflict("空间父仓库生命周期、归属或当前版本已变化");
        }
        if ("ZONE".equals(resolved.objectType())) {
            Long parentZoneId = nullableLong(resolved.config().get("parentZoneId"));
            if (parentZoneId == null) {
                if (versionChanged(
                    resolved.config().get(PARENT_LOCATION_VERSION_KEY),
                    currentVersionId("WAREHOUSE", resolved.warehouseId())
                )) {
                    throw versionConflict("库区父仓库版本已变化");
                }
                return;
            }
            ZoneRow parent = lockZone(parentZoneId);
            if (parent == null || !"ACTIVE".equals(parent.lifecycleStatus())
                || !WmsSpaceHierarchy.validZoneParent(
                    textOrDefault(resolved.config().get("zoneKind"), ""),
                    parentZoneId, parent.zoneKind(), parent.warehouseId(),
                    resolved.warehouseId())) {
                throw versionConflict("库区父链或父生命周期已变化");
            }
            Long expected = nullableLong(resolved.config().get(PARENT_LOCATION_VERSION_KEY));
            Long actual = currentVersionId("ZONE", parentZoneId);
            if (expected == null || !Objects.equals(expected, actual)) {
                throw versionConflict("库区父版本已变化");
            }
            if (versionChanged(
                resolved.config().get(WAREHOUSE_LOCATION_VERSION_KEY),
                currentVersionId("WAREHOUSE", resolved.warehouseId())
            )) {
                throw versionConflict("库区父仓库版本已变化");
            }
            return;
        }
        ZoneRow zone = lockZone(resolved.zoneId());
        if (zone == null || !"ACTIVE".equals(zone.lifecycleStatus())
            || !WmsSpaceHierarchy.validStorageParent(
                resolved.warehouseId(), zone.warehouseId(), zone.zoneKind())) {
            throw versionConflict("储位父库区或生命周期已变化");
        }
        ZoneRow material = lockZone(zone.parentZoneId());
        if (material == null || !"ACTIVE".equals(material.lifecycleStatus())
            || !WmsSpaceHierarchy.validZoneParent(
                zone.zoneKind(), zone.parentZoneId(), material.zoneKind(),
                material.warehouseId(), resolved.warehouseId())) {
            throw versionConflict("储位父链已变化");
        }
        Long expected = nullableLong(resolved.config().get(PARENT_LOCATION_VERSION_KEY));
        Long actual = currentVersionId("ZONE", resolved.zoneId());
        if (expected == null || !Objects.equals(expected, actual)) {
            throw versionConflict("储位父版本已变化");
        }
        if (versionChanged(
            resolved.config().get(MATERIAL_PARENT_LOCATION_VERSION_KEY),
            currentVersionId("ZONE", material.zoneId())
        ) || versionChanged(
            resolved.config().get(WAREHOUSE_LOCATION_VERSION_KEY),
            currentVersionId("WAREHOUSE", resolved.warehouseId())
        )) {
            throw versionConflict("储位父链版本已变化");
        }
    }

    private boolean versionChanged(Object expected, Long actual) {
        Long parsed = nullableLong(expected);
        return parsed == null || actual == null || !Objects.equals(parsed, actual);
    }

    private WarehouseRow lockWarehouse(long warehouseId) {
        return jdbcTemplate.query("""
            select warehouse_id, company_id, lifecycle_status
              from wms_warehouse where warehouse_id = ? for update
            """, (rs, rowNum) -> new WarehouseRow(
            rs.getLong("warehouse_id"), rs.getLong("company_id"),
            rs.getString("lifecycle_status")), warehouseId).stream().findFirst().orElse(null);
    }

    private ZoneRow lockZone(Long zoneId) {
        if (zoneId == null || zoneId <= 0) {
            return null;
        }
        return jdbcTemplate.query("""
            select zone_id, warehouse_id, zone_kind, parent_zone_id, lifecycle_status
              from wms_warehouse_zone
             where zone_id = ? and del_flag = '0' for update
            """, (rs, rowNum) -> new ZoneRow(
            rs.getLong("zone_id"), rs.getLong("warehouse_id"),
            rs.getString("zone_kind"),
            nullableLongColumn(rs, "parent_zone_id"),
            rs.getString("lifecycle_status")), zoneId).stream().findFirst().orElse(null);
    }

    private CurrentZone currentZone(long zoneId) {
        return jdbcTemplate.query("""
            select warehouse_id, parent_zone_id
              from wms_warehouse_zone
             where zone_id = ? and del_flag = '0'
            """, (rs, rowNum) -> new CurrentZone(
            rs.getLong("warehouse_id"), nullableLongColumn(rs, "parent_zone_id")),
            zoneId).stream().findFirst().orElse(null);
    }

    private CurrentStorage currentStorage(long storageId) {
        return jdbcTemplate.query("""
            select warehouse_id, zone_id
              from wms_storage
             where storage_id = ? and del_flag = '0'
            """, (rs, rowNum) -> new CurrentStorage(
            rs.getLong("warehouse_id"), rs.getLong("zone_id")),
            storageId).stream().findFirst().orElse(null);
    }

    private WarehouseRow requireWarehouse(long warehouseId) {
        return jdbcTemplate.query("""
            select warehouse_id, company_id, lifecycle_status
              from wms_warehouse where warehouse_id = ?
            """, (rs, rowNum) -> new WarehouseRow(
            rs.getLong("warehouse_id"),
            rs.getLong("company_id"),
            rs.getString("lifecycle_status")
        ), warehouseId).stream().findFirst().orElseThrow(() ->
            new ServiceException("仓库不存在", HttpStatus.HTTP_NOT_FOUND));
    }

    private ZoneRow requireZone(long zoneId) {
        return jdbcTemplate.query("""
            select zone_id, warehouse_id, zone_kind, parent_zone_id, lifecycle_status
              from wms_warehouse_zone where zone_id = ? and del_flag = '0'
            """, (rs, rowNum) -> new ZoneRow(
            rs.getLong("zone_id"), rs.getLong("warehouse_id"),
            rs.getString("zone_kind"),
            nullableLongColumn(rs, "parent_zone_id"),
            rs.getString("lifecycle_status")
        ), zoneId).stream().findFirst().orElseThrow(() ->
            new ServiceException("库区不存在", HttpStatus.HTTP_NOT_FOUND));
    }

    private ObjectRevision lockTarget(String objectType, long objectId) {
        List<ObjectRevision> rows = switch (objectType) {
            case "WAREHOUSE" -> jdbcTemplate.query("""
                select revision, lifecycle_status from wms_warehouse
                 where warehouse_id = ? for update
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("revision"), rs.getString("lifecycle_status")), objectId);
            case "ZONE" -> jdbcTemplate.query("""
                select revision, lifecycle_status from wms_warehouse_zone
                 where zone_id = ? and del_flag = '0' for update
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("revision"), rs.getString("lifecycle_status")), objectId);
            case "STORAGE" -> jdbcTemplate.query("""
                select version, lifecycle_status from wms_storage
                 where storage_id = ? and del_flag = '0' for update
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("version"), rs.getString("lifecycle_status")), objectId);
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ObjectRevision requireTarget(String objectType, long objectId) {
        ObjectRevision row = lockTarget(objectType, objectId);
        if (row == null) {
            throw new ServiceException("空间对象不存在", HttpStatus.HTTP_NOT_FOUND);
        }
        return row;
    }

    private Integer readRevision(String objectType, long objectId) {
        ObjectRevision row = readTarget(objectType, objectId);
        return row == null ? null : row.revision();
    }

    private ObjectRevision readTarget(String objectType, long objectId) {
        List<ObjectRevision> rows = switch (objectType) {
            case "WAREHOUSE" -> jdbcTemplate.query("""
                select revision, lifecycle_status from wms_warehouse where warehouse_id = ?
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("revision"), rs.getString("lifecycle_status")), objectId);
            case "ZONE" -> jdbcTemplate.query("""
                select revision, lifecycle_status from wms_warehouse_zone
                 where zone_id = ? and del_flag = '0'
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("revision"), rs.getString("lifecycle_status")), objectId);
            case "STORAGE" -> jdbcTemplate.query("""
                select version, lifecycle_status from wms_storage
                 where storage_id = ? and del_flag = '0'
                """, (rs, rowNum) -> new ObjectRevision(
                rs.getInt("version"), rs.getString("lifecycle_status")), objectId);
            default -> throw invalid("不支持的空间对象类型: " + objectType);
        };
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Resolved resolvedFromOrder(OrderRow order) {
        if (order.validatedConfigJson() == null) {
            throw conflict("空间变更单缺少冻结配置");
        }
        String actualDigest = WmsRuleDigest.sha256(WmsRuleDigest.canonical(
            jsonMapper.readTree(order.validatedConfigJson())));
        if (!Objects.equals(actualDigest, order.canonicalDigest())) {
            throw conflict("空间变更冻结配置摘要不一致");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = jsonMapper.readValue(
            order.validatedConfigJson(), Map.class);
        return new Resolved(
            order.objectType(),
            order.objectId(),
            order.companyId(),
            order.warehouseId(),
            order.zoneId(),
            new LinkedHashMap<>(config),
            order.expectedRevision()
        );
    }

    private OrderRow lockOrder(long changeOrderId) {
        if (changeOrderId <= 0) {
            throw invalid("changeOrderId 必须为正整数");
        }
        return jdbcTemplate.query("""
            select change_order_id, command_key, change_type, object_type, object_id,
                   expected_revision, company_id, warehouse_id, zone_id, input_hash,
                   input_json, validated_config_json, canonical_digest, status,
                   revision, location_version_id, location_effective_revision,
                   object_revision, create_by
              from wms_space_change_order
             where change_order_id = ? for update
            """, (rs, rowNum) -> orderRow(rs), changeOrderId).stream().findFirst()
            .orElseThrow(() -> new ServiceException(
                "空间变更单不存在", HttpStatus.HTTP_NOT_FOUND));
    }

    private OrderRow findOrderByCommandKey(String commandKey) {
        return jdbcTemplate.query("""
            select change_order_id, command_key, change_type, object_type, object_id,
                   expected_revision, company_id, warehouse_id, zone_id, input_hash,
                   input_json, validated_config_json, canonical_digest, status,
                   revision, location_version_id, location_effective_revision,
                   object_revision, create_by
              from wms_space_change_order where command_key = ?
            """, (rs, rowNum) -> orderRow(rs), commandKey).stream().findFirst().orElse(null);
    }

    private OrderRow findOrderByCommandKeyCurrent(String commandKey) {
        return jdbcTemplate.query("""
            select change_order_id, command_key, change_type, object_type, object_id,
                   expected_revision, company_id, warehouse_id, zone_id, input_hash,
                   input_json, validated_config_json, canonical_digest, status,
                   revision, location_version_id, location_effective_revision,
                   object_revision, create_by
              from wms_space_change_order where command_key = ? for update
            """, (rs, rowNum) -> orderRow(rs), commandKey).stream().findFirst().orElse(null);
    }

    private WmsSpaceChangeValidation validation(OrderRow order) {
        return new WmsSpaceChangeValidation(
            order.orderId(),
            order.changeType(),
            order.objectType(),
            requireObjectId(order),
            order.expectedRevision(),
            order.objectRevision(),
            order.companyId(),
            order.warehouseId(),
            order.zoneId(),
            order.validatedConfigJson(),
            order.canonicalDigest(),
            order.status()
        );
    }

    private WmsSpaceChangeResult result(OrderRow order) {
        return new WmsSpaceChangeResult(
            order.orderId(),
            order.changeType(),
            order.objectType(),
            requireObjectId(order),
            order.status(),
            order.canonicalDigest(),
            order.locationVersionId(),
            order.locationEffectiveRevision(),
            order.objectRevision()
        );
    }

    private long requireObjectId(OrderRow order) {
        if (order.objectId() == null) {
            throw conflict("空间变更单尚未分配对象身份");
        }
        return order.objectId();
    }

    private Long initiatorId(OrderRow order) {
        if (order.createBy() == null || order.createBy() <= 0) {
            throw conflict("空间变更单缺少冻结发起人，拒绝后台执行");
        }
        return order.createBy();
    }

    private void validateCommand(WmsSpaceChangeCommand command) {
        if (command == null) {
            throw invalid("请求体不能为空");
        }
        if (command.getCommandKey() == null || command.getCommandKey().isBlank()
            || command.getCommandKey().length() > 96) {
            throw invalid("commandKey 必须为 1 到 96 个字符");
        }
        command.setChangeType(normalize(command.getChangeType()));
        command.setObjectType(normalize(command.getObjectType()));
        if (command.getChangeType() == null
            || !CHANGE_TYPES.contains(command.getChangeType())) {
            throw invalid("changeType 必须为 CREATE/UPDATE/RETIRE");
        }
        if (command.getObjectType() == null
            || !OBJECT_TYPES.contains(command.getObjectType())) {
            throw invalid("objectType 必须为 WAREHOUSE/ZONE/STORAGE");
        }
        if (!"CREATE".equals(command.getChangeType())
            && (command.getObjectId() == null || command.getObjectId() <= 0
            || command.getExpectedRevision() == null || command.getExpectedRevision() < 0)) {
            throw invalid("更新或退役必须提供 objectId 和 expectedRevision");
        }
        if (command.getReason() == null || command.getReason().isBlank()
            || command.getReason().length() > 500) {
            throw invalid("reason 必须为 1 到 500 个字符");
        }
    }

    private WmsSpaceChangeCommand readCommand(String json) {
        WmsSpaceChangeCommand command = jsonMapper.readValue(json, WmsSpaceChangeCommand.class);
        validateCommand(command);
        return command;
    }

    private void normalizeAddress(Map<String, Object> config) {
        for (String key : List.of(
            "aisleText", "rowText", "levelText", "columnText", "depthText"
        )) {
            String value = blankToNull(config.get(key));
            if (value != null && value.codePointCount(0, value.length()) > 64) {
                throw invalid(key + " 不能超过 64 个字符");
            }
            config.put(key, value);
        }
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> configMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String requireText(Object value, String name, int maxLength) {
        String text = blankToNull(value);
        if (text == null || text.length() > maxLength) {
            throw invalid(name + " 必须为 1 到 " + maxLength + " 个字符");
        }
        return text;
    }

    private static long requirePositive(Object value, String name) {
        Long parsed = nullableLong(value);
        if (parsed == null || parsed <= 0) {
            throw invalid(name + " 必须为正整数");
        }
        return parsed;
    }

    private static long longValue(Object value, String name) {
        return requirePositive(value, name);
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int intOrDefault(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static boolean boolOrDefault(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return defaultValue;
    }

    private static String textOrDefault(Object value, String defaultValue) {
        String text = blankToNull(value);
        return text == null ? defaultValue : text;
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ' ') {
            end--;
        }
        return start == end ? null : text.substring(start, end);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean sameJson(String stored, String candidate) {
        try {
            return Objects.equals(
                WmsRuleDigest.canonical(jsonMapper.readTree(stored)),
                WmsRuleDigest.canonical(jsonMapper.readTree(candidate))
            );
        } catch (RuntimeException error) {
            return Objects.equals(stored, candidate);
        }
    }

    private static String limit(String value, int maxLength) {
        return value != null && value.length() > maxLength
            ? value.substring(0, maxLength) : value;
    }

    private void requireCas(int rows, String label) {
        if (rows != 1) {
            throw versionConflict(label + "修订已变化");
        }
    }

    private long nextId() {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null) {
            throw new IllegalStateException("空间对象主键生成失败");
        }
        return generated.longValue();
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(
            ERROR_INVALID + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }

    private static ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.HTTP_CONFLICT);
    }

    private static ServiceException versionConflict(String message) {
        return new ServiceException(
            ERROR_VERSION_CONFLICT + ": " + message,
            HttpStatus.HTTP_CONFLICT
        );
    }

    private static OrderRow orderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(
            rs.getLong("change_order_id"),
            rs.getString("command_key"),
            rs.getString("change_type"),
            rs.getString("object_type"),
            nullableLongColumn(rs, "object_id"),
            nullableInt(rs, "expected_revision"),
            nullableLongColumn(rs, "company_id"),
            nullableLongColumn(rs, "warehouse_id"),
            nullableLongColumn(rs, "zone_id"),
            rs.getString("input_hash"),
            rs.getString("input_json"),
            rs.getString("validated_config_json"),
            rs.getString("canonical_digest"),
            rs.getString("status"),
            rs.getInt("revision"),
            nullableLongColumn(rs, "location_version_id"),
            nullableInt(rs, "location_effective_revision"),
            nullableInt(rs, "object_revision"),
            nullableLongColumn(rs, "create_by")
        );
    }

    private static Long nullableLongColumn(java.sql.ResultSet rs, String name)
        throws java.sql.SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String name)
        throws java.sql.SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private record WarehouseRow(long warehouseId, long companyId, String lifecycleStatus) {
    }

    private record ZoneRow(
        long zoneId,
        long warehouseId,
        String zoneKind,
        Long parentZoneId,
        String lifecycleStatus
    ) {
    }

    private record ObjectRevision(int revision, String lifecycleStatus) {
    }

    private record ParentIds(Set<Long> warehouseIds, Set<Long> zoneIds) {
    }

    private record CurrentZone(long warehouseId, Long parentZoneId) {
    }

    private record CurrentStorage(long warehouseId, long zoneId) {
    }

    private record Resolved(
        String objectType,
        long objectId,
        Long companyId,
        Long warehouseId,
        Long zoneId,
        Map<String, Object> config,
        Integer currentRevision
    ) {
    }

    private record OrderRow(
        long orderId,
        String commandKey,
        String changeType,
        String objectType,
        Long objectId,
        Integer expectedRevision,
        Long companyId,
        Long warehouseId,
        Long zoneId,
        String inputHash,
        String inputJson,
        String validatedConfigJson,
        String canonicalDigest,
        String status,
        int revision,
        Long locationVersionId,
        Integer locationEffectiveRevision,
        Integer objectRevision,
        Long createBy
    ) {
    }
}
