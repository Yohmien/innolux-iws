/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : WMS 供应商标准表服务实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.WmsSupplier;
import com.innolux.iws.wms.domain.bo.IwsBulkConditionBo;
import com.innolux.iws.wms.domain.bo.IwsExportRequestBo;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsSortConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.domain.vo.WmsSupplierImportVo;
import com.innolux.iws.wms.mapper.WmsSupplierMapper;
import com.innolux.iws.wms.service.IWmsSupplierService;
import com.innolux.iws.wms.support.meta.IwsCrudMetaRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * WMS 供应商标准表服务实现。
 *
 * <p>查询字段和排序字段只通过元数据映射到数据库列名，避免前端字段直通 SQL。</p>
 *
 * @author YONGJIE.PENG
 */
@RequiredArgsConstructor
@Service
public class WmsSupplierServiceImpl implements IWmsSupplierService {

    private static final String TABLE_CODE = "wms_supplier";

    /**
     * Excel 单 Sheet 最大数据行数。
     *
     * <p>xlsx 单个工作表最多 1,048,576 行，标准导出保留 1 行表头，
     * 因此单文件最多承载 1,048,575 行数据。低于该数量必须按当前查询条件全量导出。</p>
     */
    private static final int MAX_EXCEL_DATA_ROWS = 1_048_575;

    private static final int EXPORT_BATCH_SIZE = 10_000;

    private final WmsSupplierMapper supplierMapper;

    private final IwsCrudMetaRegistry metaRegistry;

    @Override
    public PageResult<Map<String, Object>> selectPage(IwsTableQueryBo queryBo) {
        QueryWrapper<WmsSupplier> wrapper = buildQueryWrapper(queryBo);
        Page<WmsSupplier> page = supplierMapper.selectPage(new Page<>(queryBo.getPageNum(), queryBo.getPageSize()), wrapper);
        List<Map<String, Object>> rows = page.getRecords().stream()
            .map(record -> BeanUtil.beanToMap(record, false, true))
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public List<Map<String, Object>> selectExportRows(IwsExportRequestBo exportBo) {
        validateExportRows(exportBo);
        QueryWrapper<WmsSupplier> wrapper = buildQueryWrapper(exportBo);
        if ("selected".equals(exportBo.getExportScope()) && exportBo.getSelectedIds() != null && !exportBo.getSelectedIds().isEmpty()) {
            wrapper.in("supplier_id", exportBo.getSelectedIds());
        }

        long pageNum = "query".equals(exportBo.getExportScope()) ? 1L : Math.max(ObjectUtil.defaultIfNull(exportBo.getPageNum(), 1), 1);
        long pageSize = resolveExportPageSize(exportBo);
        Page<WmsSupplier> page = supplierMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.getRecords().stream()
            .map(record -> BeanUtil.beanToMap(record, false, true))
            .toList();
    }

    @Override
    public long validateExportRows(IwsExportRequestBo exportBo) {
        if ("currentPage".equals(exportBo.getExportScope())) {
            return resolveExportPageSize(exportBo);
        }

        if ("selected".equals(exportBo.getExportScope())) {
            validateSelectedExport(exportBo);
            return exportBo.getSelectedIds().size();
        }

        long total = countExportRows(exportBo);
        return resolveSyncExportTotal(total);
    }

    @Override
    public long writeExportRows(IwsExportRequestBo exportBo, Consumer<List<Map<String, Object>>> batchConsumer) {
        long maxRows = validateExportRows(exportBo);
        if (maxRows <= 0) {
            return 0;
        }
        if ("currentPage".equals(exportBo.getExportScope())) {
            return writeCurrentPage(exportBo, maxRows, batchConsumer);
        }
        return writePagedExport(exportBo, maxRows, batchConsumer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importData(List<WmsSupplierImportVo> rows, boolean updateSupport) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("导入失败，未解析到有效供应商数据");
        }

        int insertNum = 0;
        int updateNum = 0;
        int failureNum = 0;
        StringBuilder failureMsg = new StringBuilder();
        Set<String> importedCodes = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            WmsSupplierImportVo row = rows.get(i);
            int excelRowNo = i + 2;
            try {
                String supplierCode = normalize(row.getSupplierCode());
                String supplierName = normalize(row.getSupplierName());
                if (StrUtil.isBlank(supplierCode)) {
                    throw new ServiceException("供应商编码不能为空");
                }
                if (StrUtil.isBlank(supplierName)) {
                    throw new ServiceException("供应商名称不能为空");
                }
                if (!importedCodes.add(supplierCode)) {
                    throw new ServiceException("供应商编码在本次导入文件中重复");
                }

                WmsSupplier existing = selectBySupplierCode(supplierCode);
                if (existing == null) {
                    WmsSupplier supplier = buildSupplier(row);
                    supplierMapper.insert(supplier);
                    insertNum++;
                } else if (updateSupport) {
                    WmsSupplier supplier = buildSupplier(row);
                    supplier.setSupplierId(existing.getSupplierId());
                    supplier.setVersion(existing.getVersion());
                    supplierMapper.updateById(supplier);
                    updateNum++;
                } else {
                    throw new ServiceException("供应商编码已存在，请勾选更新已有数据后重试");
                }
            } catch (Exception e) {
                failureNum++;
                failureMsg.append("\n").append(failureNum)
                    .append("、第 ").append(excelRowNo).append(" 行导入失败：")
                    .append(e.getMessage());
            }
        }

        if (failureNum > 0) {
            throw new ServiceException("供应商导入失败！新增 " + insertNum + " 条，更新 " + updateNum
                + " 条，失败 " + failureNum + " 条，错误如下：" + failureMsg);
        }

        return "供应商导入成功！新增 " + insertNum + " 条，更新 " + updateNum
            + " 条，共处理 " + rows.size() + " 条。";
    }

