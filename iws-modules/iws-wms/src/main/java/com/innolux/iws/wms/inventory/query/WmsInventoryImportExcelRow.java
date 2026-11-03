/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 导入预览
 * @Version      : V1.0
 * @Description  : 物料变更导入预览 Excel 行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

/**
 * 物料变更导入预览行。
 *
 * <p>数量保持文本，由服务端统一校验 precision/scale，避免 Excel 浮点静默舍入。</p>
 */
@Data
public class WmsInventoryImportExcelRow {

    @ExcelProperty("物料编码")
    private String materialCode;

    @ExcelProperty("库存单元编码")
    private String stockUnitCode;

    @ExcelProperty("数量")
    private String quantity;

    @ExcelProperty("单位")
    private String unit;

    @ExcelProperty("原因")
    private String reason;
}
