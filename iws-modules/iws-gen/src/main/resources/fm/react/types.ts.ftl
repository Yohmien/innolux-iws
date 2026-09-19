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

import type { BaseEntity<#if !table.tree>, PageQuery</#if> } from '@/api/types';

export interface ${BusinessName}VO {
<#list columns as column>
<#if column.list || column.pk>
  /**
   * ${column.columnComment}
   */
  ${column.javaField}: ${column.tsType};
<#if column.htmlType == "imageUpload">
  /**
   * ${column.columnComment}Url
   */
  ${column.javaField}Url?: string;
</#if>
</#if>
</#list>
<#if table.tree>
  /**
   * 子对象
   */
  children?: ${BusinessName}VO[];
</#if>
}

export interface ${BusinessName}Form extends BaseEntity {
<#list columns as column>
<#if column.insert || column.edit || column.pk>
  /**
   * ${column.columnComment}
   */
<#if column.htmlType == "checkbox">
  ${column.javaField}?: string | string[];
<#else>
  ${column.javaField}?: ${column.tsType};
</#if>
</#if>
</#list>
}

export interface ${BusinessName}Query<#if !table.tree> extends PageQuery</#if> {
<#list columns as column>
<#if column.query>
  /**
   * ${column.columnComment}
   */
  ${column.javaField}?: ${column.tsType};
</#if>
</#list>
  /**
   * 日期范围参数
   */
  params?: Record<string, unknown>;
}