    @Override
    public boolean insert(Map<String, Object> data) {
        WmsSupplier supplier = BeanUtil.toBean(data, WmsSupplier.class);
        if (supplier.getStatus() == null) {
            supplier.setStatus(1);
        }
        if (supplier.getVersion() == null) {
            supplier.setVersion(0);
        }
        return supplierMapper.insert(supplier) > 0;
    }

    @Override
    public boolean update(Map<String, Object> data) {
        WmsSupplier supplier = BeanUtil.toBean(data, WmsSupplier.class);
        if (supplier.getSupplierId() == null) {
            throw new ServiceException("供应商ID不能为空");
        }
        return supplierMapper.updateById(supplier) > 0;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        return supplierMapper.deleteByIds(ids) > 0;
    }

    private QueryWrapper<WmsSupplier> buildQueryWrapper(IwsTableQueryBo queryBo) {
        QueryWrapper<WmsSupplier> wrapper = buildFilterWrapper(queryBo);
        wrapper.select("supplier_id", "supplier_code", "supplier_name", "contact_person", "contact_phone",
            "contact_email", "address", "status", "remark", "create_time", "update_time");
        applySort(wrapper, queryBo);
        return wrapper;
    }

    private QueryWrapper<WmsSupplier> buildFilterWrapper(IwsTableQueryBo queryBo) {
        Map<String, IwsCrudColumnMetaDto> columnMap = columnMap();
        QueryWrapper<WmsSupplier> wrapper = new QueryWrapper<>();
        for (IwsQueryConditionBo condition : queryBo.getConditions()) {
            IwsCrudColumnMetaDto column = columnMap.get(condition.getField());
            if (column == null || !hasConditionValue(condition)) {
                continue;
            }
            applyCondition(wrapper, column.getDbColumn(), condition);
        }

        for (IwsBulkConditionBo condition : queryBo.getBulkConditions()) {
            IwsCrudColumnMetaDto column = columnMap.get(condition.getField());
            if (column == null || condition.getValues() == null || condition.getValues().isEmpty()) {
                continue;
            }
            wrapper.in(column.getDbColumn(), condition.getValues());
        }
        return wrapper;
    }

    private WmsSupplier selectBySupplierCode(String supplierCode) {
        return supplierMapper.selectOne(new QueryWrapper<WmsSupplier>()
            .eq("supplier_code", supplierCode)
            .last("limit 1"));
    }

