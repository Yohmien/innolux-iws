/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准表字段元数据注册器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.meta;

import com.innolux.iws.wms.domain.bo.IwsColumnSettingBo;
import com.innolux.iws.wms.domain.dto.IwsCrudCapabilityDto;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.dto.IwsCrudOptionDto;
import com.innolux.iws.wms.domain.dto.IwsCrudPermissionDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.support.company.WmsCompanyContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标准表字段元数据注册器。
 *
 * <p>第一阶段先用代码注册供应商样板元数据，后续落库到 iws_table_meta /
 * iws_table_column_meta 后，本类只保留缓存和查询职责。</p>
 *
 * @author YONGJIE.PENG
 */
@Component
public class IwsCrudMetaRegistry {

    private final Map<String, IwsCrudTableMetaVo> metaMap = new ConcurrentHashMap<>();

    public IwsCrudMetaRegistry() {
        register(buildSupplierMeta());
        register(buildCompanyMeta());
    }

    /**
     * 查询表元数据。
     *
     * @param tableCode 标准表编码
     * @return 表元数据
     */
    public Optional<IwsCrudTableMetaVo> findByTableCode(String tableCode) {
        return Optional.ofNullable(metaMap.get(tableCode));
    }

    /**
     * 注册表元数据。
     *
     * @param meta 表元数据
     */
    public void register(IwsCrudTableMetaVo meta) {
        metaMap.put(meta.getTableCode(), meta);
    }

    /**
     * 应用前端列配置。
     *
     * <p>当前阶段先更新运行时元数据缓存，使页面搜索、排序、导入导出等白名单与
     * 前端列设置保持一致。后续接入 iws_table_column_meta 后，此处改为落库并刷新缓存。</p>
     *
     * @param settingBo 列配置
     */
    public void applyColumnSettings(IwsColumnSettingBo settingBo) {
        IwsCrudTableMetaVo meta = metaMap.get(settingBo.getTableCode());
        if (meta == null || settingBo.getColumns() == null) {
            return;
        }
        Map<String, IwsCrudColumnMetaDto> columnMap = new ConcurrentHashMap<>();
        for (IwsCrudColumnMetaDto column : meta.getColumns()) {
            columnMap.put(column.getField(), column);
        }
        for (IwsColumnSettingBo.ColumnSettingItem item : settingBo.getColumns()) {
            IwsCrudColumnMetaDto column = columnMap.get(item.getField());
            if (column == null) {
                continue;
            }
            applyColumnSetting(column, item);
        }
    }

    private void applyColumnSetting(IwsCrudColumnMetaDto column, IwsColumnSettingBo.ColumnSettingItem item) {
        if (item.getVisible() != null) {
            column.setVisible(item.getVisible());
        }
        if (item.getWidth() != null) {
            column.setWidth(item.getWidth());
        }
        if (item.getFixed() != null) {
            column.setFixed(item.getFixed());
        }
        if (item.getSortOrder() != null) {
            column.setSortOrder(item.getSortOrder());
        }
        if (item.getSearchable() != null) {
            column.setSearchable(item.getSearchable());
        }
        if (item.getDefaultSearchVisible() != null) {
            column.setDefaultSearchVisible(item.getDefaultSearchVisible());
        }
        if (item.getSearchType() != null) {
            column.setSearchType(item.getSearchType());
        }
        if (item.getQueryMode() != null) {
            column.setQueryMode(item.getQueryMode());
        }
        if (item.getBulkSearch() != null) {
            column.setBulkSearch(item.getBulkSearch());
        }
        if (item.getQuickSearch() != null) {
            column.setQuickSearch(item.getQuickSearch());
        }
        if (item.getSortable() != null) {
            column.setSortable(item.getSortable());
        }
        if (item.getAddable() != null) {
            column.setAddable(item.getAddable());
        }
        if (item.getEditable() != null) {
            column.setEditable(item.getEditable());
        }
        if (item.getImportable() != null) {
            column.setImportable(item.getImportable());
        }
        if (item.getExportable() != null) {
            column.setExportable(item.getExportable());
        }
        if (!Boolean.TRUE.equals(column.getSearchable())) {
            column.setDefaultSearchVisible(Boolean.FALSE);
            column.setBulkSearch(Boolean.FALSE);
            column.setQuickSearch(Boolean.FALSE);
        }
        if ("dateRange".equals(column.getSearchType())
            || "datetimeRange".equals(column.getSearchType())
            || "numberRange".equals(column.getSearchType())) {
            column.setQueryMode("between");
        }
    }

