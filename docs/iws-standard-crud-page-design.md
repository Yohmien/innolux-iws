# IWS+ 标准表数据页设计

## 1. 目标与边界

本文定义 IWS+ 标准表数据页的统一设计，用于所有偏基础数据、单据数据、库存数据、历史数据和接口推送记录的数据展示与维护页面。

本设计先解决“表数据页面”的共性能力，不提前绑定复杂业务流程。入库、出库、库存变更、WCS 联动、上层系统推送等业务流程后续在标准表数据页之上扩展。

### 1.1 适用表范围

| 分类 | 表范围 |
| --- | --- |
| 基础数据 | 库区、储位、容器类型、容器、格位、供应商、物料 |
| 入库管理 | 入库单头、入库单体、入库任务推送记录 |
| 出库管理 | 出库单头、出库单体、出库任务推送记录 |
| 在库管理 | 库存、库存流水、库存历史 |
| 历史数据 | 入库单头历史、入库单体历史、出库单头历史、出库单体历史 |

### 1.2 非目标

本阶段不处理以下内容：

1. 入库、出库、盘点、合箱、拣选等完整业务状态机。
2. WCS、AGV、亮灯货架、SnailJob 任务调度的实时执行逻辑。
3. 老 Liteon 项目所有页面源码的直接复制。
4. 每张表单独硬编码一套搜索栏、按钮、导入导出逻辑。

## 2. 总体结论

IWS+ 标准表数据页采用以下方向：

```text
IWS+ Plus 前端底座
+ Liteon 现场页面经验
+ 后端字段白名单
+ 元数据配置驱动 CRUD
+ 分页、索引、异步导出保障千万级数据性能
```

核心判断：

1. IWS+ 现有前端作为标准化底座，保留 Vue3、TypeScript、Element Plus、Pinia、Vite、vxe-table。
2. Liteon 作为交互参考，吸收固定搜索区、批量搜索、页面密度、快捷前缀搜索和四级权限思路。
3. 所有字段进入字段元数据配置，但不是所有字段默认成为搜索栏。
4. 搜索、列显示、列宽、固定列、排序、导入、导出、权限、字典、批量操作都由配置控制。
5. 后端必须白名单校验查询字段、排序字段、导入字段、导出字段、编辑字段。
6. 千万级数据不依赖前端分页，必须后端分页、索引、限制模糊查询和异步导出。

## 3. 前端总体设计

### 3.1 目录结构

新增标准表数据页组件目录：

```text
src/components/IwsCrudPage/
  index.ts
  IwsCrudPage.vue
  IwsSearchPanel.vue
  IwsBulkSearchDialog.vue
  IwsTableToolbar.vue
  IwsTableScrollContainer.vue
  IwsColumnSetting.vue
  IwsImportDialog.vue
  IwsExportDialog.vue
  types.ts

src/components/IwsFeedback/
  IwsMessageContent.vue

src/utils/
  iwsFeedback.ts
```

新增标准 hooks：

```text
src/hooks/crud/
  useCrudPage.ts
  useCrudMeta.ts
  useCrudQuery.ts
  useCrudSelection.ts
  useCrudColumns.ts
  useBulkSearch.ts
  useImportExport.ts
```

WMS 页面按业务域分包：

```text
src/views/wms/
  basic/
    supplier/index.vue
    material/index.vue
    warehouseZone/index.vue
    storage/index.vue
    containerType/index.vue
    container/index.vue
    compartment/index.vue
  inbound/
    order/index.vue
    orderLine/index.vue
    pushLog/index.vue
  outbound/
    order/index.vue
    orderLine/index.vue
    pushLog/index.vue
  inventory/
    current/index.vue
    flow/index.vue
    history/index.vue
```

API 按同样结构分包：

```text
src/api/wms/
  basic/supplier.ts
  basic/material.ts
  basic/warehouseZone.ts
  inbound/order.ts
  outbound/order.ts
  inventory/current.ts
```

