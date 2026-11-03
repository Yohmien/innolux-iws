/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 空间只读查询的表码、权限、字段白名单和错误码；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service;

import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.dto.IwsCrudPermissionDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;

import java.util.ArrayList;
import java.util.List;

/**
 * 空间只读查询合同。
 *
 * <p>本类只服务空间主数据读取，不注册通用 CRUD 元数据，也不声明库存可见性。</p>
 *
 * @author YONGJIE.PENG
 */
public final class WmsSpaceContract {

    public static final String TABLE_WAREHOUSE = "wms_warehouse";

    public static final String TABLE_ZONE = "wms_warehouse_zone";

    public static final String TABLE_STORAGE = "wms_storage";

    public static final String PERMISSION_WAREHOUSE_QUERY = "inventory:warehouse:query";

    public static final String PERMISSION_ZONE_QUERY = "inventory:zone:query";

    public static final String PERMISSION_STORAGE_QUERY = "inventory:storage:query";

    public static final String ADDRESS_AISLE = "AISLE";

    public static final String ADDRESS_ROW = "ROW";

    public static final String ADDRESS_LEVEL = "LEVEL";

    public static final String ADDRESS_COLUMN = "COLUMN";

    public static final String ADDRESS_DEPTH = "DEPTH";

    public static final List<String> ADDRESS_FIELDS = List.of(
        ADDRESS_AISLE, ADDRESS_ROW, ADDRESS_LEVEL, ADDRESS_COLUMN, ADDRESS_DEPTH
    );

    public static final int MAX_ADDRESS_VALUE_LENGTH = 64;

    public static final String ERROR_SCHEMA_STATE_UNSUPPORTED = "SCHEMA_STATE_UNSUPPORTED";

    public static final String ERROR_SPACE_WAREHOUSE_UNRESOLVED = "SPACE_WAREHOUSE_UNRESOLVED";

    public static final String ERROR_SPACE_PARENT_CHAIN_INCONSISTENT = "SPACE_PARENT_CHAIN_INCONSISTENT";

    public static final String ERROR_ADDRESS_PRESENT_MISMATCH = "ADDRESS_PRESENT_MISMATCH";

    public static final String ERROR_ADDRESS_VALUE_INVALID = "ADDRESS_VALUE_INVALID";

    public static final String ERROR_AMBIGUOUS = "AMBIGUOUS";

    public static final java.util.Map<String, String> ADDRESS_COLUMN_BY_FIELD = java.util.Map.of(
        ADDRESS_AISLE, "aisle_text",
        ADDRESS_ROW, "row_text",
        ADDRESS_LEVEL, "level_text",
        ADDRESS_COLUMN, "column_text",
        ADDRESS_DEPTH, "depth_text"
    );

    private static final IwsCrudTableMetaVo WAREHOUSE_META = buildWarehouseMeta();

    private static final IwsCrudTableMetaVo ZONE_META = buildZoneMeta();

    private static final IwsCrudTableMetaVo STORAGE_META = buildStorageMeta();

    private WmsSpaceContract() {
    }

    /**
     * 仓库分页字段白名单。
     *
     * @return 仓库查询元数据
     */
    public static IwsCrudTableMetaVo warehouseMeta() {
        return WAREHOUSE_META;
    }

    /**
     * 库区分页字段白名单。
     *
     * @return 库区查询元数据
     */
    public static IwsCrudTableMetaVo zoneMeta() {
        return ZONE_META;
    }

    /**
     * 储位分页字段白名单。
     *
     * @return 储位查询元数据
     */
    public static IwsCrudTableMetaVo storageMeta() {
        return STORAGE_META;
    }

    private static IwsCrudTableMetaVo buildWarehouseMeta() {
        IwsCrudTableMetaVo meta = baseMeta(
            TABLE_WAREHOUSE, "仓库", "warehouseId", "warehouse_code asc, warehouse_id asc",
            PERMISSION_WAREHOUSE_QUERY
        );
        List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
        columns.add(column(TABLE_WAREHOUSE, "warehouseId", "warehouse_id", "仓库ID", "number",
            140, true, true, true, "eq", 1));
        columns.add(column(TABLE_WAREHOUSE, "companyId", "company_id", "公司ID", "number",
            140, true, true, false, "eq", 2));
        columns.add(column(TABLE_WAREHOUSE, "warehouseCode", "warehouse_code", "仓库编码", "string",
            180, true, true, true, "likeRight", 3));
        columns.add(column(TABLE_WAREHOUSE, "warehouseName", "warehouse_name", "仓库名称", "string",
            240, true, true, false, "contains", 4));
        columns.add(column(TABLE_WAREHOUSE, "warehouseTypeCode", "warehouse_type_code", "仓库类型", "string",
            160, true, true, true, "eq", 5));
        columns.add(column(TABLE_WAREHOUSE, "typeConfigVersionId", "type_config_version_id",
            "类型配置版本ID", "number", 160, true, false, false, "eq", 6));
        columns.add(column(TABLE_WAREHOUSE, "lifecycleStatus", "lifecycle_status", "生命周期", "string",
            140, true, true, true, "eq", 7));
        columns.add(column(TABLE_WAREHOUSE, "enabled", "enabled",
            "启用状态", "number", 120, true, true, true, "eq", 8));
        columns.add(column(TABLE_WAREHOUSE, "revision", "revision", "修订", "number",
            100, true, true, false, "eq", 9));
        columns.add(column(TABLE_WAREHOUSE, "remark", "remark", "备注", "string",
            220, true, false, false, "contains", 10));
        columns.add(timeColumn(TABLE_WAREHOUSE, "updateTime", "update_time", 11));
        meta.setColumns(columns);
        return meta;
    }

