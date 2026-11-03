/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准 CRUD 查询条件校验与应用；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;

import java.util.Collection;
import java.util.Set;

/**
 * 标准 CRUD 查询条件处理。
 *
 * @author YONGJIE.PENG
 */
public final class IwsCrudConditionSupport {

    private static final String STRING_DATA_TYPE = "string";

    static final String EQ = "eq";

    static final String LIKE_RIGHT = "likeRight";

    static final String CONTAINS = "contains";

    static final String BETWEEN = "between";

    static final String IN = "in";

    private static final Set<String> EMPTY_EQ_OVERRIDABLE_MODES = Set.of(LIKE_RIGHT, CONTAINS, IN);

    private IwsCrudConditionSupport() {
    }

    /**
     * 校验查询条件的值和操作方式组合。
     *
     * @param column    字段元数据
     * @param condition 查询条件
     */
    public static void validate(IwsCrudColumnMetaDto column, IwsQueryConditionBo condition) {
        if (!Boolean.TRUE.equals(column.getSearchable())) {
            throw new ServiceException("字段不允许搜索: " + column.getField());
        }
        if (!hasPayload(condition)) {
            return;
        }
        validateEmptyStringType(column, condition);
        String mode = queryMode(condition);
        validateQueryMode(column, condition, mode);
        if (BETWEEN.equals(mode)) {
            if (condition.getValue() != null) {
                throw new ServiceException("范围查询必须使用开始值和结束值: " + condition.getField());
            }
            validateRangeBoundary(condition.getBeginValue(), condition.getField());
            validateRangeBoundary(condition.getEndValue(), condition.getField());
            return;
        }
        if (condition.getBeginValue() != null || condition.getEndValue() != null) {
            throw new ServiceException("非范围查询不能使用开始值或结束值: " + condition.getField());
        }

        Object value = condition.getValue();
        if (value == null) {
            return;
        }
        switch (mode) {
            case EQ -> {
            }
            case LIKE_RIGHT, CONTAINS -> validateLikeValue(value, mode, condition.getField());
            case IN -> {
                if (!(value instanceof Collection<?> values)) {
                    throw new ServiceException("in 查询值必须是集合: " + condition.getField());
                }
                if (values.isEmpty()) {
                    throw new ServiceException("in 查询至少需要一个值: " + condition.getField());
                }
            }
            default -> throw new ServiceException("不支持的查询方式: " + mode);
        }
    }

    /**
     * 将查询条件应用到字段白名单解析后的数据库列。
     *
     * @param wrapper   查询条件容器
     * @param column    字段元数据
     * @param condition 查询条件
     */
    public static void apply(QueryWrapper<?> wrapper, IwsCrudColumnMetaDto column, IwsQueryConditionBo condition) {
        validate(column, condition);
        if (!hasValue(condition)) {
            return;
        }
        String dbColumn = column.getDbColumn();
        String mode = queryMode(condition);
        switch (mode) {
            case EQ -> wrapper.eq(dbColumn, condition.getValue());
            case LIKE_RIGHT -> wrapper.likeRight(dbColumn, condition.getValue());
            case CONTAINS -> wrapper.like(dbColumn, condition.getValue());
            case BETWEEN -> {
                if (condition.getBeginValue() != null) {
                    wrapper.ge(dbColumn, condition.getBeginValue());
                }
                if (condition.getEndValue() != null) {
                    wrapper.le(dbColumn, condition.getEndValue());
                }
            }
            case IN -> wrapper.in(dbColumn, (Collection<?>) condition.getValue());
            default -> throw new ServiceException("不支持的查询方式: " + mode);
        }
    }

    private static String queryMode(IwsQueryConditionBo condition) {
        return condition.getQueryMode() == null ? EQ : condition.getQueryMode();
    }

    private static boolean isExplicitEmptyString(Object value) {
        return value instanceof String text && text.isEmpty();
    }

    private static boolean hasPayload(IwsQueryConditionBo condition) {
        return condition.getValue() != null
            || condition.getBeginValue() != null
            || condition.getEndValue() != null;
    }

    private static boolean hasValue(IwsQueryConditionBo condition) {
        if (BETWEEN.equals(queryMode(condition))) {
            return condition.getBeginValue() != null || condition.getEndValue() != null;
        }
        return condition.getValue() != null;
    }

    private static void validateQueryMode(IwsCrudColumnMetaDto column,
                                          IwsQueryConditionBo condition,
                                          String requestedMode) {
        String configuredMode = column.getQueryMode();
        if (condition.getQueryMode() == null
            || configuredMode == null
            || configuredMode.equals(requestedMode)) {
            return;
        }
        if (STRING_DATA_TYPE.equals(column.getDataType())
            && EQ.equals(requestedMode)
            && isExplicitEmptyString(condition.getValue())
            && EMPTY_EQ_OVERRIDABLE_MODES.contains(configuredMode)) {
            return;
        }
        throw new ServiceException("字段查询模式不匹配: " + column.getField());
    }

    private static void validateEmptyStringType(IwsCrudColumnMetaDto column, IwsQueryConditionBo condition) {
        if (STRING_DATA_TYPE.equals(column.getDataType())) {
            return;
        }
        if (containsEmptyString(condition.getValue())
            || containsEmptyString(condition.getBeginValue())
            || containsEmptyString(condition.getEndValue())) {
            throw new ServiceException("非字符串字段不能使用空字符串查询: " + column.getField());
        }
    }

    private static boolean containsEmptyString(Object value) {
        if (isExplicitEmptyString(value)) {
            return true;
        }
        return value instanceof Collection<?> values
            && values.stream().anyMatch(IwsCrudConditionSupport::isExplicitEmptyString);
    }

    private static void validateLikeValue(Object value, String mode, String field) {
        if (isExplicitEmptyString(value)) {
            throw new ServiceException("空字符串不能用于 " + mode + " 查询，请使用 eq: " + field);
        }
        if (value instanceof Collection<?>) {
            throw new ServiceException(mode + " 查询值必须是单值: " + field);
        }
    }

    private static void validateRangeBoundary(Object value, String field) {
        if (isExplicitEmptyString(value)) {
            throw new ServiceException("范围查询边界不能为空字符串: " + field);
        }
        if (value instanceof Collection<?>) {
            throw new ServiceException("范围查询边界只能是单值: " + field);
        }
    }
}