### 3.2 页面使用方式

标准业务页面只声明表编码，不重复写整套页面能力：

```vue
<template>
  <IwsCrudPage table-code="wms_supplier" />
</template>

<script setup name="WmsSupplier" lang="ts">
import { IwsCrudPage } from '@/components/IwsCrudPage';
</script>
```

如果某个页面有特殊业务按钮，可以通过插槽追加，不允许破坏标准搜索、标准导入导出和标准权限校验。

代码生成器新增 `IWS标准Vue` 前端模板，普通单表 CRUD 选择该模板时，生成页默认就是上面的平铺入口：

```text
frontendType = iws-vue
tableCode = 数据库表名，例如 wms_supplier
```

`iws-vue` 模板只生成 `views/{moduleName}/{businessName}/index.vue`，不再生成每张表独立的前端 API、查询栏、表格、弹窗和导入导出按钮。页面运行依赖 `/wms/crud/{tableCode}` 通用接口和表字段元数据，因此新增标准表时必须同步维护该表的元数据、权限编码和后端服务接入。

### 3.3 组件职责

| 组件 | 职责 |
| --- | --- |
| `IwsCrudPage.vue` | 标准页面容器，整合查询、表格、分页、按钮、弹窗 |
| `IwsSearchPanel.vue` | 普通搜索、下拉搜索、日期范围、快捷前缀搜索 |
| `IwsBulkSearchDialog.vue` | 批量搜索弹窗，支持换行、逗号、分号分隔 |
| `IwsTableToolbar.vue` | 新增、修改、删除、导入、导出、模板、刷新、列设置 |
| `IwsTableScrollContainer.vue` | 参考 Liteon，固定搜索区和工具栏，只滚动表格 |
| `IwsColumnSetting.vue` | 控制列显示、列宽、列顺序、固定列、默认排序 |
| `IwsImportDialog.vue` | 上传、校验、预览、确认导入、失败原因下载 |
| `IwsExportDialog.vue` | 导出范围、导出列、异步进度、文件下载 |
| `IwsFeedback` | 标准消息提示，统一成功、警告、错误、倒计时和手动关闭 |

### 3.4 表格滚动标准

页面布局固定为：

```text
页面容器
  搜索区：固定，不随表格滚动
  工具栏：固定，不随表格滚动
  表格区：自适应高度，内部滚动
  分页区：固定在表格容器底部
```

实现要求：

1. `IwsTableScrollContainer` 使用 `ResizeObserver` 计算表格高度。
2. 搜索区展开、收起后重新计算表格高度。
3. 页签切换后主动刷新表格高度。
4. 普通表优先使用 `el-table`。
5. 历史、流水、库存等列多或数据量大的页面允许切换为 `vxe-table`。

### 3.5 标准反馈提示

所有标准 CRUD 页、系统通用 `modal` 插件和生成页模板统一使用 `src/utils/iwsFeedback.ts`。

标准要求：

1. 成功、警告、错误、信息提示必须显示在页面顶端中间，文案居中，不允许页面内各自定义一套提示样式。
2. 提示默认 30 秒后自动关闭，且必须支持手动关闭。
3. 关闭时间由系统参数 `sys.ui.messageCloseSeconds` 控制，单位秒，默认值为 `30`。
4. 参数配置页保存该参数后，需要刷新前端反馈配置缓存。
5. 删除确认、退出登录、导入导出弹窗统一使用标准弹窗圆角、标题区、底部按钮样式。
6. 请求异常仍由请求拦截器负责兜底；业务操作成功提示由业务页或标准 CRUD 页主动调用 `iwsFeedback.success`。

新增标准页面或生成器模板时，不允许直接散用 `ElMessage.success/error/warning`，应使用 `iwsFeedback` 或 `$modal`。

## 4. 字段元数据设计

### 4.1 字段配置模型

每个表字段都进入字段元数据，但不代表默认显示或默认可搜索。

