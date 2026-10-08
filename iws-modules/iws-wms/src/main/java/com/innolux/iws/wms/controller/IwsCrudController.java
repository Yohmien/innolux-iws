/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准 CRUD 页面元数据、查询、列配置与导入导出入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.excel.core.ExcelResult;
import com.innolux.iws.common.excel.utils.ExcelBuilder;
import com.innolux.iws.wms.domain.bo.IwsColumnSettingBo;
import com.innolux.iws.wms.domain.bo.IwsExportRequestBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.vo.WmsSupplierImportVo;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.service.IWmsSupplierService;
import com.innolux.iws.wms.support.excel.IwsCrudExcelWriteHandler;
import com.innolux.iws.wms.support.meta.IwsCrudMetaRegistry;
import com.innolux.iws.wms.support.query.IwsCrudQueryValidator;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 标准 CRUD 页面入口。
 *
 * <p>供应商样板页已经接入真实分页、新增、修改、删除、导入、导出和列配置入口；
 * 其他标准表接入时复用本控制器的字段白名单、权限校验和导入导出边界。</p>
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/crud")
public class IwsCrudController {

    private final IwsCrudMetaRegistry metaRegistry;

    private final IwsCrudQueryValidator queryValidator;

    private final IWmsSupplierService supplierService;

