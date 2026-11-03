/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页查询合同测试
 * @Version      : V1.0
 * @Description  : 验证显式空字符串、null 和范围查询模式的合同；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.query;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.support.meta.IwsCrudMetaRegistry;
import com.innolux.iws.wms.support.query.IwsCrudConditionSupport;
import com.innolux.iws.wms.support.query.IwsCrudQueryValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标准 CRUD 查询条件合同测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
class IwsCrudQueryContractTest {

    private final IwsCrudMetaRegistry metaRegistry = new IwsCrudMetaRegistry();

    private final IwsCrudQueryValidator validator = new IwsCrudQueryValidator();

    @Test
    void explicitEmptyEqUsesExactMatchForSupplierAndCompanyStringWhitelists() {
        assertExplicitEmptyEq("wms_supplier", "supplierCode", "supplier_code");
        assertExplicitEmptyEq("wms_company", "companyName", "company_name");
    }

    @Test
    void explicitEmptyLikeModesAreRejectedInsteadOfMatchingEveryRow() {
        for (String mode : List.of("likeRight", "contains")) {
            IwsCrudColumnMetaDto column = stringColumn("like_" + mode, mode, true);
            IwsQueryConditionBo condition = condition(column.getField(), mode, "");
            assertThatThrownBy(() -> IwsCrudConditionSupport.validate(column, condition))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("空字符串不能用于")
                .hasMessageContaining("请使用 eq");
        }
    }