    private WmsSupplier buildSupplier(WmsSupplierImportVo row) {
        WmsSupplier supplier = new WmsSupplier();
        supplier.setSupplierCode(normalize(row.getSupplierCode()));
        supplier.setSupplierName(normalize(row.getSupplierName()));
        supplier.setContactPerson(normalize(row.getContactPerson()));
        supplier.setContactPhone(normalize(row.getContactPhone()));
        supplier.setContactEmail(normalize(row.getContactEmail()));
        supplier.setAddress(normalize(row.getAddress()));
        supplier.setStatus(resolveStatus(row.getStatus()));
        supplier.setRemark(normalize(row.getRemark()));
        supplier.setVersion(0);
        supplier.setDelFlag("0");
        return supplier;
    }

    private String normalize(String value) {
        String trimmedValue = StrUtil.trim(value);
        return StrUtil.isBlank(trimmedValue) ? null : trimmedValue;
    }

    private Integer resolveStatus(String rawStatus) {
        String status = normalize(rawStatus);
        if (StrUtil.isBlank(status)) {
            return 1;
        }
        if ("1".equals(status) || "启用".equals(status) || "正常".equals(status)
            || "Y".equalsIgnoreCase(status) || "YES".equalsIgnoreCase(status)
            || "ENABLE".equalsIgnoreCase(status) || "ENABLED".equalsIgnoreCase(status)) {
            return 1;
        }
        if ("0".equals(status) || "停用".equals(status) || "禁用".equals(status)
            || "N".equalsIgnoreCase(status) || "NO".equalsIgnoreCase(status)
            || "DISABLE".equalsIgnoreCase(status) || "DISABLED".equalsIgnoreCase(status)) {
            return 0;
        }
        throw new ServiceException("状态只允许填写 1/0、启用/停用");
    }

    private void applySort(QueryWrapper<WmsSupplier> wrapper, IwsTableQueryBo queryBo) {
        Map<String, IwsCrudColumnMetaDto> columnMap = columnMap();
        if (queryBo.getSorts() == null || queryBo.getSorts().isEmpty()) {
            wrapper.orderByDesc("update_time").orderByDesc("supplier_id");
            return;
        }
        for (IwsSortConditionBo sort : queryBo.getSorts()) {
            IwsCrudColumnMetaDto column = columnMap.get(sort.getField());
            if (column == null) {
                continue;
            }
            boolean asc = "asc".equalsIgnoreCase(sort.getOrder());
            wrapper.orderBy(true, asc, column.getDbColumn());
        }
    }

    private long resolveExportPageSize(IwsExportRequestBo exportBo) {
        if ("selected".equals(exportBo.getExportScope()) && exportBo.getSelectedIds() != null && !exportBo.getSelectedIds().isEmpty()) {
            return Math.min(exportBo.getSelectedIds().size(), MAX_EXCEL_DATA_ROWS);
        }
        if ("query".equals(exportBo.getExportScope())) {
            return MAX_EXCEL_DATA_ROWS;
        }
        Integer pageSize = ObjectUtil.defaultIfNull(exportBo.getPageSize(), 50);
        return Math.min(Math.max(pageSize, 1), MAX_EXCEL_DATA_ROWS);
    }

    private void validateSelectedExport(IwsExportRequestBo exportBo) {
        if (exportBo.getSelectedIds() == null || exportBo.getSelectedIds().isEmpty()) {
            throw new ServiceException("请选择需要导出的数据");
        }
        if (exportBo.getSelectedIds().size() > MAX_EXCEL_DATA_ROWS) {
            throw new ServiceException("选中数据 " + exportBo.getSelectedIds().size()
                + " 条，超过 Excel 单文件数据上限 " + MAX_EXCEL_DATA_ROWS + " 条，请使用异步拆分导出任务");
        }
    }

    private long resolveSyncExportTotal(long total) {
        if (total > MAX_EXCEL_DATA_ROWS) {
            throw new ServiceException("当前查询结果 " + total + " 条，超过 Excel 单文件数据上限 "
                + MAX_EXCEL_DATA_ROWS + " 条，请缩小查询条件或使用异步拆分导出任务");
        }
        return total;
    }

    private long countExportRows(IwsExportRequestBo exportBo) {
        Long total = supplierMapper.selectCount(buildExportCountWrapper(exportBo));
        return total == null ? 0 : total;
    }