    /**
     * 获取标准表页面元数据。
     *
     * @param tableCode 标准表编码
     * @return 页面元数据
     */
    @GetMapping("/{tableCode}/meta")
    public R<IwsCrudTableMetaVo> meta(@NotBlank @PathVariable String tableCode) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getList());
        return R.ok(meta);
    }

    /**
     * 标准分页查询入口。
     *
     * @param tableCode 标准表编码
     * @param queryBo   查询条件
     * @return 分页数据
     */
    @PostMapping("/{tableCode}/list")
    public R<PageResult<Map<String, Object>>> list(@NotBlank @PathVariable String tableCode,
                                                   @RequestBody IwsTableQueryBo queryBo) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getList());
        queryValidator.validateQuery(meta, queryBo);
        if ("wms_supplier".equals(tableCode)) {
            return R.ok(supplierService.selectPage(queryBo));
        }
        return R.ok(PageResult.build(Collections.emptyList(), 0));
    }

    /**
     * 新增标准表数据。
     *
     * @param tableCode 标准表编码
     * @param data      表单数据
     * @return 操作结果
     */
    @PostMapping("/{tableCode}")
    public R<Void> add(@NotBlank @PathVariable String tableCode,
                       @RequestBody Map<String, Object> data) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getAdd());
        if ("wms_supplier".equals(tableCode)) {
            return supplierService.insert(data) ? R.ok() : R.fail();
        }
        return R.fail("暂未接入新增服务: " + tableCode);
    }

    /**
     * 修改标准表数据。
     *
     * @param tableCode 标准表编码
     * @param data      表单数据
     * @return 操作结果
     */
    @PutMapping("/{tableCode}")
    public R<Void> edit(@NotBlank @PathVariable String tableCode,
                        @RequestBody Map<String, Object> data) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getEdit());
        if ("wms_supplier".equals(tableCode)) {
            return supplierService.update(data) ? R.ok() : R.fail();
        }
        return R.fail("暂未接入修改服务: " + tableCode);
    }

    /**
     * 删除标准表数据。
     *
     * @param tableCode 标准表编码
     * @param ids       主键集合
     * @return 操作结果
     */
    @DeleteMapping("/{tableCode}/{ids}")
    public R<Void> remove(@NotBlank @PathVariable String tableCode,
                          @PathVariable List<Long> ids) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkAnyPermission(meta.getPermissions().getBatchRemove(), meta.getPermissions().getRemove());
        if ("wms_supplier".equals(tableCode)) {
            return supplierService.deleteByIds(ids) ? R.ok() : R.fail();
        }
        return R.fail("暂未接入删除服务: " + tableCode);
    }

    /**
     * 保存用户列配置。
     *
     * @param tableCode 标准表编码
     * @param settingBo 用户列配置
     * @return 操作结果
     */
    @PostMapping("/{tableCode}/columns")
    public R<Void> saveColumns(@NotBlank @PathVariable String tableCode,
                               @RequestBody IwsColumnSettingBo settingBo) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getColumn());
        settingBo.setTableCode(tableCode);
        metaRegistry.applyColumnSettings(settingBo);
        return R.ok();
    }

    /**
     * 导出标准表数据。
     *
     * @param tableCode 标准表编码
     * @param exportBo  导出请求
     */
    @PostMapping("/{tableCode}/export")
    public void export(@NotBlank @PathVariable String tableCode,
                       @RequestBody IwsExportRequestBo exportBo,
                       HttpServletResponse response) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getExport());
        queryValidator.validateExport(meta, exportBo);
        List<IwsCrudColumnMetaDto> columns = resolveExportColumns(meta, exportBo.getExportFields());
        if (columns.isEmpty()) {
            throw new ServiceException("请至少选择一个可导出的字段");
        }
        if ("wms_supplier".equals(tableCode)) {
            supplierService.validateExportRows(exportBo);
        } else {
            throw new ServiceException("暂未接入导出服务: " + tableCode);
        }
        writeExcel(response, meta.getPageTitle() + "数据", columns, batchConsumer -> {
            supplierService.writeExportRows(exportBo, batchConsumer);
        });
    }

    /**
     * 导入标准表数据。
     *
     * @param tableCode     标准表编码
     * @param file          导入文件
     * @param updateSupport 是否更新已有数据
     * @return 操作结果
     */
    @PostMapping(value = "/{tableCode}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<String> importData(@NotBlank @PathVariable String tableCode,
                                @RequestPart("file") MultipartFile file,
                                @RequestParam(defaultValue = "false") boolean updateSupport) throws IOException {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkPermission(meta.getPermissions().getImportData());
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要导入的 Excel 文件");
        }
        if (!"wms_supplier".equals(tableCode)) {
            return R.fail("暂未接入导入服务: " + tableCode);
        }
        ExcelResult<WmsSupplierImportVo> result = ExcelBuilder.read(file.getInputStream(), WmsSupplierImportVo.class)
            .autoTrim(true)
            .ignoreEmptyRow(true)
            .doRead();
        return R.ok(supplierService.importData(result.getList(), updateSupport));
    }

    /**
     * 下载导入模板。
     *
     * @param tableCode 标准表编码
     */
    @PostMapping("/{tableCode}/template")
    public void template(@NotBlank @PathVariable String tableCode,
                         HttpServletResponse response) {
        IwsCrudTableMetaVo meta = requireMeta(tableCode);
        checkAnyPermission(meta.getPermissions().getTemplate(), meta.getPermissions().getImportData());
        List<IwsCrudColumnMetaDto> columns = meta.getColumns().stream()
            .filter(column -> Boolean.TRUE.equals(column.getImportable()))
            .sorted((left, right) -> Integer.compare(safeSortOrder(left), safeSortOrder(right)))
            .toList();
        writeExcel(response, meta.getPageTitle() + "导入模板", columns, Collections.emptyList());
    }

    private IwsCrudTableMetaVo requireMeta(String tableCode) {
        return metaRegistry.findByTableCode(tableCode)
            .orElseThrow(() -> new ServiceException("未维护表元数据: " + tableCode));
    }

    private void checkPermission(String permission) {
        if (permission != null && !permission.isBlank()) {
            StpUtil.checkPermission(permission);
        }
    }

    private void checkAnyPermission(String... permissions) {
        String firstPermission = null;
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            if (firstPermission == null) {
                firstPermission = permission;
            }
            if (StpUtil.hasPermission(permission)) {
                return;
            }
        }
        checkPermission(firstPermission);
    }

    private List<IwsCrudColumnMetaDto> resolveExportColumns(IwsCrudTableMetaVo meta, List<String> exportFields) {
        List<String> fields = exportFields == null ? Collections.emptyList() : exportFields;
        return meta.getColumns().stream()
            .filter(column -> Boolean.TRUE.equals(column.getExportable()))
            .filter(column -> fields.isEmpty() || fields.contains(column.getField()))
            .sorted((left, right) -> Integer.compare(safeSortOrder(left), safeSortOrder(right)))
            .toList();
    }

    private int safeSortOrder(IwsCrudColumnMetaDto column) {
        return column.getSortOrder() == null ? Integer.MAX_VALUE : column.getSortOrder();
    }

    private void writeExcel(HttpServletResponse response,
                            String fileName,
                            List<IwsCrudColumnMetaDto> columns,
                            List<Map<String, Object>> rows) {
        writeExcel(response, fileName, columns, batchConsumer -> {
            if (rows != null && !rows.isEmpty()) {
                batchConsumer.accept(rows);
            }
        });
    }

    private void writeExcel(HttpServletResponse response,
                            String fileName,
                            List<IwsCrudColumnMetaDto> columns,
                            ExcelBatchProvider batchProvider) {
        try {
            resetDownloadResponse(response, fileName);
            List<List<String>> head = buildExcelHead(columns);
            ExcelWriter excelWriter = FesodSheet.write(response.getOutputStream())
                .head(head)
                .autoCloseStream(false)
                .registerWriteHandler(new IwsCrudExcelWriteHandler(columns))
                .build();
            try {
                WriteSheet writeSheet = FesodSheet.writerSheet("sheet1").build();
                AtomicBoolean hasWritten = new AtomicBoolean(false);
                batchProvider.write(rows -> {
                    excelWriter.write(buildExcelRows(columns, rows), writeSheet);
                    hasWritten.set(true);
                });
                if (!hasWritten.get()) {
                    excelWriter.write(Collections.emptyList(), writeSheet);
                }
            } finally {
                excelWriter.finish();
            }
        } catch (IOException e) {
            throw new IllegalStateException("导出Excel失败: " + fileName, e);
        }
    }

    @FunctionalInterface
    private interface ExcelBatchProvider {

        void write(Consumer<List<Map<String, Object>>> batchConsumer);
    }

    private List<List<String>> buildExcelHead(List<IwsCrudColumnMetaDto> columns) {
        return columns.stream()
            .map(column -> List.of(column.getLabel()))
            .toList();
    }

    private List<List<Object>> buildExcelRows(List<IwsCrudColumnMetaDto> columns, List<Map<String, Object>> rows) {
        List<List<Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> values = new ArrayList<>();
            for (IwsCrudColumnMetaDto column : columns) {
                values.add(formatExcelValue(row.get(column.getField()), column));
            }
            data.add(values);
        }
        return data;
    }

    private Object formatExcelValue(Object value, IwsCrudColumnMetaDto column) {
        if (value == null || column.getOptions() == null || column.getOptions().isEmpty()) {
            return value;
        }
        return column.getOptions().stream()
            .filter(option -> String.valueOf(option.getValue()).equals(String.valueOf(value)))
            .map(option -> (Object) option.getLabel())
            .findFirst()
            .orElse(value);
    }

    private void resetDownloadResponse(HttpServletResponse response, String fileName) throws IOException {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encodedFileName + ".xlsx");
        response.flushBuffer();
    }
}
