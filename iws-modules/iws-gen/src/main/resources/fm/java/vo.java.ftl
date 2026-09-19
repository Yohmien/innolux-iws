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

package ${packageName}.domain.vo;

<#list importList as import>
import ${import};
</#list>
import ${packageName}.domain.${ClassName};
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import com.innolux.iws.common.excel.annotation.ExcelDictFormat;
import com.innolux.iws.common.excel.convert.ExcelDictConvert;
import com.innolux.iws.common.translation.annotation.Translation;
import com.innolux.iws.common.translation.constant.TransConstant;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ${functionName}视图对象 ${tableName}
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = ${ClassName}.class)
public class ${ClassName}Vo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

<#list columns as column>
<#if column.list>
    /**
     * ${column.columnComment}
     */
<#assign parentheseIndex = column.columnComment?index_of("（")>
<#if column.dictType?has_content>
    @ExcelProperty(value = "${column.columnLabel}", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "${column.dictType}")
<#elseif parentheseIndex != -1>
    @ExcelProperty(value = "${column.columnLabel}", converter = ExcelDictConvert.class)
    @ExcelDictFormat(readConverterExp = "${column.readConverterExp()}")
<#else>
    @ExcelProperty(value = "${column.columnLabel}")
</#if>
    private ${column.javaType} ${column.javaField};

<#if column.htmlType == "imageUpload">
    /**
     * ${column.columnComment}Url
     */
    @Translation(type = TransConstant.OSS_ID_TO_URL, mapper = "${column.javaField}")
    private String ${column.javaField}Url;
</#if>
</#if>
</#list>

}