```ts
export interface IwsColumnMeta {
  tableCode: string;
  field: string;
  dbColumn: string;
  label: string;
  dataType: 'string' | 'number' | 'decimal' | 'date' | 'datetime' | 'boolean' | 'dict';
  visible: boolean;
  defaultVisible: boolean;
  width?: number;
  minWidth?: number;
  fixed?: 'left' | 'right';
  align?: 'left' | 'center' | 'right';
  showOverflowTooltip: boolean;

  searchable: boolean;
  defaultSearchVisible: boolean;
  searchType?: 'input' | 'select' | 'dateRange' | 'datetimeRange' | 'numberRange' | 'remoteSelect';
  queryMode?: 'eq' | 'likeRight' | 'contains' | 'between' | 'in';
  bulkSearch: boolean;
  quickSearch: boolean;

  sortable: boolean;
  defaultSort?: 'asc' | 'desc';
  sortOrder?: number;

  addable: boolean;
  editable: boolean;
  importable: boolean;
  exportable: boolean;
  detailVisible: boolean;

  dictType?: string;
  optionApi?: string;
  permissionCode?: string;
  required?: boolean;
  readonly?: boolean;
  sensitive?: boolean;
}
```

### 4.2 配置层级

配置分三层：

| 层级 | 作用 | 是否允许用户改 |
| --- | --- | --- |
| 系统默认配置 | 标准产品默认字段、搜索、导入导出、排序能力 | 否 |
| 角色/权限配置 | 控制用户能否访问字段和动作 | 由管理员维护 |
| 用户个人配置 | 列显示、列宽、列顺序、固定列、搜索项显示 | 是 |

### 4.3 推荐元数据表

后端建议增加以下配置表：

```text
iws_table_meta
iws_table_column_meta
iws_user_table_column_setting
iws_user_table_search_setting
```

`iws_table_meta` 负责页面级配置：

| 字段 | 说明 |
| --- | --- |
| `table_code` | 标准表编码，如 `wms_inventory` |
| `table_name` | 数据库表名 |
| `module_code` | 模块编码，如 `wms` |
| `business_group` | 业务分组，如 `inventory` |
| `page_title` | 页面标题 |
| `primary_key` | 主键字段 |
| `default_sort` | 默认排序 |
| `page_size` | 默认分页大小 |
| `max_page_size` | 最大分页大小 |
| `allow_add/edit/delete/import/export` | 页面级能力开关 |
| `async_export_threshold` | 异步导出阈值 |

`iws_table_column_meta` 负责字段级配置：

| 字段 | 说明 |
| --- | --- |
| `table_code` | 表编码 |
| `field_name` | 前端字段名 |
| `column_name` | 数据库字段名 |
| `column_label` | 显示名称 |
| `data_type` | 字段类型 |
| `dict_type` | 字典类型 |
| `visible_flag` | 默认是否展示 |
| `search_flag` | 是否允许搜索 |
| `search_type` | 搜索控件类型 |
| `query_mode` | 查询模式 |
| `bulk_search_flag` | 是否允许批量搜索 |
| `quick_search_flag` | 是否允许快捷搜索 |
| `sort_flag` | 是否允许排序 |
| `add_flag` | 是否允许新增填写 |
| `edit_flag` | 是否允许编辑 |
| `import_flag` | 是否允许导入 |
| `export_flag` | 是否允许导出 |
| `permission_code` | 字段级权限编码 |

## 5. 搜索标准

### 5.1 搜索类型

| 类型 | queryMode | 适用场景 | 性能要求 |
| --- | --- | --- | --- |
| 精确搜索 | `eq` | 状态、类型、ID、编码、单号 | 推荐默认 |
| 前缀搜索 | `likeRight` | 物料码、容器号、单号前缀 | 允许，需索引支持 |
| 包含模糊 | `contains` | 名称、备注、小表字段 | 严格限制 |
| 批量搜索 | `in` | 物料码、批次、容器号、ReelID、单号 | 限制数量 |
| 范围搜索 | `between` | 日期、数量、重量 | 需组合索引 |
| 下拉搜索 | `eq` | 状态、类型、库区、供应商 | 字典或远程选项 |

