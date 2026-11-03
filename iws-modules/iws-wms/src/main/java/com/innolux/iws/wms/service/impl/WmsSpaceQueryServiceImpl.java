/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 仓库、库区和储位的公司范围只读分页与五项精确定位；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.WmsStorage;
import com.innolux.iws.wms.domain.WmsWarehouse;
import com.innolux.iws.wms.domain.WmsWarehouseZone;
import com.innolux.iws.wms.domain.bo.IwsBulkConditionBo;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsSortConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.bo.WmsStorageAddressFieldBo;
import com.innolux.iws.wms.domain.bo.WmsStorageLocateBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.domain.vo.WmsStorageVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseZoneVo;
import com.innolux.iws.wms.mapper.WmsStorageMapper;
import com.innolux.iws.wms.mapper.WmsWarehouseMapper;
import com.innolux.iws.wms.mapper.WmsWarehouseZoneMapper;
import com.innolux.iws.wms.service.IWmsSpaceQueryService;
import com.innolux.iws.wms.service.WmsSpaceContract;
import com.innolux.iws.wms.service.WmsSpaceReadinessGuard;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.support.query.IwsCrudConditionSupport;
import com.innolux.iws.wms.support.query.IwsCrudQueryValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 空间主数据只读查询实现。
 *
 * <p>公司管理范围与库存可见范围是两套合同；本实现只应用前者，不读取或声明
 * {@code wms_object_visibility}。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WmsSpaceQueryServiceImpl implements IWmsSpaceQueryService {

    private final WmsWarehouseMapper warehouseMapper;

    private final WmsWarehouseZoneMapper zoneMapper;

    private final WmsStorageMapper storageMapper;

    private final WmsCompanyAccessPolicy accessPolicy;

    private final IwsCrudQueryValidator queryValidator;

    private final WmsSpaceReadinessGuard readinessGuard;

    @Override
    public PageResult<WmsWarehouseVo> selectWarehousePage(IwsTableQueryBo queryBo) {
        accessPolicy.checkPermission(WmsSpaceContract.PERMISSION_WAREHOUSE_QUERY);
        IwsCrudTableMetaVo meta = WmsSpaceContract.warehouseMeta();
        queryValidator.validateQuery(meta, queryBo);
        readinessGuard.assertWarehouseReadable();
        QueryWrapper<WmsWarehouse> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, meta, queryBo);
        accessPolicy.applyCompanyScope(wrapper, "wms_warehouse.company_id");
        applySort(wrapper, meta, queryBo, "warehouse_id", "warehouse_code", "warehouse_id");
        Page<WmsWarehouse> page = warehouseMapper.selectPage(
            new Page<>(queryBo.getPageNum(), queryBo.getPageSize()), wrapper
        );
        List<WmsWarehouseVo> rows = page.getRecords().stream()
            .map(this::toWarehouseVo)
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public PageResult<WmsWarehouseZoneVo> selectZonePage(IwsTableQueryBo queryBo) {
        accessPolicy.checkPermission(WmsSpaceContract.PERMISSION_ZONE_QUERY);
        IwsCrudTableMetaVo meta = WmsSpaceContract.zoneMeta();
        queryValidator.validateQuery(meta, queryBo);
        readinessGuard.assertZoneReadable(scope(queryBo));
        QueryWrapper<WmsWarehouseZone> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, meta, queryBo);
        wrapper.isNotNull("warehouse_id");
        applyZoneParentScope(wrapper);
        accessPolicy.applyWarehouseCompanyScope(wrapper, "wms_warehouse_zone.warehouse_id");
        applySort(wrapper, meta, queryBo, "zone_id", "warehouse_id", "zone_code", "zone_id");
        Page<WmsWarehouseZone> page = zoneMapper.selectPage(
            new Page<>(queryBo.getPageNum(), queryBo.getPageSize()), wrapper
        );
        Map<Long, WmsWarehouse> warehouses = warehousesOf(
            page.getRecords().stream().map(WmsWarehouseZone::getWarehouseId).toList()
        );
        List<WmsWarehouseZoneVo> rows = page.getRecords().stream()
            .map(zone -> toZoneVo(zone, warehouses.get(zone.getWarehouseId())))
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public PageResult<WmsStorageVo> selectStoragePage(IwsTableQueryBo queryBo) {
        accessPolicy.checkPermission(WmsSpaceContract.PERMISSION_STORAGE_QUERY);
        IwsCrudTableMetaVo meta = WmsSpaceContract.storageMeta();
        queryValidator.validateQuery(meta, queryBo);
        readinessGuard.assertStorageReadable(scope(queryBo));
        QueryWrapper<WmsStorage> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, meta, queryBo);
        wrapper.isNotNull("warehouse_id");
        applyStorageParentScope(wrapper);
        accessPolicy.applyWarehouseCompanyScope(wrapper, "wms_storage.warehouse_id");
        applySort(wrapper, meta, queryBo, "storage_id",
            "warehouse_id", "zone_id", "storage_code", "storage_id");
        Page<WmsStorage> page = storageMapper.selectPage(
            new Page<>(queryBo.getPageNum(), queryBo.getPageSize()), wrapper
        );
        List<WmsStorageVo> rows = toStorageVos(page.getRecords());
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public PageResult<WmsStorageVo> locateStorage(WmsStorageLocateBo locateBo) {
        accessPolicy.checkPermission(WmsSpaceContract.PERMISSION_STORAGE_QUERY);
        Map<String, String> address = parseAddress(locateBo);
        readinessGuard.assertStorageReadable(new WmsSpaceReadinessGuard.Scope(
            accessPolicy.accessibleCompanyIds(), locateBo.getWarehouseId(), null, null
        ));
        QueryWrapper<WmsStorage> wrapper = new QueryWrapper<>();
        wrapper.eq("warehouse_id", locateBo.getWarehouseId());
        for (String field : WmsSpaceContract.ADDRESS_FIELDS) {
            String column = WmsSpaceContract.ADDRESS_COLUMN_BY_FIELD.get(field);
            String value = address.get(field);
            if (value == null) {
                wrapper.isNull(column);
            } else {
                wrapper.eq(column, value);
            }
        }
        applyStorageParentScope(wrapper);
        accessPolicy.applyWarehouseCompanyScope(wrapper, "wms_storage.warehouse_id");
        List<WmsStorage> matches = storageMapper.selectList(wrapper.last("limit 2"));
        if (matches.size() > 1) {
            throw new ServiceException(
                WmsSpaceContract.ERROR_AMBIGUOUS
                    + ": 同一仓库存在多条相同五项地址，S1 唯一约束尚未建立，拒绝返回任意一条",
                HttpStatus.HTTP_CONFLICT
            );
        }
        List<WmsStorageVo> rows = toStorageVos(matches);
        return PageResult.build(rows, rows.size());
    }

    private Map<String, String> parseAddress(WmsStorageLocateBo locateBo) {
        if (locateBo == null || locateBo.getWarehouseId() == null || locateBo.getWarehouseId() <= 0) {
            throw addressPresentMismatch("warehouseId 必须显式提供为正整数");
        }
        List<WmsStorageAddressFieldBo> fields = locateBo.getAddressFields();
        if (fields == null || fields.size() != WmsSpaceContract.ADDRESS_FIELDS.size()) {
            throw addressPresentMismatch("addressFields 必须恰含五项地址字段");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (WmsStorageAddressFieldBo field : fields) {
            if (field == null) {
                throw addressPresentMismatch("地址字段条目不能为空");
            }
            if (field.hasDuplicateKeys()) {
                throw addressPresentMismatch("地址字段条目重复出现 field/value 键");
            }
            if (field.getField() == null || field.getField().isEmpty()) {
                throw addressPresentMismatch("地址字段名不能为空");
            }
            if (!WmsSpaceContract.ADDRESS_COLUMN_BY_FIELD.containsKey(field.getField())) {
                throw addressPresentMismatch("未知地址字段: " + field.getField());
            }
            if (!field.isValuePresent()) {
                throw addressPresentMismatch("地址字段缺少 value 键: " + field.getField());
            }
            if (values.containsKey(field.getField())) {
                throw addressPresentMismatch("地址字段重复: " + field.getField());
            }
            values.put(field.getField(), normalizeAddressValue(field.getValue(), field.getField()));
        }
        if (values.size() != WmsSpaceContract.ADDRESS_FIELDS.size()) {
            throw addressPresentMismatch("addressFields 必须恰含 AISLE/ROW/LEVEL/COLUMN/DEPTH");
        }
        return values;
    }

    private String normalizeAddressValue(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw addressValueInvalid("地址值必须为字符串或 null: " + field);
        }
        String text = value.textValue();
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ' ') {
            end--;
        }
        if (start == end) {
            return null;
        }
        String normalized = text.substring(start, end);
        if (normalized.codePointCount(0, normalized.length()) > WmsSpaceContract.MAX_ADDRESS_VALUE_LENGTH) {
            throw addressValueInvalid("地址值超过 64 个字符: " + field);
        }
        return normalized;
    }

    private ServiceException addressPresentMismatch(String message) {
        return new ServiceException(
            WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }

    private ServiceException addressValueInvalid(String message) {
        return new ServiceException(
            WmsSpaceContract.ERROR_ADDRESS_VALUE_INVALID + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }

    private <T> void applyConditions(QueryWrapper<T> wrapper,
                                     IwsCrudTableMetaVo meta,
                                     IwsTableQueryBo queryBo) {
        Map<String, IwsCrudColumnMetaDto> columns = columns(meta);
        for (IwsQueryConditionBo condition : queryBo.getConditions()) {
            IwsCrudColumnMetaDto column = columns.get(condition.getField());
            IwsCrudConditionSupport.apply(wrapper, column, condition);
        }
        for (IwsBulkConditionBo condition : queryBo.getBulkConditions()) {
            IwsCrudColumnMetaDto column = columns.get(condition.getField());
            if (column != null && condition.getValues() != null && !condition.getValues().isEmpty()) {
                wrapper.in(column.getDbColumn(), condition.getValues());
            }
        }
    }

    private <T> void applySort(QueryWrapper<T> wrapper,
                               IwsCrudTableMetaVo meta,
                               IwsTableQueryBo queryBo,
                               String primaryDbColumn,
                               String... defaultColumns) {
        List<IwsSortConditionBo> sorts = queryBo.getSorts();
        if (sorts == null || sorts.isEmpty()) {
            wrapper.orderByAsc(List.of(defaultColumns));
            return;
        }
        Map<String, IwsCrudColumnMetaDto> columns = columns(meta);
        boolean primarySorted = false;
        for (IwsSortConditionBo sort : sorts) {
            IwsCrudColumnMetaDto column = columns.get(sort.getField());
            boolean asc = !"desc".equalsIgnoreCase(sort.getOrder());
            wrapper.orderBy(true, asc, column.getDbColumn());
            primarySorted = primarySorted || primaryDbColumn.equals(column.getDbColumn());
        }
        if (!primarySorted) {
            wrapper.orderByAsc(primaryDbColumn);
        }
    }

    private Map<String, IwsCrudColumnMetaDto> columns(IwsCrudTableMetaVo meta) {
        return meta.getColumns().stream()
            .collect(Collectors.toMap(IwsCrudColumnMetaDto::getField, Function.identity()));
    }

    private void applyZoneParentScope(QueryWrapper<WmsWarehouseZone> wrapper) {
        wrapper.apply("""
            (parent_zone_id is null or exists (
                select 1 from wms_warehouse_zone scope_parent
                where scope_parent.zone_id = wms_warehouse_zone.parent_zone_id
                  and scope_parent.del_flag = '0'
                  and scope_parent.warehouse_id = wms_warehouse_zone.warehouse_id
            ))
            """);
    }

    private void applyStorageParentScope(QueryWrapper<WmsStorage> wrapper) {
        wrapper.apply("""
            exists (
                select 1 from wms_warehouse_zone scope_zone
                where scope_zone.zone_id = wms_storage.zone_id
                  and scope_zone.del_flag = '0'
                  and scope_zone.warehouse_id = wms_storage.warehouse_id
                  and (scope_zone.parent_zone_id is null or exists (
                      select 1 from wms_warehouse_zone scope_parent
                      where scope_parent.zone_id = scope_zone.parent_zone_id
                        and scope_parent.del_flag = '0'
                        and scope_parent.warehouse_id = scope_zone.warehouse_id
                  ))
            )
            """);
    }

    private WmsSpaceReadinessGuard.Scope scope(IwsTableQueryBo queryBo) {
        return new WmsSpaceReadinessGuard.Scope(
            accessPolicy.accessibleCompanyIds(),
            equalityTarget(queryBo, "warehouseId"),
            equalityTarget(queryBo, "zoneId"),
            equalityTarget(queryBo, "storageId")
        );
    }

    private Long equalityTarget(IwsTableQueryBo queryBo, String field) {
        for (IwsQueryConditionBo condition : queryBo.getConditions()) {
            if (!field.equals(condition.getField())) {
                continue;
            }
            if (condition.getQueryMode() != null && !"eq".equals(condition.getQueryMode())) {
                continue;
            }
            Object value = condition.getValue();
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Long.valueOf(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Map<Long, WmsWarehouse> warehousesOf(Collection<Long> warehouseIds) {
        Set<Long> ids = warehouseIds.stream().filter(id -> id != null).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<WmsWarehouse> wrapper = new QueryWrapper<>();
        wrapper.in("warehouse_id", ids);
        accessPolicy.applyCompanyScope(wrapper, "wms_warehouse.company_id");
        return warehouseMapper.selectList(wrapper).stream()
            .collect(Collectors.toMap(WmsWarehouse::getWarehouseId, Function.identity()));
    }

    private Map<Long, WmsWarehouseZone> zonesOf(Collection<Long> zoneIds) {
        Set<Long> ids = zoneIds.stream().filter(id -> id != null).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<WmsWarehouseZone> wrapper = new QueryWrapper<>();
        wrapper.in("zone_id", ids);
        accessPolicy.applyWarehouseCompanyScope(wrapper, "wms_warehouse_zone.warehouse_id");
        return zoneMapper.selectList(wrapper).stream()
            .collect(Collectors.toMap(WmsWarehouseZone::getZoneId, Function.identity()));
    }

    private List<WmsStorageVo> toStorageVos(List<WmsStorage> storages) {
        Map<Long, WmsWarehouseZone> zones = zonesOf(
            storages.stream().map(WmsStorage::getZoneId).toList()
        );
        Map<Long, WmsWarehouse> warehouses = warehousesOf(
            storages.stream().map(WmsStorage::getWarehouseId).toList()
        );
        List<WmsStorageVo> rows = new ArrayList<>(storages.size());
        for (WmsStorage storage : storages) {
            WmsStorageVo vo = BeanUtil.copyProperties(storage, WmsStorageVo.class);
            WmsWarehouseZone zone = zones.get(storage.getZoneId());
            WmsWarehouse warehouse = warehouses.get(storage.getWarehouseId());
            if (zone != null && Objects.equals(zone.getWarehouseId(), storage.getWarehouseId())) {
                vo.setZoneCode(zone.getZoneCode());
                vo.setZoneName(zone.getZoneName());
            } else {
                vo.setZoneId(null);
            }
            if (warehouse != null) {
                vo.setCompanyId(warehouse.getCompanyId());
                vo.setWarehouseCode(warehouse.getWarehouseCode());
                vo.setWarehouseName(warehouse.getWarehouseName());
            } else {
                vo.setWarehouseId(null);
            }
            rows.add(vo);
        }
        return rows;
    }

    private WmsWarehouseVo toWarehouseVo(WmsWarehouse warehouse) {
        return BeanUtil.copyProperties(warehouse, WmsWarehouseVo.class);
    }

    private WmsWarehouseZoneVo toZoneVo(WmsWarehouseZone zone, WmsWarehouse warehouse) {
        WmsWarehouseZoneVo vo = BeanUtil.copyProperties(zone, WmsWarehouseZoneVo.class);
        vo.setLegacyWarehouseCode(zone.getWarehouseCode());
        vo.setWarehouseCode(null);
        if (warehouse != null) {
            vo.setCompanyId(warehouse.getCompanyId());
            vo.setWarehouseCode(warehouse.getWarehouseCode());
            vo.setWarehouseName(warehouse.getWarehouseName());
        }
        return vo;
    }
}
