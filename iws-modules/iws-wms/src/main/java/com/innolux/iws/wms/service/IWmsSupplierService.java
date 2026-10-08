/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : WMS 供应商标准表服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.wms.domain.bo.IwsExportRequestBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.vo.WmsSupplierImportVo;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * WMS 供应商标准表服务。
 *
 * @author YONGJIE.PENG
 */
public interface IWmsSupplierService {

    /**
     * 分页查询供应商。
     *
     * @param queryBo 查询条件
     * @return 分页数据
     */
    PageResult<Map<String, Object>> selectPage(IwsTableQueryBo queryBo);

    /**
     * 查询供应商导出数据。
     *
     * @param exportBo 导出条件
     * @return 导出数据
     */
    List<Map<String, Object>> selectExportRows(IwsExportRequestBo exportBo);

    /**
     * 校验供应商同步导出范围。
     *
     * @param exportBo 导出条件
     * @return 本次预计导出行数
     */
    long validateExportRows(IwsExportRequestBo exportBo);

    /**
     * 分批写出供应商导出数据。
     *
     * @param exportBo      导出条件
     * @param batchConsumer 批次消费器
     * @return 实际导出行数
     */
    long writeExportRows(IwsExportRequestBo exportBo, Consumer<List<Map<String, Object>>> batchConsumer);

    /**
     * 导入供应商。
     *
     * @param rows          导入数据
     * @param updateSupport 是否更新已存在供应商
     * @return 导入结果说明
     */
    String importData(List<WmsSupplierImportVo> rows, boolean updateSupport);

    /**
     * 新增供应商。
     *
     * @param data 表单数据
     * @return 是否成功
     */
    boolean insert(Map<String, Object> data);

    /**
     * 修改供应商。
     *
     * @param data 表单数据
     * @return 是否成功
     */
    boolean update(Map<String, Object> data);

    /**
     * 删除供应商。
     *
     * @param ids 主键集合
     * @return 是否成功
     */
    boolean deleteByIds(List<Long> ids);
}