### 5.2 批量搜索

批量搜索输入规则：

1. 支持换行、英文逗号、中文逗号、英文分号、中文分号。
2. 自动去空格。
3. 自动去重。
4. 前端默认最多 1000 个值。
5. 后端最多接收 5000 个值。
6. 超过限制不直接拼 `IN`，后续走异步查询或临时表方案。

适合批量搜索字段：

| 表 | 字段 |
| --- | --- |
| 物料 | `materialCode` |
| 容器 | `containerCode` |
| 库存 | `materialCode`、`materialBatch`、`containerCode`、`stockUnitCode` |
| 入库单 | `inboundOrderCode`、`sourceOrderCode` |
| 出库单 | `outboundOrderCode`、`sourceOrderCode` |
| 推送记录 | `requestNo`、`sourceOrderCode`、`traceId` |

### 5.3 快捷前缀搜索

快捷前缀搜索用于一个输入框快速识别不同字段：

| 前缀 | 对应字段 |
| --- | --- |
| `mat:` | 物料编码 |
| `box:` | 容器编码 |
| `lc:` | 物料批次 |
| `reel:` | 库存单元/ReelID |
| `in:` | 入库单号 |
| `out:` | 出库单号 |
| `src:` | 上层系统来源单号 |
| `trace:` | 推送追踪号 |

快捷搜索必须由后端元数据返回启用规则，前端不能自行猜字段。

## 6. 导入导出标准

### 6.1 导出

导出范围：

1. 当前查询条件。
2. 当前选中行。
3. 当前页。
4. 全部符合条件数据，超过阈值走异步导出。

导出字段：

1. 只允许选择 `exportable = true` 的字段。
2. 默认导出列按元数据 `export_order` 排序。
3. 用户可临时勾选导出列。
4. 字段权限不足时不展示、不导出。

导出执行：

| 数据量 | 策略 |
| --- | --- |
| 小数据量 | 同步导出 |
| 大数据量 | 异步导出任务 |
| 超大历史数据 | 分片查询、流式写 Excel、完成后下载 |

前端必须展示导出加载状态。异步导出显示进度、状态、失败原因和下载入口。

### 6.2 导入

导入流程：

```text
下载模板 -> 上传文件 -> 后端解析 -> 数据校验 -> 预览结果 -> 确认导入 -> 结果反馈
```

导入规则：

1. 只允许导入 `importable = true` 的字段。
2. 必填字段由元数据和后端校验共同控制。
3. 字典字段导入时支持字典标签和值转换。
4. 导入失败必须返回行号、字段、失败原因。
5. 大文件导入走异步任务。
6. 导入必须保留操作日志。

## 7. 权限设计

权限采用四级模型：

| 层级 | 含义 | 示例 |
| --- | --- | --- |
| 菜单权限 | 能否进入页面 | `wms:inventory:view` |
| 按钮权限 | 能否执行动作 | `wms:inventory:add` |
| 数据权限 | 能看到哪些数据 | 厂别、仓库、库区、部门 |
| 字段权限 | 字段是否可见、可编辑、可导入导出 | `wms:inventory:field:supplierCode` |

标准权限编码：

```text
wms:{resource}:list
wms:{resource}:query
wms:{resource}:add
wms:{resource}:edit
wms:{resource}:remove
wms:{resource}:batchRemove
wms:{resource}:batchEdit
wms:{resource}:import
wms:{resource}:export
wms:{resource}:template
wms:{resource}:column
wms:{resource}:bulkQuery
```

前端通过 `v-hasPermi` 隐藏按钮；后端必须通过 `@SaCheckPermission` 和服务层校验再次拦截。

## 8. 后端总体设计

### 8.1 模块结构

WMS 放到独立模块：