    private static IwsCrudTableMetaVo buildZoneMeta() {
        IwsCrudTableMetaVo meta = baseMeta(
            TABLE_ZONE, "库区", "zoneId", "warehouse_id asc, zone_code asc, zone_id asc",
            PERMISSION_ZONE_QUERY
        );
        List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
        columns.add(column(TABLE_ZONE, "zoneId", "zone_id", "库区ID", "number",
            140, true, true, true, "eq", 1));
        columns.add(column(TABLE_ZONE, "warehouseId", "warehouse_id", "仓库ID", "number",
            140, true, true, true, "eq", 2));
        columns.add(column(TABLE_ZONE, "zoneCode", "zone_code", "库区编码", "string",
            180, true, true, true, "likeRight", 3));
        columns.add(column(TABLE_ZONE, "zoneName", "zone_name", "库区名称", "string",
            220, true, true, false, "contains", 4));
        columns.add(column(TABLE_ZONE, "zoneType", "zone_type", "库区类型", "number",
            120, true, true, true, "eq", 5));
        columns.add(column(TABLE_ZONE, "zoneKind", "zone_kind", "库区种类", "string",
            150, true, true, true, "eq", 6));
        columns.add(column(TABLE_ZONE, "lifecycleStatus", "lifecycle_status", "生命周期", "string",
            140, true, true, true, "eq", 7));
        columns.add(column(TABLE_ZONE, "plantCode", "plant_code", "厂别编码", "string",
            140, true, true, false, "eq", 8));
        columns.add(column(TABLE_ZONE, "legacyWarehouseCode", "warehouse_code",
            "旧仓别编码", "string", 150, true, false, false, "eq", 9));
        columns.add(column(TABLE_ZONE, "parentZoneId", "parent_zone_id", "上级库区ID", "number",
            150, true, false, false, "eq", 10));
        columns.add(column(TABLE_ZONE, "allowInbound", "allow_inbound", "允许入库", "number",
            120, true, false, false, "eq", 11));
        columns.add(column(TABLE_ZONE, "allowOutbound", "allow_outbound", "允许出库", "number",
            120, true, false, false, "eq", 12));
        columns.add(column(TABLE_ZONE, "status", "status", "状态", "number",
            100, true, true, true, "eq", 13));
        columns.add(column(TABLE_ZONE, "revision", "revision", "修订", "number",
            100, true, true, false, "eq", 14));
        columns.add(column(TABLE_ZONE, "sortNo", "sort_no", "显示顺序", "number",
            110, true, true, false, "eq", 15));
        columns.add(column(TABLE_ZONE, "remark", "remark", "备注", "string",
            220, true, false, false, "contains", 16));
        columns.add(timeColumn(TABLE_ZONE, "updateTime", "update_time", 17));
        meta.setColumns(columns);
        return meta;
    }