    @Test
    void explicitEmptyRangeBoundaryIsRejected() {
        IwsQueryConditionBo condition = condition("updateTime", "between", null);
        condition.setBeginValue("");

        assertThatThrownBy(() -> validator.validateQuery(supplierMeta(), query(condition)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("非字符串字段不能使用空字符串查询");
    }

    @Test
    void emptyEqIsNotAcceptedForRangeWhitelist() {
        IwsQueryConditionBo condition = condition("updateTime", "eq", "");

        assertThatThrownBy(() -> validator.validateQuery(supplierMeta(), query(condition)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("非字符串字段不能使用空字符串查询");
    }

    @Test
    void eqOverrideIsLimitedToExplicitEmptyStrings() {
        IwsQueryConditionBo condition = condition("supplierCode", "eq", "SUP-001");

        assertThatThrownBy(() -> validator.validateQuery(supplierMeta(), query(condition)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("字段查询模式不匹配");
    }

    @Test
    void explicitEmptyEqIsAllowedForEverySearchableStringMode() {
        for (String configuredMode : List.of("eq", "likeRight", "contains", "in")) {
            IwsCrudColumnMetaDto column = stringColumn("string_" + configuredMode, configuredMode, true);
            IwsQueryConditionBo condition = condition(column.getField(), "eq", "");

            assertThatCode(() -> IwsCrudConditionSupport.validate(column, condition))
                .doesNotThrowAnyException();

            QueryWrapper<Object> wrapper = new QueryWrapper<>();
            IwsCrudConditionSupport.apply(wrapper, column, condition);
            assertThat(wrapper.getSqlSegment()).contains(column.getDbColumn() + " =");
            assertThat(wrapper.getParamNameValuePairs()).containsValue("");
        }
    }

    @Test
    void emptyStringRemainsAnExactElementInsideStringInCondition() {
        IwsCrudColumnMetaDto column = stringColumn("string_in_values", "in", true);
        IwsQueryConditionBo condition = condition(column.getField(), "in", List.of("", "SUP-001"));
        QueryWrapper<Object> wrapper = new QueryWrapper<>();

        assertThatCode(() -> IwsCrudConditionSupport.apply(wrapper, column, condition))
            .doesNotThrowAnyException();
        assertThat(wrapper.getSqlSegment()).contains("IN");
        assertThat(wrapper.getParamNameValuePairs()).containsValues("", "SUP-001");
    }

    @Test
    void emptyStringIsRejectedForNonStringFieldsEvenWhenQueryModeMatches() {
        IwsCrudColumnMetaDto numericColumn = column("numeric_eq", "number", "eq", true);
        IwsQueryConditionBo eq = condition(numericColumn.getField(), "eq", "");
        assertThatThrownBy(() -> IwsCrudConditionSupport.validate(numericColumn, eq))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("非字符串字段不能使用空字符串查询");

        IwsCrudColumnMetaDto numericIn = column("numeric_in", "number", "in", true);
        IwsQueryConditionBo in = condition(numericIn.getField(), "in", List.of(""));
        assertThatThrownBy(() -> IwsCrudConditionSupport.validate(numericIn, in))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("非字符串字段不能使用空字符串查询");
    }

    @Test
    void explicitEmptyEqStillRequiresTheFieldToBeSearchable() {
        IwsCrudColumnMetaDto column = stringColumn("not_searchable", "likeRight", false);
        IwsQueryConditionBo condition = condition(column.getField(), "eq", "");

        assertThatThrownBy(() -> IwsCrudConditionSupport.validate(column, condition))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("字段不允许搜索");
    }

    @Test
    void omittedQueryModeKeepsTheExistingEqBehavior() {
        IwsQueryConditionBo condition = condition("supplierCode", null, "SUP-001");
        IwsCrudColumnMetaDto column = column(supplierMeta(), "supplierCode");

        assertThatCode(() -> validator.validateQuery(supplierMeta(), query(condition)))
            .doesNotThrowAnyException();

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        IwsCrudConditionSupport.apply(wrapper, column, condition);
        assertThat(wrapper.getSqlSegment()).contains(column.getDbColumn() + " =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("SUP-001");
    }

    @Test
    void nullValuesRemainEquivalentToOmittedConditions() {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        IwsQueryConditionBo condition = condition("supplierCode", null, null);
        IwsCrudColumnMetaDto column = column(supplierMeta(), "supplierCode");

        assertThatCode(() -> validator.validateQuery(supplierMeta(), query(condition)))
            .doesNotThrowAnyException();
        assertThatCode(() -> IwsCrudConditionSupport.apply(wrapper, column, condition))
            .doesNotThrowAnyException();
        assertThat(wrapper.getSqlSegment()).isEmpty();
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();
    }

    @Test
    void betweenRequiresBoundaryFieldsAndInRequiresACollection() {
        IwsCrudColumnMetaDto updateTime = column(supplierMeta(), "updateTime");
        IwsQueryConditionBo between = condition("updateTime", "between", "2026-10-01");
        assertThatThrownBy(() -> IwsCrudConditionSupport.validate(updateTime, between))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("范围查询必须使用开始值和结束值");

        IwsCrudColumnMetaDto inColumn = stringColumn("in_scalar", "in", true);
        IwsQueryConditionBo in = condition(inColumn.getField(), "in", "");
        assertThatThrownBy(() -> IwsCrudConditionSupport.validate(inColumn, in))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("in 查询值必须是集合");
    }

    @Test
    void nullArrayElementsAreBadRequestForSupplierAndCompanyQueries() {
        for (String tableCode : List.of("wms_supplier", "wms_company")) {
            IwsCrudTableMetaVo meta = metaRegistry.findByTableCode(tableCode).orElseThrow();

            IwsTableQueryBo conditions = new IwsTableQueryBo();
            conditions.setConditions(Collections.singletonList(null));
            assertNullElementRejected(meta, conditions, "conditions[0]");

            IwsTableQueryBo bulkConditions = new IwsTableQueryBo();
            bulkConditions.setBulkConditions(Collections.singletonList(null));
            assertNullElementRejected(meta, bulkConditions, "bulkConditions[0]");

            IwsTableQueryBo sorts = new IwsTableQueryBo();
            sorts.setSorts(Collections.singletonList(null));
            assertNullElementRejected(meta, sorts, "sorts[0]");
        }
    }

    private void assertNullElementRejected(IwsCrudTableMetaVo meta,
                                           IwsTableQueryBo query,
                                           String expectedPath) {
        assertThatThrownBy(() -> validator.validateQuery(meta, query))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getCode()).isEqualTo(HttpStatus.HTTP_BAD_REQUEST);
                assertThat(error.getMessage()).contains(expectedPath);
            });
    }

    private void assertExplicitEmptyEq(String tableCode, String field, String dbColumn) {
        IwsQueryConditionBo condition = condition(field, "eq", "");
        IwsCrudTableMetaVo meta = metaRegistry.findByTableCode(tableCode).orElseThrow();
        IwsCrudColumnMetaDto column = column(meta, field);

        assertThatCode(() -> validator.validateQuery(meta, query(condition))).doesNotThrowAnyException();

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        IwsCrudConditionSupport.apply(wrapper, column, condition);
        assertThat(wrapper.getSqlSegment()).contains(dbColumn + " =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("");
    }

    private IwsCrudColumnMetaDto column(IwsCrudTableMetaVo meta, String field) {
        return meta.getColumns().stream()
            .filter(item -> field.equals(item.getField()))
            .findFirst()
            .orElseThrow();
    }

    private IwsCrudColumnMetaDto stringColumn(String field, String queryMode, boolean searchable) {
        return column(field, "string", queryMode, searchable);
    }

    private IwsCrudColumnMetaDto column(String field, String dataType, String queryMode, boolean searchable) {
        IwsCrudColumnMetaDto column = new IwsCrudColumnMetaDto();
        column.setTableCode("query_contract");
        column.setField(field);
        column.setDbColumn(field);
        column.setDataType(dataType);
        column.setQueryMode(queryMode);
        column.setSearchable(searchable);
        return column;
    }

    private IwsCrudTableMetaVo supplierMeta() {
        return metaRegistry.findByTableCode("wms_supplier").orElseThrow();
    }

    private IwsTableQueryBo query(IwsQueryConditionBo condition) {
        IwsTableQueryBo query = new IwsTableQueryBo();
        query.setConditions(List.of(condition));
        return query;
    }

    private IwsQueryConditionBo condition(String field, String queryMode, Object value) {
        IwsQueryConditionBo condition = new IwsQueryConditionBo();
        condition.setField(field);
        condition.setQueryMode(queryMode);
        condition.setValue(value);
        return condition;
    }
}