```text
iws-modules/iws-wms
```

包结构：

```text
com.innolux.iws.wms
  controller
  domain.entity
  domain.bo
  domain.vo
  domain.dto
  mapper
  service
  service.impl
  support.meta
  support.query
  support.excel
  support.permission
  enums
```

### 8.2 类职责

| 包 | 职责 |
| --- | --- |
| `controller` | 接收请求、权限注解、参数校验、返回统一响应 |
| `domain.entity` | 数据库实体，不放页面逻辑 |
| `domain.bo` | 查询、新增、修改入参 |
| `domain.vo` | 列表、详情、导出返回对象 |
| `domain.dto` | 导入、导出、元数据、批量操作 DTO |
| `mapper` | MyBatis / MyBatis Plus 数据访问 |
| `service` | 业务接口 |
| `service.impl` | 业务实现 |
| `support.meta` | 表和字段元数据解析 |
| `support.query` | 查询条件白名单、排序白名单、查询构造 |
| `support.excel` | 导入导出、模板、异步任务 |
| `support.permission` | 字段权限、数据权限、动作权限 |
| `enums` | 状态枚举、查询模式、导入导出状态 |

### 8.3 标准接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/wms/{resource}/meta` | 获取页面元数据 |
| `GET` | `/wms/{resource}/list` | 分页查询 |
| `GET` | `/wms/{resource}/{id}` | 详情 |
| `POST` | `/wms/{resource}` | 新增 |
| `PUT` | `/wms/{resource}` | 修改 |
| `DELETE` | `/wms/{resource}/{ids}` | 删除 |
| `POST` | `/wms/{resource}/export` | 导出 |
| `POST` | `/wms/{resource}/import` | 导入 |
| `POST` | `/wms/{resource}/template` | 导入模板 |
| `POST` | `/wms/{resource}/columns` | 保存用户列配置 |

资源名示例：

```text
supplier
material
warehouse-zone
storage
container-type
container
inventory
inventory-flow
inbound-order
outbound-order
inbound-push-log
outbound-push-log
```

## 9. 后端查询标准

### 9.1 请求模型

```java
public class IwsTableQueryBo {
    private Integer pageNum;
    private Integer pageSize;
    private List<IwsQueryCondition> conditions;
    private List<IwsBulkCondition> bulkConditions;
    private List<IwsSortCondition> sorts;
}
```

```java
public class IwsQueryCondition {
    private String field;
    private String queryMode;
    private Object value;
    private Object beginValue;
    private Object endValue;
}
```

```java
public class IwsBulkCondition {
    private String field;
    private List<String> values;
}
```

```java
public class IwsSortCondition {
    private String field;
    private String order;
}
```

### 9.2 白名单规则

后端必须执行：

1. 查询字段必须在当前表元数据中存在。
2. 查询字段必须 `search_flag = 1`。
3. 查询模式必须是该字段允许的 `query_mode`。
4. 批量搜索字段必须 `bulk_search_flag = 1`。
5. 排序字段必须 `sort_flag = 1`。
6. 导出字段必须 `export_flag = 1`。
7. 导入字段必须 `import_flag = 1`。
8. 新增字段必须 `add_flag = 1`。
9. 修改字段必须 `edit_flag = 1`。

禁止前端传数据库字段名直接拼接 SQL。前端只能传 `field`，后端根据元数据映射到 `column_name`。

### 9.3 查询模式限制

| queryMode | SQL 行为 | 限制 |
| --- | --- | --- |
| `eq` | `column = ?` | 默认推荐 |
| `likeRight` | `column like 'xxx%'` | 编码类字段推荐 |
| `contains` | `column like '%xxx%'` | 小表或低频字段才允许 |
| `between` | `column between ? and ?` | 日期、数量范围 |
| `in` | `column in (...)` | 必须限制数量 |

### 9.4 默认排序

所有表必须有稳定默认排序：