    private IwsCrudTableMetaVo buildSupplierMeta() {
        IwsCrudTableMetaVo meta = new IwsCrudTableMetaVo();
        meta.setTableCode("wms_supplier");
        meta.setTableName("wms_supplier");
        meta.setModuleCode("wms");
        meta.setBusinessGroup("basic");
        meta.setPageTitle("供应商");
        meta.setPrimaryKey("supplierId");
        meta.setDefaultSort("update_time desc, supplier_id desc");
        meta.setPageSize(50);
        meta.setMaxPageSize(1000);

        IwsCrudPermissionDto permissions = new IwsCrudPermissionDto();
        permissions.setList("wms:supplier:list");
        permissions.setQuery("wms:supplier:query");
        permissions.setAdd("wms:supplier:add");
        permissions.setEdit("wms:supplier:edit");
        permissions.setRemove("wms:supplier:remove");
        permissions.setBatchRemove("wms:supplier:batchRemove");
        permissions.setImportData("wms:supplier:import");
        permissions.setExport("wms:supplier:export");
        permissions.setTemplate("wms:supplier:template");
        permissions.setColumn("wms:supplier:column");
        permissions.setBulkQuery("wms:supplier:bulkQuery");
        meta.setPermissions(permissions);

        List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
        columns.add(column("wms_supplier", "supplierId", "supplier_id", "供应商ID", "number", 130, false, false, false, false, false, false, 1));
        columns.add(column("wms_supplier", "supplierCode", "supplier_code", "供应商编码", "string", 160, true, true, true, true, true, true, 2));
        columns.add(column("wms_supplier", "supplierName", "supplier_name", "供应商名称", "string", 220, true, true, true, false, true, true, 3));
        columns.add(column("wms_supplier", "contactPerson", "contact_person", "联系人", "string", 140, true, false, false, false, true, true, 4));
        columns.add(column("wms_supplier", "contactPhone", "contact_phone", "联系电话", "string", 150, true, false, false, false, true, true, 5));
        columns.add(column("wms_supplier", "contactEmail", "contact_email", "联系邮箱", "string", 180, false, false, false, false, true, true, 6));
        columns.add(column("wms_supplier", "address", "address", "地址", "string", 220, false, false, false, false, true, true, 7));
        columns.add(statusColumn(8));
        columns.add(column("wms_supplier", "remark", "remark", "备注", "string", 200, true, false, false, false, true, true, 9));
        columns.add(column("wms_supplier", "updateTime", "update_time", "更新时间", "datetime", 180, true, true, false, false, false, true, 10));
        meta.setColumns(columns);
        return meta;
    }

    private IwsCrudTableMetaVo buildCompanyMeta() {
        IwsCrudTableMetaVo meta = new IwsCrudTableMetaVo();
        meta.setTableCode(WmsCompanyContract.TABLE_CODE);
        meta.setTableName(WmsCompanyContract.TABLE_CODE);
        meta.setModuleCode("wms");
        meta.setBusinessGroup("inventory");
        meta.setPageTitle("公司");
        meta.setPrimaryKey("companyId");
        meta.setDefaultSort("update_time desc, company_id desc");
        meta.setPageSize(50);
        meta.setMaxPageSize(1000);

        IwsCrudPermissionDto permissions = new IwsCrudPermissionDto();
        permissions.setList(WmsCompanyContract.PERMISSION_QUERY);
        permissions.setQuery(WmsCompanyContract.PERMISSION_QUERY);
        permissions.setAdd(WmsCompanyContract.PERMISSION_CREATE);
        permissions.setEdit(WmsCompanyContract.PERMISSION_UPDATE);
        meta.setPermissions(permissions);

        IwsCrudCapabilityDto capabilities = new IwsCrudCapabilityDto();
        capabilities.setQuery(Boolean.TRUE);
        capabilities.setAdd(Boolean.TRUE);
        capabilities.setEdit(Boolean.TRUE);
        capabilities.setRemove(Boolean.FALSE);
        capabilities.setBatchRemove(Boolean.FALSE);
        capabilities.setImportData(Boolean.FALSE);
        capabilities.setExport(Boolean.FALSE);
        capabilities.setTemplate(Boolean.FALSE);
        capabilities.setColumn(Boolean.FALSE);
        capabilities.setBulkQuery(Boolean.TRUE);
        meta.setCapabilities(capabilities);

        List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
        IwsCrudColumnMetaDto companyId = companyColumn(
            "companyId", "company_id", "公司ID", "number", 140, false, false, false, false, false, false, 1
        );
        IwsCrudColumnMetaDto companyCode = companyColumn(
            "companyCode", "company_code", "公司编码", "string", 180, true, true, true, true, true, false, 2
        );
        companyCode.setAddable(Boolean.TRUE);
        companyCode.setEditable(Boolean.FALSE);
        IwsCrudColumnMetaDto companyName = companyColumn(
            "companyName", "company_name", "公司名称", "string", 240, true, true, true, true, true, false, 3
        );
        companyName.setAddable(Boolean.TRUE);
        companyName.setEditable(Boolean.TRUE);
        IwsCrudColumnMetaDto remark = companyColumn(
            "remark", "remark", "备注", "string", 240, true, false, false, false, true, false, 4
        );
        remark.setAddable(Boolean.TRUE);
        remark.setEditable(Boolean.TRUE);
        IwsCrudColumnMetaDto enabled = companyColumn(
            "enabled", "enabled", "启用状态", "boolean", 120, true, true, true, false, false, false, 5
        );
        enabled.setSearchType("select");
        enabled.setOptions(List.of(
            IwsCrudOptionDto.of("已启用", true),
            IwsCrudOptionDto.of("已停用", false)
        ));
        IwsCrudColumnMetaDto lifecycleStatus = companyColumn(
            "lifecycleStatus", "lifecycle_status", "生命周期", "string", 130, true, true, true, false, false, false, 6
        );
        lifecycleStatus.setSearchType("select");
        lifecycleStatus.setOptions(List.of(
            IwsCrudOptionDto.of("ACTIVE", WmsCompanyContract.LIFECYCLE_ACTIVE)
        ));
        IwsCrudColumnMetaDto revision = companyColumn(
            "revision", "revision", "修订", "number", 100, true, true, true, false, false, false, 7
        );
        IwsCrudColumnMetaDto updateTime = companyColumn(
            "updateTime", "update_time", "更新时间", "datetime", 180, true, true, true, false, false, false, 8
        );
        columns.add(companyId);
        columns.add(companyCode);
        columns.add(companyName);
        columns.add(remark);
        columns.add(enabled);
        columns.add(lifecycleStatus);
        columns.add(revision);
        columns.add(updateTime);
        meta.setColumns(columns);
        return meta;
    }

