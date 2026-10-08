/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准 CRUD 查询、排序、导入导出字段白名单校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.query;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.bo.IwsBulkConditionBo;
import com.innolux.iws.wms.domain.bo.IwsExportRequestBo;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsSortConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 标准 CRUD 字段白名单校验器。
 *
 * <p>前端只能传业务字段名，不能传数据库列名。本类负责验证字段是否属于当前表，
 * 且是否允许参与搜索、批量搜索、排序和导出，避免越权查询和 SQL 注入。</p>
 *
 * @author YONGJIE.PENG
 */
@Component
public class IwsCrudQueryValidator {

    private static final int MAX_PAGE_SIZE = 1000;

    private static final int MAX_BULK_VALUES = 5000;

    /**
     * 校验分页查询请求。
     *
     * @param meta    表元数据
     * @param queryBo 查询参数
     */
    public void validateQuery(IwsCrudTableMetaVo meta, IwsTableQueryBo queryBo) {
        Map<String, IwsCrudColumnMetaDto> columnMap = columnMap(meta);
        validatePage(meta, queryBo);
        normalizeQuery(queryBo);
        for (IwsQueryConditionBo condition : queryBo.getConditions()) {
            IwsCrudColumnMetaDto column = requireColumn(columnMap, condition.getField());
            if (!Boolean.TRUE.equals(column.getSearchable())) {
                throw new ServiceException("字段不允许搜索: " + condition.getField());
            }
            if (column.getQueryMode() != null && condition.getQueryMode() != null
                && !column.getQueryMode().equals(condition.getQueryMode())) {
                throw new ServiceException("字段查询模式不匹配: " + condition.getField());
            }
        }
        for (IwsBulkConditionBo condition : queryBo.getBulkConditions()) {
            IwsCrudColumnMetaDto column = requireColumn(columnMap, condition.getField());
            if (!Boolean.TRUE.equals(column.getBulkSearch())) {
                throw new ServiceException("字段不允许批量搜索: " + condition.getField());
            }
            if (condition.getValues() != null && condition.getValues().size() > MAX_BULK_VALUES) {
                throw new ServiceException("批量搜索数量超过上限: " + condition.getField());
            }
        }
        for (IwsSortConditionBo sort : queryBo.getSorts()) {
            IwsCrudColumnMetaDto column = requireColumn(columnMap, sort.getField());
            if (!Boolean.TRUE.equals(column.getSortable())) {
                throw new ServiceException("字段不允许排序: " + sort.getField());
            }
        }
    }

    /**
     * 校验导出字段。
     *
     * @param meta      表元数据
     * @param exportBo  导出请求
     */
    public void validateExport(IwsCrudTableMetaVo meta, IwsExportRequestBo exportBo) {
        validateQuery(meta, exportBo);
        Map<String, IwsCrudColumnMetaDto> columnMap = columnMap(meta);
        for (String field : exportBo.getExportFields() == null ? Collections.<String>emptyList() : exportBo.getExportFields()) {
            IwsCrudColumnMetaDto column = requireColumn(columnMap, field);
            if (!Boolean.TRUE.equals(column.getExportable())) {
                throw new ServiceException("字段不允许导出: " + field);
            }
        }
    }

    private void validatePage(IwsCrudTableMetaVo meta, IwsTableQueryBo queryBo) {
        int maxPageSize = meta.getMaxPageSize() == null ? MAX_PAGE_SIZE : Math.min(meta.getMaxPageSize(), MAX_PAGE_SIZE);
        if (queryBo.getPageNum() == null || queryBo.getPageNum() < 1) {
            queryBo.setPageNum(1);
        }
        if (queryBo.getPageSize() == null || queryBo.getPageSize() < 1) {
            queryBo.setPageSize(meta.getPageSize());
        }
        if (queryBo.getPageSize() > maxPageSize) {
            throw new ServiceException("分页大小超过上限: " + maxPageSize);
        }
    }

    private void normalizeQuery(IwsTableQueryBo queryBo) {
        if (queryBo.getConditions() == null) {
            queryBo.setConditions(new ArrayList<>());
        }
        if (queryBo.getBulkConditions() == null) {
            queryBo.setBulkConditions(new ArrayList<>());
        }
        if (queryBo.getSorts() == null) {
            queryBo.setSorts(new ArrayList<>());
        }
    }

    private Map<String, IwsCrudColumnMetaDto> columnMap(IwsCrudTableMetaVo meta) {
        return meta.getColumns().stream().collect(Collectors.toMap(IwsCrudColumnMetaDto::getField, Function.identity()));
    }

    private IwsCrudColumnMetaDto requireColumn(Map<String, IwsCrudColumnMetaDto> columnMap, String field) {
        IwsCrudColumnMetaDto column = columnMap.get(field);
        if (column == null) {
            throw new ServiceException("字段不属于当前表: " + field);
        }
        return column;
    }
}
