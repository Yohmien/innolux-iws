/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.excel.core;

import org.apache.fesod.sheet.read.listener.ReadListener;

/**
 * Excel 导入监听
 *
 * @author YONGJIE.PENG
 */
public interface ExcelListener<T> extends ReadListener<T> {

    /**
     * 获取 Excel 导入结果。
     *
     * @return Excel 导入结果
     */
    ExcelResult<T> getExcelResult();

}