| 表类型 | 默认排序 |
| --- | --- |
| 基础资料 | `update_time desc, id desc` |
| 当前库存 | `update_time desc, inventory_id desc` |
| 单据 | `create_time desc, order_id desc` |
| 历史/流水 | `event_time desc, id desc` |
| 推送记录 | `push_time desc, log_id desc` |

## 10. 性能设计

### 10.1 分页

1. 所有列表接口必须分页。
2. 默认 `pageSize = 50`。
3. 最大 `pageSize = 1000`。
4. 历史表和流水表后续支持游标分页。
5. 禁止前端一次加载全量数据。

### 10.2 索引

索引按页面查询模式设计，不按字段盲目建索引。

库存类常用索引方向：

```text
material_code + material_batch
container_code
storage_code
inventory_status + update_time
plant_code + warehouse_type + inventory_status
```

单据类常用索引方向：

```text
order_code
source_order_code
order_status + create_time
plant_code + warehouse_type + create_time
```

推送记录常用索引方向：

```text
source_system + push_time
source_order_code
request_no
trace_id
process_status + push_time
```

### 10.3 大数据导出

导出阈值建议：

| 数据量 | 策略 |
| --- | --- |
| <= 5 万 | 同步导出 |
| 5 万 - 100 万 | 异步导出 |
| > 100 万 | 分批导出或要求增加过滤条件 |

导出实现要求：

1. 后端分批查询。
2. Excel 流式写入。
3. 不允许一次性把所有数据放入内存。
4. 前端显示导出中、成功、失败。
5. 失败必须有原因。

### 10.4 批量操作

批量删除、批量修改、批量状态变更：

1. 前端只传 ID 集合和操作参数。
2. 后端按批次执行。
3. 每批建议 500 - 1000 条。
4. 必须记录操作日志。
5. 部分成功时返回成功数、失败数、失败原因。

## 11. 字典和状态设计

状态字段统一使用字典，不允许在前端散落硬编码。

建议字典：

| 字典类型 | 用途 |
| --- | --- |
| `wms_common_status` | 通用启停 |
| `wms_storage_status` | 储位状态 |
| `wms_container_status` | 容器状态 |
| `wms_inventory_status` | 库存状态 |
| `wms_order_status` | 单据状态 |
| `wms_push_status` | 推送处理状态 |
| `wms_quality_status` | 品质状态 |
| `wms_frozen_state` | 冻结状态 |

前端字段元数据中配置 `dictType`，统一由字典组件渲染。

## 12. 代码注释风格

### 12.1 文件头

沿用 IWS+ 统一文件头。代码、配置、Vue、TS、Java 文件新增时按对应语法添加。

Java：

```java
/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准表数据页后端查询、元数据、导入导出与权限控制；
 * @ModifyRecord : V1.0 新建；
 */
```

Vue：

```vue
<!--
 - @Date         : 2026-09-22
 - @Author       : YONGJIE.PENG
 - @Email        : YONGJIE.PENG@INNOLUX.COM
 - @Job          : INNOLUX IWS 标准化重构
 - @Module       : IWS 标准表数据页前端
 - @Version      : V1.0
 - @Description  : 标准 CRUD 页面容器，统一搜索、表格、导入导出、列配置；
 - @ModifyRecord : V1.0 新建；
-->
```

JSON、lockfile、图片、字体、二进制文件不加文件头。

### 12.2 类和方法注释

后端公开类、Controller 接口、Service 接口必须写 Javadoc。

要求：

1. 注释说明业务含义，不写无意义翻译。
2. 复杂查询必须说明索引意图。
3. 白名单、安全、权限相关逻辑必须说明原因。
4. 简单 getter/setter 不写废话注释。

示例：

```java
/**
 * 根据字段元数据构造安全查询条件。
 *
 * <p>前端只能传业务字段名，不能传数据库列名。本方法负责校验字段是否允许查询，
 * 并将业务字段映射为白名单内的数据库列，避免 SQL 注入和越权查询。</p>
 */
public LambdaQueryWrapper<T> buildQuery(IwsTableQueryBo queryBo, IwsTableMeta tableMeta) {
    // ...
}
```