    private long writeCurrentPage(IwsExportRequestBo exportBo,
                                  long maxRows,
                                  Consumer<List<Map<String, Object>>> batchConsumer) {
        QueryWrapper<WmsSupplier> wrapper = buildExportWrapper(exportBo);
        long pageNum = Math.max(ObjectUtil.defaultIfNull(exportBo.getPageNum(), 1), 1);
        Page<WmsSupplier> page = supplierMapper.selectPage(new Page<>(pageNum, maxRows, false), wrapper);
        List<Map<String, Object>> rows = toRows(page.getRecords());
        if (!rows.isEmpty()) {
            batchConsumer.accept(rows);
        }
        return rows.size();
    }

    private long writePagedExport(IwsExportRequestBo exportBo,
                                  long maxRows,
                                  Consumer<List<Map<String, Object>>> batchConsumer) {
        long written = 0;
        long pageNum = 1;
        while (written < maxRows) {
            long batchSize = Math.min(EXPORT_BATCH_SIZE, maxRows - written);
            QueryWrapper<WmsSupplier> wrapper = buildExportWrapper(exportBo);
            Page<WmsSupplier> page = supplierMapper.selectPage(new Page<>(pageNum, batchSize, false), wrapper);
            List<WmsSupplier> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            List<Map<String, Object>> rows = toRows(records);
            batchConsumer.accept(rows);
            written += rows.size();
            if (rows.size() < batchSize) {
                break;
            }
            pageNum++;
        }
        return written;
    }

    private QueryWrapper<WmsSupplier> buildExportWrapper(IwsExportRequestBo exportBo) {
        QueryWrapper<WmsSupplier> wrapper = buildQueryWrapper(exportBo);
        if ("selected".equals(exportBo.getExportScope()) && exportBo.getSelectedIds() != null && !exportBo.getSelectedIds().isEmpty()) {
            wrapper.in("supplier_id", exportBo.getSelectedIds());
        }
        return wrapper;
    }

    private QueryWrapper<WmsSupplier> buildExportCountWrapper(IwsExportRequestBo exportBo) {
        QueryWrapper<WmsSupplier> wrapper = buildFilterWrapper(exportBo);
        if ("selected".equals(exportBo.getExportScope()) && exportBo.getSelectedIds() != null && !exportBo.getSelectedIds().isEmpty()) {
            wrapper.in("supplier_id", exportBo.getSelectedIds());
        }
        return wrapper;
    }

    private List<Map<String, Object>> toRows(List<WmsSupplier> records) {
        return records.stream()
            .map(record -> BeanUtil.beanToMap(record, false, true))
            .toList();
    }

    private void applyCondition(QueryWrapper<WmsSupplier> wrapper, String column, IwsQueryConditionBo condition) {
        String mode = StrUtil.nullToDefault(condition.getQueryMode(), "eq");
        switch (mode) {
            case "likeRight" -> wrapper.likeRight(column, condition.getValue());
            case "contains" -> wrapper.like(column, condition.getValue());
            case "between" -> {
                if (ObjectUtil.isNotEmpty(condition.getBeginValue())) {
                    wrapper.ge(column, condition.getBeginValue());
                }
                if (ObjectUtil.isNotEmpty(condition.getEndValue())) {
                    wrapper.le(column, condition.getEndValue());
                }
            }
            case "in" -> {
                if (condition.getValue() instanceof Collection<?> values && !values.isEmpty()) {
                    wrapper.in(column, values);
                }
            }
            default -> wrapper.eq(column, condition.getValue());
        }
    }

    private boolean hasConditionValue(IwsQueryConditionBo condition) {
        if ("between".equals(condition.getQueryMode())) {
            return ObjectUtil.isNotEmpty(condition.getBeginValue()) || ObjectUtil.isNotEmpty(condition.getEndValue());
        }
        return ObjectUtil.isNotEmpty(condition.getValue());
    }

    private Map<String, IwsCrudColumnMetaDto> columnMap() {
        IwsCrudTableMetaVo meta = metaRegistry.findByTableCode(TABLE_CODE)
            .orElseThrow(() -> new IllegalStateException("未维护表元数据: " + TABLE_CODE));
        return meta.getColumns().stream().collect(Collectors.toMap(IwsCrudColumnMetaDto::getField, Function.identity()));
    }
}
