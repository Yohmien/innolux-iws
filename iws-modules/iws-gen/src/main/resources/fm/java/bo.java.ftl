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

package ${packageName}.domain.bo;

import ${packageName}.domain.${ClassName};
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import java.io.Serial;
import java.io.Serializable;
<#if hasBetween>
import java.util.HashMap;
import java.util.Map;
</#if>
import lombok.Data;
import jakarta.validation.constraints.*;
<#list importList as import>
import ${import};
</#list>

/**
 * ${functionName}业务对象 ${tableName}
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@AutoMapper(target = ${ClassName}.class, reverseConvertGenerate = false)
public class ${ClassName}Bo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

<#list columns as column>
<#if column.query || (!table.isSuperColumn(column.javaField) && (column.insert || column.edit))>
    /**
     * ${column.columnComment}
     */
<#if column.insert && column.edit>
<#assign Group = "AddGroup.class, EditGroup.class">
<#elseif column.insert>
<#assign Group = "AddGroup.class">
<#elseif column.edit>
<#assign Group = "EditGroup.class">
</#if>
<#if column.required && !table.isSuperColumn(column.javaField) && (column.insert || column.edit)>
<#if column.javaType == 'String'>
    @NotBlank(message = "${column.columnComment}不能为空", groups = { ${Group} })
<#else>
    @NotNull(message = "${column.columnComment}不能为空", groups = { ${Group} })
</#if>
</#if>
    private ${column.javaType} ${column.javaField};

</#if>
</#list>
<#if hasBetween>
    /**
     * 查询参数
     */
    private Map<String, Object> params = new HashMap<>();
</#if>

}