    private static IwsCrudTableMetaVo buildStorageMeta() {
        IwsCrudTableMetaVo meta = baseMeta(
            TABLE_STORAGE, "储位", "storageId", "warehouse_id asc, zone_id asc, storage_code asc, storage_id asc",
            PERMISSION_STORAGE_QUERY
        );
        List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
        columns.add(column(TABLE_STORAGE, "storageId", "storage_id", "储位ID", "number",
            140, true, true, true, "eq", 1));
        columns.add(column(TABLE_STORAGE, "warehouseId", "warehouse_id", "仓库ID", "number",
            140, true, true, true, "eq", 2));
        columns.add(column(TABLE_STORAGE, "zoneId", "zone_id", "库区ID", "number",
            140, true, true, true, "eq", 3));
        columns.add(column(TABLE_STORAGE, "storageCode", "storage_code", "储位编码", "string",
            180, true, true, true, "contains", 4));
        columns.add(column(TABLE_STORAGE, "storageType", "storage_type", "储位类型", "number",
            120, true, true, true, "eq", 5));
        columns.add(column(TABLE_STORAGE, "heightType", "height_type", "高度类型", "number",
            120, true, false, false, "eq", 6));
        columns.add(column(TABLE_STORAGE, "rowNo", "row_no", "排号", "number",
            100, true, false, false, "eq", 7));
        columns.add(column(TABLE_STORAGE, "colNo", "col_no", "列号", "number",
            100, true, false, false, "eq", 8));
        columns.add(column(TABLE_STORAGE, "levelNo", "level_no", "层号", "number",
            100, true, false, false, "eq", 9));
        columns.add(column(TABLE_STORAGE, "portCode", "port_code", "端口编码", "string",
            150, true, false, false, "eq", 10));
        columns.add(column(TABLE_STORAGE, "wcsPointCode", "wcs_point_code", "WCS点位", "string",
            150, true, false, false, "eq", 11));
        columns.add(column(TABLE_STORAGE, "status", "status", "储位状态", "number",
            110, true, true, true, "eq", 12));
        columns.add(column(TABLE_STORAGE, "lockStatus", "lock_status", "锁定状态", "number",
            110, true, true, true, "eq", 13));
        columns.add(column(TABLE_STORAGE, "disableStatus", "disable_status", "禁用状态", "number",
            110, true, true, true, "eq", 14));
        columns.add(column(TABLE_STORAGE, "lifecycleStatus", "lifecycle_status", "生命周期", "string",
            140, true, true, true, "eq", 15));
        columns.add(addressColumn(TABLE_STORAGE, "aisleText", "aisle_text", "巷道", 16));
        columns.add(addressColumn(TABLE_STORAGE, "rowText", "row_text", "排", 17));
        columns.add(addressColumn(TABLE_STORAGE, "levelText", "level_text", "层", 18));
        columns.add(addressColumn(TABLE_STORAGE, "columnText", "column_text", "列", 19));
        columns.add(addressColumn(TABLE_STORAGE, "depthText", "depth_text", "深", 20));
        columns.add(column(TABLE_STORAGE, "remark", "remark", "备注", "string",
            220, true, false, false, "contains", 21));
        columns.add(timeColumn(TABLE_STORAGE, "updateTime", "update_time", 22));
        meta.setColumns(columns);
        return meta;
    }

    private static IwsCrudTableMetaVo baseMeta(String tableCode,
                                                String pageTitle,
                                                String primaryKey,
                                                String defaultSort,
                                                String permission) {
        IwsCrudTableMetaVo meta = new IwsCrudTableMetaVo();
        meta.setTableCode(tableCode);
        meta.setTableName(tableCode);
        meta.setModuleCode("wms");
        meta.setBusinessGroup("inventory");
        meta.setPageTitle(pageTitle);
        meta.setPrimaryKey(primaryKey);
        meta.setDefaultSort(defaultSort);
        meta.setPageSize(50);
        meta.setMaxPageSize(1000);
        IwsCrudPermissionDto permissions = new IwsCrudPermissionDto();
        permissions.setList(permission);
        permissions.setQuery(permission);
        meta.setPermissions(permissions);
        return meta;
    }

    private static IwsCrudColumnMetaDto timeColumn(String tableCode, String field,
                                                   String dbColumn, int sortOrder) {
        IwsCrudColumnMetaDto column = column(tableCode, field, dbColumn, "更新时间", "datetime",
            180, true, true, false, "between", sortOrder);
        column.setSearchType("datetimeRange");
        return column;
    }

    private static IwsCrudColumnMetaDto addressColumn(String tableCode, String field,
                                                      String dbColumn, String label, int sortOrder) {
        return column(tableCode, field, dbColumn, label, "string",
            110, true, false, false, "eq", sortOrder);
    }

    private static IwsCrudColumnMetaDto column(String tableCode,
                                               String field,
                                               String dbColumn,
                                               String label,
                                               String dataType,
                                               Integer width,
                                               boolean searchable,
                                               boolean sortable,
                                               boolean bulkSearch,
                                               String queryMode,
                                               int sortOrder) {
        IwsCrudColumnMetaDto column = new IwsCrudColumnMetaDto();
        column.setTableCode(tableCode);
        column.setField(field);
        column.setDbColumn(dbColumn);
        column.setLabel(label);
        column.setDataType(dataType);
        column.setVisible(Boolean.TRUE);
        column.setDefaultVisible(Boolean.TRUE);
        column.setWidth(width);
        column.setSearchable(searchable);
        column.setDefaultSearchVisible(searchable);
        column.setSearchType(searchType(dataType));
        column.setQueryMode(queryMode);
        column.setBulkSearch(bulkSearch);
        column.setQuickSearch(bulkSearch);
        column.setSortable(sortable);
        column.setAddable(Boolean.FALSE);
        column.setEditable(Boolean.FALSE);
        column.setImportable(Boolean.FALSE);
        column.setExportable(Boolean.FALSE);
        column.setSortOrder(sortOrder);
        return column;
    }

    private static String searchType(String dataType) {
        if ("datetime".equals(dataType)) {
            return "datetimeRange";
        }
        if ("number".equals(dataType)) {
            return "input";
        }
        return "input";
    }
}