    private IwsCrudColumnMetaDto companyColumn(
        String field,
        String dbColumn,
        String label,
        String dataType,
        Integer width,
        boolean visible,
        boolean searchable,
        boolean sortable,
        boolean bulkSearch,
        boolean importable,
        boolean exportable,
        int sortOrder
    ) {
        IwsCrudColumnMetaDto column = column(
            WmsCompanyContract.TABLE_CODE,
            field,
            dbColumn,
            label,
            dataType,
            width,
            visible,
            searchable,
            sortable,
            bulkSearch,
            importable,
            exportable,
            sortOrder
        );
        column.setAddable(Boolean.FALSE);
        column.setEditable(Boolean.FALSE);
        column.setImportable(Boolean.FALSE);
        column.setExportable(Boolean.FALSE);
        return column;
    }

    private IwsCrudColumnMetaDto column(
        String tableCode,
        String field,
        String dbColumn,
        String label,
        String dataType,
        Integer width,
        boolean visible,
        boolean searchable,
        boolean sortable,
        boolean bulkSearch,
        boolean importable,
        boolean exportable,
        int sortOrder
    ) {
        IwsCrudColumnMetaDto column = new IwsCrudColumnMetaDto();
        column.setTableCode(tableCode);
        column.setField(field);
        column.setDbColumn(dbColumn);
        column.setLabel(label);
        column.setDataType(dataType);
        column.setVisible(visible);
        column.setDefaultVisible(visible);
        column.setWidth(width);
        column.setSearchable(searchable);
        column.setDefaultSearchVisible(searchable);
        column.setSearchType(defaultSearchType(dataType));
        column.setQueryMode(defaultQueryMode(dataType));
        column.setBulkSearch(bulkSearch);
        column.setQuickSearch(bulkSearch);
        column.setSortable(sortable);
        column.setAddable(!"supplierId".equals(field) && !"updateTime".equals(field));
        column.setEditable(!"supplierId".equals(field) && !"updateTime".equals(field));
        column.setImportable(importable);
        column.setExportable(exportable);
        column.setSortOrder(sortOrder);
        return column;
    }

    private IwsCrudColumnMetaDto statusColumn(int sortOrder) {
        IwsCrudColumnMetaDto column = column("wms_supplier", "status", "status", "状态", "dict", 100, true, true, true, false, true, true, sortOrder);
        column.setSearchType("select");
        column.setDictType("wms_common_status");
        column.setOptions(List.of(IwsCrudOptionDto.of("启用", 1), IwsCrudOptionDto.of("停用", 0)));
        return column;
    }

    private String defaultSearchType(String dataType) {
        if ("date".equals(dataType)) {
            return "dateRange";
        }
        if ("datetime".equals(dataType)) {
            return "datetimeRange";
        }
        if ("number".equals(dataType) || "decimal".equals(dataType)) {
            return "numberRange";
        }
        return "input";
    }

    private String defaultQueryMode(String dataType) {
        if ("date".equals(dataType) || "datetime".equals(dataType)
            || "number".equals(dataType) || "decimal".equals(dataType)) {
            return "between";
        }
        return "string".equals(dataType) ? "likeRight" : "eq";
    }
}