### 12.3 前端注释

前端注释原则：

1. 组件 props、核心 hook、复杂计算逻辑必须注释。
2. 不给显而易见代码加注释。
3. 表格高度、批量搜索、列配置持久化等容易出问题的位置必须说明原因。

示例：

```ts
// 页签从隐藏切回可见时不会触发 window resize，需要主动重新计算表格高度。
function recalculateTableHeight() {
  // ...
}
```

## 13. 开发落地顺序

### 第一阶段：标准能力骨架

1. 新增 `IwsCrudPage` 前端组件目录。
2. 新增 `IwsTableScrollContainer`，吸收 Liteon 表格滚动体验。
3. 新增批量搜索弹窗。
4. 新增列设置弹窗，支持列显示、列宽、排序、固定列。
5. 新增后端 `iws-wms` 模块骨架。
6. 新增元数据 DTO、查询 DTO、导入导出 DTO。

### 第二阶段：样板表

优先做 `wms_supplier`。

原因：

1. 字段适中。
2. 业务逻辑少。
3. 能验证新增、修改、删除、查询、导入、导出、模板、列配置。
4. 能验证权限和字典。

第二个样板做 `wms_inventory`。

原因：

1. 字段多。
2. 查询复杂。
3. 数据量大。
4. 能验证批量搜索、性能、索引、异步导出。

### 第三阶段：推广到所有表

按顺序推广：

```text
供应商 -> 物料 -> 库区 -> 储位 -> 容器类型 -> 容器 -> 格位
-> 入库单头 -> 入库单体 -> 入库推送记录
-> 出库单头 -> 出库单体 -> 出库推送记录
-> 当前库存 -> 库存流水 -> 库存历史
-> 入库历史 -> 出库历史
```

## 14. 验收标准

每个标准表页面必须满足：

1. 搜索区固定，表格区滚动。
2. 搜索栏可按字段配置控制。
3. 支持普通搜索、下拉搜索、日期范围、批量搜索。
4. 支持列显示、列宽、列顺序、固定列维护。
5. 支持后端分页。
6. 支持默认排序和用户触发排序。
7. 支持新增、修改、删除、批量删除。
8. 支持导入、导入模板、导出。
9. 导出有加载状态，大数据导出有异步状态。
10. 所有按钮受权限控制。
11. 所有后端接口做权限校验。
12. 所有查询字段、排序字段、导入导出字段做白名单校验。
13. 字典字段统一通过字典展示。
14. 页面代码不出现散落硬编码状态文本。
15. 列表查询不使用 `select *`。
16. 高频查询字段有索引设计。

## 15. 风险控制

| 风险 | 控制方式 |
| --- | --- |
| 元数据过度复杂 | 第一阶段只做必要字段，不做低价值配置 |
| 页面过度抽象导致难维护 | 通用能力放组件，特殊业务用插槽扩展 |
| 模糊查询拖慢千万级表 | 只允许少数字段开启 `contains` |
| 导出占用内存 | 大数据强制异步、分批、流式写入 |
| 前端权限绕过 | 后端重复校验权限和字段白名单 |
| 用户列配置污染默认配置 | 用户配置独立表保存，可一键恢复默认 |
| 批量搜索 SQL 过长 | 限制数量，超限走异步或临时表方案 |

## 16. 最终设计判断

本方案不是把 Liteon 项目搬进 IWS+，而是把 Liteon 已验证的现场体验抽象成 IWS+ 标准能力。

最终形态：

```text
一套标准组件
一套标准接口
一套字段元数据
一套权限模型
一套导入导出机制
一套性能约束
多张表复用
```

这样既能保证标准化，又能保留 SMT 电子仓现场需要的高密度查询、批量搜索、快速定位、列控制和大数据导入导出能力。
