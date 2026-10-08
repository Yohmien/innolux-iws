/*
 * @Date         : 2026-09-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准 CRUD Excel 写入样式处理器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.excel;

import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.poi.ss.usermodel.*;

import java.util.List;

/**
 * 标准 CRUD Excel 样式。
 *
 * <p>通过字段元数据控制列宽，并统一表头与内容边框，保证模板、导出文件在 Excel/WPS
 * 中打开时具备一致的可读性。</p>
 *
 * @author YONGJIE.PENG
 */
public class IwsCrudExcelWriteHandler implements CellWriteHandler {

    private static final int MIN_EXCEL_WIDTH = 18;

    private static final int MAX_EXCEL_WIDTH = 48;

    private final List<IwsCrudColumnMetaDto> columns;

    private CellStyle headStyle;

    private CellStyle contentStyle;

    public IwsCrudExcelWriteHandler(List<IwsCrudColumnMetaDto> columns) {
        this.columns = columns;
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        Cell cell = context.getCell();
        if (cell == null) {
            return;
        }
        if (context.getHead()) {
            applyHeadCell(context, cell);
            return;
        }
        applyContentCell(cell);
    }

    private void applyHeadCell(CellWriteHandlerContext context, Cell cell) {
        Workbook workbook = context.getWriteSheetHolder().getSheet().getWorkbook();
        Sheet sheet = context.getWriteSheetHolder().getSheet();
        Row row = cell.getRow();
        if (row != null) {
            row.setHeightInPoints(24F);
        }
        int columnIndex = cell.getColumnIndex();
        if (columnIndex >= 0 && columnIndex < columns.size()) {
            sheet.setColumnWidth(columnIndex, resolveColumnWidth(columns.get(columnIndex)));
        }
        cell.setCellStyle(getHeadStyle(workbook));
    }

    private void applyContentCell(Cell cell) {
        Row row = cell.getRow();
        if (row != null) {
            row.setHeightInPoints(22F);
        }
        cell.setCellStyle(getContentStyle(cell.getSheet().getWorkbook()));
    }

    private CellStyle getHeadStyle(Workbook workbook) {
        if (headStyle != null) {
            return headStyle;
        }
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setFontName("Microsoft YaHei");
        font.setColor(IndexedColors.WHITE.getIndex());

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        applyBorder(style);
        headStyle = style;
        return headStyle;
    }

    private CellStyle getContentStyle(Workbook workbook) {
        if (contentStyle != null) {
            return contentStyle;
        }
        Font font = workbook.createFont();
        font.setFontName("Microsoft YaHei");
        font.setFontHeightInPoints((short) 10);

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        applyBorder(style);
        contentStyle = style;
        return contentStyle;
    }

    private void applyBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
    }

    private int resolveColumnWidth(IwsCrudColumnMetaDto column) {
        int pixelWidth = column.getWidth() != null ? column.getWidth() : 150;
        if (column.getMinWidth() != null) {
            pixelWidth = Math.max(pixelWidth, column.getMinWidth());
        }
        int excelWidth = Math.round(pixelWidth / 7.0F);
        excelWidth = Math.max(MIN_EXCEL_WIDTH, Math.min(MAX_EXCEL_WIDTH, excelWidth));
        return excelWidth * 256;
    }
}
