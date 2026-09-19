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

package ${packageName}.domain;

import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
<#list importList as import>
import ${import};
</#list>

import java.io.Serial;

/**
 * ${functionName}对象 ${tableName}
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("${tableName}")
public class ${ClassName} extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

<#list columns as column>
<#if !table.isSuperColumn(column.javaField)>
    /**
     * ${column.columnComment}
     */
<#if column.javaField=='delFlag'>
    @TableLogic
</#if>
<#if column.javaField=='version'>
    @Version
</#if>
<#if column.pk>
    @TableId(value = "${column.columnName}")
<#elseif column.needTableField>
    @TableField(value = "${column.columnName}")
</#if>
    private ${column.javaType} ${column.javaField};

</#if>
</#list>

}
