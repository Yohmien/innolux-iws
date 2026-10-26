# process 对象授权与 process:object:all 使用说明

日期：2026-10-22。适用于 `iws-process` 的 HTTP 命令与查询入口。

## 授权入口

- 用户入口：`ProcessObjectAuthorizer.authorizeUserInstance/authorizeUserEvent/authorizeUserSchedule`。未登录直接拒绝；已登录用户必须有 `process:object:all`，或由已注册 `ProcessAccessPolicy` 对目标业务范围放行。认证异常不会转成“允许”。
- 查询兼容入口：`authorizeInstance/authorizeEvent/authorizeSchedule` 与用户入口同义，供 Query/Overview/Chain 复用。
- worker 入口：`authorizeTrustedEvent` 只能在 `ProcessTrustedInvocation.call/run` 内调用；job/worker 不使用登录会话，也不伪造用户身份。

## Permission（菜单/按钮）SQL

SQL 由 SQL 代理执行；下列片段以 `@process_menu_id` 为现有“计划事件”父菜单 ID，执行前替换。普通角色不要自动授予 `process:object:all`。

```sql
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
     visible, status, perms, icon, create_by, create_time, remark)
VALUES
    ('计划对象全范围', @process_menu_id, 99, '', '', 1, 0, 'F',
     '0', '0', 'process:object:all', '#', 1, NOW(), '计划事件对象范围全量授权');
```

仅为明确的管理员角色授予：

```sql
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @admin_role_id, menu_id
FROM sys_menu
WHERE perms = 'process:object:all'
LIMIT 1;
```

## 业务范围策略

业务模块注册一个 `ProcessAccessPolicy` Bean，按 `ProcessAccessTarget` 的 scheduleId/businessType/businessId/taskKey/scheduleScope 返回是否允许；未注册时只允许 `process:object:all`，不默认放行普通登录用户。
