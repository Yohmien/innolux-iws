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

import cn.hutool.core.util.StrUtil;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认excel返回对象
 *
 * @author YONGJIE.PENG
 * @author YONGJIE.PENG
 */
public class DefaultExcelResult<T> implements ExcelResult<T> {

    /**
     * 数据对象list
     */
    @Setter
    private List<T> list;

    /**
     * 错误信息列表
     */
    @Setter
    private List<String> errorList;

    /**
     * 创建默认导入结果。
     */
    public DefaultExcelResult() {
        this.list = new ArrayList<>();
        this.errorList = new ArrayList<>();
    }

    /**
     * 创建导入结果。
     *
     * @param list      成功数据列表
     * @param errorList 错误信息列表
     */
    public DefaultExcelResult(List<T> list, List<String> errorList) {
        this.list = list;
        this.errorList = errorList;
    }

    /**
     * 复制导入结果。
     *
     * @param excelResult 原导入结果
     */
    public DefaultExcelResult(ExcelResult<T> excelResult) {
        this.list = excelResult.getList();
        this.errorList = excelResult.getErrorList();
    }

    /**
     * 获取成功数据列表。
     *
     * @return 成功数据列表
     */
    @Override
    public List<T> getList() {
        return list;
    }

    /**
     * 获取错误信息列表。
     *
     * @return 错误信息列表
     */
    @Override
    public List<String> getErrorList() {
        return errorList;
    }

    /**
     * 获取导入回执
     *
     * @return 导入回执
     */
    @Override
    public String getAnalysis() {
        int successCount = list.size();
        int errorCount = errorList.size();
        if (successCount == 0) {
            return "读取失败，未解析到数据";
        } else {
            if (errorCount == 0) {
                return StrUtil.format("恭喜您，全部读取成功！共{}条", successCount);
            } else {
                return StrUtil.format("共{}条，成功导入{}条，错误{}条", successCount + errorCount, successCount, errorCount);
            }
        }
    }
}
