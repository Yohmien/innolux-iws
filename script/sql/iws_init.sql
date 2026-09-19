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

create database if not exists innolux_iws default character set utf8mb4 collate utf8mb4_general_ci;
use innolux_iws;

-- ===================================================
-- 基础权限与菜单数据
-- Segment: 基础权限与菜单初始化
-- ===================================================

-- ----------------------------
-- 第三方平台授权表
-- ----------------------------
create table sys_social
(
    id                 bigint           not null        comment '主键',
    user_id            bigint           not null        comment '用户ID',
    auth_id            varchar(255)     not null        comment '平台+平台唯一id',
    source             varchar(255)     not null        comment '用户来源',
    open_id            varchar(255)     default null    comment '平台编号唯一id',
    user_name          varchar(30)      not null        comment '登录账号',
    nick_name          varchar(30)      default ''      comment '用户昵称',
    email              varchar(255)     default ''      comment '用户邮箱',
    avatar             varchar(500)     default ''      comment '头像地址',
    access_token       varchar(2000)     not null       comment '用户的授权令牌',
    expire_in          int              default null    comment '用户的授权令牌的有效期，部分平台可能没有',
    refresh_token      varchar(2000)     default null    comment '刷新令牌，部分平台可能没有',
    access_code        varchar(255)     default null    comment '平台的授权信息，部分平台可能没有',
    union_id           varchar(255)     default null    comment '用户的 unionid',
    scope              varchar(255)     default null    comment '授予的权限，部分平台可能没有',
    token_type         varchar(255)     default null    comment '个别平台的授权信息，部分平台可能没有',
    id_token           varchar(2000)    default null    comment 'id token，部分平台可能没有',
    mac_algorithm      varchar(255)     default null    comment '小米平台用户的附带属性，部分平台可能没有',
    mac_key            varchar(255)     default null    comment '小米平台用户的附带属性，部分平台可能没有',
    code               varchar(255)     default null    comment '用户的授权code，部分平台可能没有',
    oauth_token        varchar(255)     default null    comment 'Twitter平台用户的附带属性，部分平台可能没有',
    oauth_token_secret varchar(255)     default null    comment 'Twitter平台用户的附带属性，部分平台可能没有',
    create_dept        bigint(20)                       comment '创建部门',
    create_by          bigint(20)                       comment '创建者',
    create_time        datetime                         comment '创建时间',
    update_by          bigint(20)                       comment '更新者',
    update_time        datetime                         comment '更新时间',
    del_flag           char(1)          default '0'     comment '删除标志（0代表存在 1代表删除）',
    PRIMARY KEY (id)
) engine=innodb comment = '社会化关系表';

-- ----------------------------
-- 1、部门表
-- ----------------------------
create table sys_dept (
    dept_id           bigint(20)      not null                   comment '部门id',
    parent_id         bigint(20)      default 0                  comment '父部门id',
    ancestors         varchar(500)    default ''                 comment '祖级列表',
    dept_name         varchar(30)     default ''                 comment '部门名称',
    dept_category     varchar(100)    default null               comment '部门类别编码',
    order_num         int(4)          default 0                  comment '显示顺序',
    leader            bigint(20)      default null               comment '负责人',
    phone             varchar(11)     default null               comment '联系电话',
    email             varchar(50)     default null               comment '邮箱',
    status            char(1)         default '0'                comment '部门状态（0正常 1停用）',
    del_flag          char(1)         default '0'                comment '删除标志（0代表存在 1代表删除）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    primary key (dept_id),
    key idx_sys_dept_parent_id (parent_id)
) engine=innodb comment = '部门表';

-- ----------------------------
-- 初始化-部门表数据
-- ----------------------------


insert into sys_dept values(1761000000000000100, 0, '0', 'IWS+', null, 0, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000101, 1761000000000000100, '0,1761000000000000100', 'INNOLUX', null, 1, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000102, 1761000000000000100, '0,1761000000000000100', 'WMS运营中心', null, 2, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000103, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '系统管理部', null, 1, 1761100000000000001, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000104, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '仓储作业部', null, 2, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000105, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '品质管控部', null, 3, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000106, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '数据监控部', null, 4, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000107, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '设备运维部', null, 5, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000108, 1761000000000000102, '0,1761000000000000100,1761000000000000102', '入库管理组', null, 1, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
insert into sys_dept values(1761000000000000109, 1761000000000000102, '0,1761000000000000100,1761000000000000102', '出库管理组', null, 2, null, '15888888888', 'wms@innolux.com', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);


-- ----------------------------
-- 2、用户信息表
-- ----------------------------
create table sys_user (
    user_id           bigint(20)      not null                   comment '用户ID',
    dept_id           bigint(20)      default null               comment '部门ID',
    user_name         varchar(30)     not null                   comment '用户账号',
    nick_name         varchar(30)     not null                   comment '用户昵称',
    user_type         varchar(10)     default 'sys_user'         comment '用户类型（sys_user系统用户）',
    email             varchar(50)     default ''                 comment '用户邮箱',
    phone_number      varchar(11)     default ''                 comment '手机号码',
    gender            char(1)         default '0'                comment '用户性别（0男 1女 2未知）',
    avatar            bigint(20)                                 comment '头像地址',
    password          varchar(100)    default ''                 comment '密码',
    status            char(1)         default '0'                comment '账号状态（0正常 1停用）',
    del_flag          char(1)         default '0'                comment '删除标志（0代表存在 1代表删除）',
    login_ip          varchar(128)    default ''                 comment '最后登录IP',
    login_date        datetime                                   comment '最后登录时间',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    remark            varchar(500)    default null               comment '备注',
    primary key (user_id),
    key idx_sys_user_dept_id   (dept_id),
    key idx_sys_user_create_by (create_by),
    key idx_sys_user_user_name (user_name),
    key idx_sys_user_phone     (phone_number)
) engine=innodb comment = '用户信息表';

-- ----------------------------
-- 初始化-用户信息表数据
-- ----------------------------
insert into sys_user values(1761100000000000001, 1761000000000000103, 'admin', 'INNOLUX管理员', 'sys_user', 'wms@innolux.com', '15888888888', '1', null, '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', sysdate(), 1761000000000000103, 1761100000000000001, sysdate(), null, null, '管理员');
insert into sys_user values(1761100000000000003, 1761000000000000108, 'test', 'WMS主管 密码666666', 'sys_user', '', '', '0', null, '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne', '0', '0', '127.0.0.1', sysdate(), 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000003, sysdate(), null);
insert into sys_user values(1761100000000000004, 1761000000000000102, 'test1', 'WMS作业员 密码666666', 'sys_user', '', '', '0', null, '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne', '0', '0', '127.0.0.1', sysdate(), 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000004, sysdate(), null);

-- ----------------------------
-- 3、岗位信息表
-- ----------------------------
create table sys_post
(
    post_id       bigint(20)      not null                   comment '岗位ID',
    dept_id       bigint(20)      not null                   comment '部门id',
    post_code     varchar(64)     not null                   comment '岗位编码',
    post_category varchar(100)    default null               comment '岗位类别编码',
    post_name     varchar(50)     not null                   comment '岗位名称',
    post_sort     int(4)          not null                   comment '显示顺序',
    status        char(1)         not null                   comment '状态（0正常 1停用）',
    del_flag      char(1)         default '0'                comment '删除标志（0代表存在 1代表删除）',
    create_dept   bigint(20)      default null               comment '创建部门',
    create_by     bigint(20)      default null               comment '创建者',
    create_time   datetime                                   comment '创建时间',
    update_by     bigint(20)      default null               comment '更新者',
    update_time   datetime                                   comment '更新时间',
    remark        varchar(500)    default null               comment '备注',
    primary key (post_id),
    key idx_sys_post_dept_id (dept_id)
) engine=innodb comment = '岗位信息表';

-- ----------------------------
-- 初始化-岗位信息表数据
-- ----------------------------
insert into sys_post values(1761200000000000001, 1761000000000000103, 'ceo', null, '董事长', 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_post values(1761200000000000002, 1761000000000000100, 'se', null, '项目经理', 2, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_post values(1761200000000000003, 1761000000000000100, 'hr', null, '人力资源', 3, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_post values(1761200000000000004, 1761000000000000100, 'user', null, '普通员工', 4, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');


-- ----------------------------
-- 4、角色信息表
-- ----------------------------
create table sys_role (
    role_id              bigint(20)      not null                   comment '角色ID',
    role_name            varchar(30)     not null                   comment '角色名称',
    role_key             varchar(100)    not null                   comment '角色权限字符串',
    role_sort            int(4)          not null                   comment '显示顺序',
    data_scope           char(1)         default '1'                comment '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限 6：部门及以下或本人数据权限）',
    menu_check_strictly  tinyint(1)      default 1                  comment '菜单树选择项是否关联显示',
    dept_check_strictly  tinyint(1)      default 1                  comment '部门树选择项是否关联显示',
    status               char(1)         not null                   comment '角色状态（0正常 1停用）',
    del_flag             char(1)         default '0'                comment '删除标志（0代表存在 1代表删除）',
    create_dept          bigint(20)      default null               comment '创建部门',
    create_by            bigint(20)      default null               comment '创建者',
    create_time          datetime                                   comment '创建时间',
    update_by            bigint(20)      default null               comment '更新者',
    update_time          datetime                                   comment '更新时间',
    remark               varchar(500)    default null               comment '备注',
    primary key (role_id),
    key idx_sys_role_create_dept (create_dept),
    key idx_sys_role_create_by   (create_by)
) engine=innodb comment = '角色信息表';

-- ----------------------------
-- 初始化-角色信息表数据
-- ----------------------------
insert into sys_role values(1761300000000000001, '超级管理员', 'superadmin', 1, 1, 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '超级管理员');
insert into sys_role values(1761300000000000003, '本部门及以下', 'test1', 3, 4, 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_role values(1761300000000000004, '仅本人', 'test2', 4, 5, 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- ----------------------------
-- 5、菜单权限表
-- ----------------------------
create table sys_menu (
    menu_id           bigint(20)      not null                   comment '菜单ID',
    menu_name         varchar(50)     not null                   comment '菜单名称',
    parent_id         bigint(20)      default 0                  comment '父菜单ID',
    order_num         int(4)          default 0                  comment '显示顺序',
    path              varchar(200)    default ''                 comment '路由地址',
    component         varchar(255)    default null               comment '组件路径',
    query_param       varchar(255)    default null               comment '路由参数',
    is_frame          char(1)         default 'N'                comment '是否为外链（Y是 N否）',
    is_cache          char(1)         default 'Y'                comment '是否缓存（Y缓存 N不缓存）',
    menu_type         char(1)         default ''                 comment '菜单类型（M目录 C菜单 F按钮）',
    visible           char(1)         default 0                  comment '显示状态（0显示 1隐藏）',
    status            char(1)         default 0                  comment '菜单状态（0正常 1停用）',
    perms             varchar(100)    default null               comment '权限标识',
    icon              varchar(100)    default '#'                comment '菜单图标',
    active_menu       varchar(255)    default ''                 comment '激活菜单路径',
    ext               varchar(2000)   default ''                 comment '扩展字段',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    remark            varchar(500)    default ''                 comment '备注',
    primary key (menu_id)
) engine=innodb comment = '菜单权限表';

-- ----------------------------
-- 初始化-菜单信息表数据
-- ----------------------------
-- 一级菜单
insert into sys_menu values(1761400000000000001, '系统管理', 0, 1, 'system', null, '', 'N', 'Y', 'M', '0', '0', '', 'system', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统管理目录');
insert into sys_menu values(1761400000000000002, '系统监控', 0, 3, 'monitor', null, '', 'N', 'Y', 'M', '0', '0', '', 'monitor', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统监控目录');
insert into sys_menu values(1761400000000000003, '系统工具', 0, 4, 'tool', null, '', 'N', 'Y', 'M', '0', '0', '', 'tool', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统工具目录');
insert into sys_menu values(1761400000000000005, '测试菜单', 0, 5, 'demo', null, '', 'N', 'Y', 'M', '0', '0', '', 'star', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '测试菜单');
insert into sys_menu values(1761400000000000008, 'AI会话',  0, 8, 'aichat', 'ai/chat/index', '', 'N', 'Y', 'C', '0', '0', '', 'checkbox', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'AI聊天菜单');
insert into sys_menu values(1761400000000000004, 'INNOLUX官网', 0, 9, 'https://www.innolux.com', null, '', 'Y', 'Y', 'M', '0', '0', '', 'guide', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'INNOLUX官网地址');
-- 二级菜单
insert into sys_menu values(1761400000000000100, '用户管理', 1761400000000000001, 1, 'user', 'system/user/index', '', 'N', 'Y', 'C', '0', '0', 'system:user:list', 'user', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '用户管理菜单');
insert into sys_menu values(1761400000000000101, '角色管理', 1761400000000000001, 2, 'role', 'system/role/index', '', 'N', 'Y', 'C', '0', '0', 'system:role:list', 'peoples', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '角色管理菜单');
insert into sys_menu values(1761400000000000102, '菜单管理', 1761400000000000001, 3, 'menu', 'system/menu/index', '', 'N', 'Y', 'C', '0', '0', 'system:menu:list', 'tree-table', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '菜单管理菜单');
insert into sys_menu values(1761400000000000103, '部门管理', 1761400000000000001, 4, 'dept', 'system/dept/index', '', 'N', 'Y', 'C', '0', '0', 'system:dept:list', 'tree', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '部门管理菜单');
insert into sys_menu values(1761400000000000104, '岗位管理', 1761400000000000001, 5, 'post', 'system/post/index', '', 'N', 'Y', 'C', '0', '0', 'system:post:list', 'post', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '岗位管理菜单');
insert into sys_menu values(1761400000000000105, '字典管理', 1761400000000000001, 6, 'dict', 'system/dict/index', '', 'N', 'Y', 'C', '0', '0', 'system:dict:list', 'dict', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '字典管理菜单');
insert into sys_menu values(1761400000000000106, '参数设置', 1761400000000000001, 7, 'config', 'system/config/index', '', 'N', 'Y', 'C', '0', '0', 'system:config:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '参数设置菜单');
insert into sys_menu values(1761400000000000107, '通知公告', 1761400000000000001, 8, 'notice', 'system/notice/index', '', 'N', 'Y', 'C', '0', '0', 'system:notice:list', 'message', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '通知公告菜单');
insert into sys_menu values(1761400000000000108, '日志管理', 1761400000000000001, 9, 'log', '', '', 'N', 'Y', 'M', '0', '0', '', 'log', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '日志管理菜单');
insert into sys_menu values(1761400000000000109, '在线用户', 1761400000000000002, 1, 'online', 'monitor/online/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:online:list', 'online', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '在线用户菜单');
insert into sys_menu values(1761400000000000113, '缓存监控', 1761400000000000002, 5, 'cache', 'monitor/cache/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:cache:list', 'redis', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '缓存监控菜单');
insert into sys_menu values(1761400000000000115, '代码生成', 1761400000000000003, 2, 'gen', 'tool/gen/index', '', 'N', 'Y', 'C', '0', '0', 'tool:gen:list', 'code', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '代码生成菜单');
insert into sys_menu values(1761400000000000123, '客户端管理', 1761400000000000001, 11, 'client', 'system/client/index', '', 'N', 'Y', 'C', '0', '0', 'system:client:list', 'international', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '客户端管理菜单');
insert into sys_menu values(1761400000000000116, '修改生成配置', 1761400000000000003, 2, 'gen-edit/index/:tableId', 'tool/gen/editTable', '', 'N', 'N', 'C', '1', '0', 'tool:gen:edit', '#', '/tool/gen', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000000130, '分配用户', 1761400000000000001, 2, 'role-auth/user/:roleId', 'system/role/authUser', '', 'N', 'N', 'C', '1', '0', 'system:role:edit', '#', '/system/role', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000000131, '分配角色', 1761400000000000001, 1, 'user-auth/role/:userId', 'system/user/authRole', '', 'N', 'N', 'C', '1', '0', 'system:user:edit', '#', '/system/user', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000000133, '文件配置管理', 1761400000000000001, 10, 'oss-config/index', 'system/oss/config', '', 'N', 'N', 'C', '1', '0', 'system:ossConfig:list', '#', '/system/oss', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- springboot-admin监控
insert into sys_menu values(1761400000000000117, 'Admin监控', 1761400000000000002, 5, 'Admin', 'monitor/admin/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:admin:list', 'dashboard', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'Admin监控菜单');
-- oss菜单
insert into sys_menu values(1761400000000000118, '文件管理', 1761400000000000001, 10, 'oss', 'system/oss/index', '', 'N', 'Y', 'C', '0', '0', 'system:oss:list', 'upload', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '文件管理菜单');
-- snail-job server控制台
insert into sys_menu values(1761400000000000120, '任务调度中心', 1761400000000000002, 6, 'snailjob', 'monitor/snailjob/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:snailjob:list', 'job', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'SnailJob控制台菜单');
-- snail-ai server控制台
insert into sys_menu values(1761400000000000121, 'AI控制台', 1761400000000000002, 7, 'snailai', 'monitor/snailai/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:snailai:list', 'checkbox', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'AI控制台菜单');

-- 三级菜单
insert into sys_menu values(1761400000000000500, '操作日志', 1761400000000000108, 1, 'operlog', 'monitor/operlog/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:operlog:list', 'form', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '操作日志菜单');
insert into sys_menu values(1761400000000000501, '登录日志', 1761400000000000108, 2, 'logininfo', 'monitor/logininfo/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:logininfo:list', 'logininfo', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '登录日志菜单');
-- 用户管理按钮
insert into sys_menu values(1761400000000001001, '用户查询', 1761400000000000100, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001002, '用户新增', 1761400000000000100, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001003, '用户修改', 1761400000000000100, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001004, '用户删除', 1761400000000000100, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001005, '用户导出', 1761400000000000100, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001006, '用户导入', 1761400000000000100, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:import', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001007, '重置密码', 1761400000000000100, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:resetPwd', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 角色管理按钮
insert into sys_menu values(1761400000000001008, '角色查询', 1761400000000000101, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001009, '角色新增', 1761400000000000101, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001010, '角色修改', 1761400000000000101, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001011, '角色删除', 1761400000000000101, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001012, '角色导出', 1761400000000000101, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 菜单管理按钮
insert into sys_menu values(1761400000000001013, '菜单查询', 1761400000000000102, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001014, '菜单新增', 1761400000000000102, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001015, '菜单修改', 1761400000000000102, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001016, '菜单删除', 1761400000000000102, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 部门管理按钮
insert into sys_menu values(1761400000000001017, '部门查询', 1761400000000000103, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001018, '部门新增', 1761400000000000103, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001019, '部门修改', 1761400000000000103, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001020, '部门删除', 1761400000000000103, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 岗位管理按钮
insert into sys_menu values(1761400000000001021, '岗位查询', 1761400000000000104, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001022, '岗位新增', 1761400000000000104, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001023, '岗位修改', 1761400000000000104, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001024, '岗位删除', 1761400000000000104, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001025, '岗位导出', 1761400000000000104, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 字典管理按钮
insert into sys_menu values(1761400000000001026, '字典查询', 1761400000000000105, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001027, '字典新增', 1761400000000000105, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001028, '字典修改', 1761400000000000105, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001029, '字典删除', 1761400000000000105, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001030, '字典导出', 1761400000000000105, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 参数设置按钮
insert into sys_menu values(1761400000000001031, '参数查询', 1761400000000000106, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001032, '参数新增', 1761400000000000106, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001033, '参数修改', 1761400000000000106, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001034, '参数删除', 1761400000000000106, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001035, '参数导出', 1761400000000000106, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 通知公告按钮
insert into sys_menu values(1761400000000001036, '公告查询', 1761400000000000107, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001037, '公告新增', 1761400000000000107, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001038, '公告修改', 1761400000000000107, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001039, '公告删除', 1761400000000000107, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 操作日志按钮
insert into sys_menu values(1761400000000001040, '操作查询', 1761400000000000500, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001041, '操作删除', 1761400000000000500, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001042, '日志导出', 1761400000000000500, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 登录日志按钮
insert into sys_menu values(1761400000000001043, '登录查询', 1761400000000000501, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001044, '登录删除', 1761400000000000501, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001045, '日志导出', 1761400000000000501, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001050, '账户解锁', 1761400000000000501, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:unlock', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 在线用户按钮
insert into sys_menu values(1761400000000001046, '在线查询', 1761400000000000109, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001047, '批量强退', 1761400000000000109, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:batchLogout', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001048, '单条强退', 1761400000000000109, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:forceLogout', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 代码生成按钮
insert into sys_menu values(1761400000000001055, '生成查询', 1761400000000000115, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001056, '生成修改', 1761400000000000115, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001057, '生成删除', 1761400000000000115, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001058, '导入代码', 1761400000000000115, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:import', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001059, '预览代码', 1761400000000000115, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:preview', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001060, '生成代码', 1761400000000000115, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:code', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- oss相关按钮
insert into sys_menu values(1761400000000001600, '文件查询', 1761400000000000118, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001601, '文件上传', 1761400000000000118, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:upload', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001602, '文件下载', 1761400000000000118, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:download', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001603, '文件删除', 1761400000000000118, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001620, '配置列表', 1761400000000000118, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001621, '配置添加', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001622, '配置编辑', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001623, '配置删除', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 客户端管理按钮
insert into sys_menu values(1761400000000001061, '客户端管理查询', 1761400000000000123, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001062, '客户端管理新增', 1761400000000000123, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001063, '客户端管理修改', 1761400000000000123, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001064, '客户端管理删除', 1761400000000000123, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001065, '客户端管理导出', 1761400000000000123, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 测试菜单
insert into sys_menu values(1761400000000001500, '测试单表', 1761400000000000005, 1, 'demo', 'demo/demo/index', '', 'N', 'Y', 'C', '0', '0', 'demo:demo:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '测试单表菜单');
insert into sys_menu values(1761400000000001501, '测试单表查询', 1761400000000001500, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001502, '测试单表新增', 1761400000000001500, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001503, '测试单表修改', 1761400000000001500, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001504, '测试单表删除', 1761400000000001500, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001505, '测试单表导出', 1761400000000001500, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001506, '测试树表', 1761400000000000005, 1, 'tree', 'demo/tree/index', '', 'N', 'Y', 'C', '0', '0', 'demo:tree:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '测试树表菜单');
insert into sys_menu values(1761400000000001507, '测试树表查询', 1761400000000001506, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001508, '测试树表新增', 1761400000000001506, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001509, '测试树表修改', 1761400000000001506, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001510, '测试树表删除', 1761400000000001506, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000001511, '测试树表导出', 1761400000000001506, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- ----------------------------
-- 6、用户和角色关联表  用户N-1角色
-- ----------------------------
create table sys_user_role (
    user_id   bigint(20) not null comment '用户ID',
    role_id   bigint(20) not null comment '角色ID',
    primary key(user_id, role_id),
    key idx_sys_user_role_rid (role_id)
) engine=innodb comment = '用户和角色关联表';

-- ----------------------------
-- 初始化-用户和角色关联表数据
-- ----------------------------
insert into sys_user_role values (1761100000000000001, 1761300000000000001);
insert into sys_user_role values (1761100000000000003, 1761300000000000003);
insert into sys_user_role values (1761100000000000004, 1761300000000000004);

-- ----------------------------
-- 7、角色和菜单关联表  角色1-N菜单
-- ----------------------------
create table sys_role_menu (
    role_id   bigint(20) not null comment '角色ID',
    menu_id   bigint(20) not null comment '菜单ID',
    primary key(role_id, menu_id)
) engine=innodb comment = '角色和菜单关联表';

-- ----------------------------
-- 初始化-角色和菜单关联表数据
-- ----------------------------
insert into sys_role_menu values (1761300000000000003, 1761400000000000001);
insert into sys_role_menu values (1761300000000000003, 1761400000000000005);
insert into sys_role_menu values (1761300000000000003, 1761400000000000100);
insert into sys_role_menu values (1761300000000000003, 1761400000000000101);
insert into sys_role_menu values (1761300000000000003, 1761400000000000102);
insert into sys_role_menu values (1761300000000000003, 1761400000000000103);
insert into sys_role_menu values (1761300000000000003, 1761400000000000104);
insert into sys_role_menu values (1761300000000000003, 1761400000000000105);
insert into sys_role_menu values (1761300000000000003, 1761400000000000106);
insert into sys_role_menu values (1761300000000000003, 1761400000000000107);
insert into sys_role_menu values (1761300000000000003, 1761400000000000108);
insert into sys_role_menu values (1761300000000000003, 1761400000000000118);
insert into sys_role_menu values (1761300000000000003, 1761400000000000123);
insert into sys_role_menu values (1761300000000000003, 1761400000000000130);
insert into sys_role_menu values (1761300000000000003, 1761400000000000131);
insert into sys_role_menu values (1761300000000000003, 1761400000000000133);
insert into sys_role_menu values (1761300000000000003, 1761400000000000500);
insert into sys_role_menu values (1761300000000000003, 1761400000000000501);
insert into sys_role_menu values (1761300000000000003, 1761400000000001001);
insert into sys_role_menu values (1761300000000000003, 1761400000000001002);
insert into sys_role_menu values (1761300000000000003, 1761400000000001003);
insert into sys_role_menu values (1761300000000000003, 1761400000000001004);
insert into sys_role_menu values (1761300000000000003, 1761400000000001005);
insert into sys_role_menu values (1761300000000000003, 1761400000000001006);
insert into sys_role_menu values (1761300000000000003, 1761400000000001007);
insert into sys_role_menu values (1761300000000000003, 1761400000000001008);
insert into sys_role_menu values (1761300000000000003, 1761400000000001009);
insert into sys_role_menu values (1761300000000000003, 1761400000000001010);
insert into sys_role_menu values (1761300000000000003, 1761400000000001011);
insert into sys_role_menu values (1761300000000000003, 1761400000000001012);
insert into sys_role_menu values (1761300000000000003, 1761400000000001013);
insert into sys_role_menu values (1761300000000000003, 1761400000000001014);
insert into sys_role_menu values (1761300000000000003, 1761400000000001015);
insert into sys_role_menu values (1761300000000000003, 1761400000000001016);
insert into sys_role_menu values (1761300000000000003, 1761400000000001017);
insert into sys_role_menu values (1761300000000000003, 1761400000000001018);
insert into sys_role_menu values (1761300000000000003, 1761400000000001019);
insert into sys_role_menu values (1761300000000000003, 1761400000000001020);
insert into sys_role_menu values (1761300000000000003, 1761400000000001021);
insert into sys_role_menu values (1761300000000000003, 1761400000000001022);
insert into sys_role_menu values (1761300000000000003, 1761400000000001023);
insert into sys_role_menu values (1761300000000000003, 1761400000000001024);
insert into sys_role_menu values (1761300000000000003, 1761400000000001025);
insert into sys_role_menu values (1761300000000000003, 1761400000000001026);
insert into sys_role_menu values (1761300000000000003, 1761400000000001027);
insert into sys_role_menu values (1761300000000000003, 1761400000000001028);
insert into sys_role_menu values (1761300000000000003, 1761400000000001029);
insert into sys_role_menu values (1761300000000000003, 1761400000000001030);
insert into sys_role_menu values (1761300000000000003, 1761400000000001031);
insert into sys_role_menu values (1761300000000000003, 1761400000000001032);
insert into sys_role_menu values (1761300000000000003, 1761400000000001033);
insert into sys_role_menu values (1761300000000000003, 1761400000000001034);
insert into sys_role_menu values (1761300000000000003, 1761400000000001035);
insert into sys_role_menu values (1761300000000000003, 1761400000000001036);
insert into sys_role_menu values (1761300000000000003, 1761400000000001037);
insert into sys_role_menu values (1761300000000000003, 1761400000000001038);
insert into sys_role_menu values (1761300000000000003, 1761400000000001039);
insert into sys_role_menu values (1761300000000000003, 1761400000000001040);
insert into sys_role_menu values (1761300000000000003, 1761400000000001041);
insert into sys_role_menu values (1761300000000000003, 1761400000000001042);
insert into sys_role_menu values (1761300000000000003, 1761400000000001043);
insert into sys_role_menu values (1761300000000000003, 1761400000000001044);
insert into sys_role_menu values (1761300000000000003, 1761400000000001045);
insert into sys_role_menu values (1761300000000000003, 1761400000000001050);
insert into sys_role_menu values (1761300000000000003, 1761400000000001061);
insert into sys_role_menu values (1761300000000000003, 1761400000000001062);
insert into sys_role_menu values (1761300000000000003, 1761400000000001063);
insert into sys_role_menu values (1761300000000000003, 1761400000000001064);
insert into sys_role_menu values (1761300000000000003, 1761400000000001065);
insert into sys_role_menu values (1761300000000000003, 1761400000000001500);
insert into sys_role_menu values (1761300000000000003, 1761400000000001501);
insert into sys_role_menu values (1761300000000000003, 1761400000000001502);
insert into sys_role_menu values (1761300000000000003, 1761400000000001503);
insert into sys_role_menu values (1761300000000000003, 1761400000000001504);
insert into sys_role_menu values (1761300000000000003, 1761400000000001505);
insert into sys_role_menu values (1761300000000000003, 1761400000000001506);
insert into sys_role_menu values (1761300000000000003, 1761400000000001507);
insert into sys_role_menu values (1761300000000000003, 1761400000000001508);
insert into sys_role_menu values (1761300000000000003, 1761400000000001509);
insert into sys_role_menu values (1761300000000000003, 1761400000000001510);
insert into sys_role_menu values (1761300000000000003, 1761400000000001511);
insert into sys_role_menu values (1761300000000000003, 1761400000000001600);
insert into sys_role_menu values (1761300000000000003, 1761400000000001601);
insert into sys_role_menu values (1761300000000000003, 1761400000000001602);
insert into sys_role_menu values (1761300000000000003, 1761400000000001603);
insert into sys_role_menu values (1761300000000000003, 1761400000000001620);
insert into sys_role_menu values (1761300000000000003, 1761400000000001621);
insert into sys_role_menu values (1761300000000000003, 1761400000000001622);
insert into sys_role_menu values (1761300000000000003, 1761400000000001623);
insert into sys_role_menu values (1761300000000000003, 1761400000000011616);
insert into sys_role_menu values (1761300000000000003, 1761400000000011618);
insert into sys_role_menu values (1761300000000000003, 1761400000000011619);
insert into sys_role_menu values (1761300000000000003, 1761400000000011622);
insert into sys_role_menu values (1761300000000000003, 1761400000000011623);
insert into sys_role_menu values (1761300000000000003, 1761400000000011629);
insert into sys_role_menu values (1761300000000000003, 1761400000000011632);
insert into sys_role_menu values (1761300000000000003, 1761400000000011633);
insert into sys_role_menu values (1761300000000000003, 1761400000000011638);
insert into sys_role_menu values (1761300000000000003, 1761400000000011639);
insert into sys_role_menu values (1761300000000000003, 1761400000000011640);
insert into sys_role_menu values (1761300000000000003, 1761400000000011641);
insert into sys_role_menu values (1761300000000000003, 1761400000000011642);
insert into sys_role_menu values (1761300000000000003, 1761400000000011643);
insert into sys_role_menu values (1761300000000000003, 1761400000000011701);
insert into sys_role_menu values (1761300000000000004, 1761400000000000005);
insert into sys_role_menu values (1761300000000000004, 1761400000000001500);
insert into sys_role_menu values (1761300000000000004, 1761400000000001501);
insert into sys_role_menu values (1761300000000000004, 1761400000000001502);
insert into sys_role_menu values (1761300000000000004, 1761400000000001503);
insert into sys_role_menu values (1761300000000000004, 1761400000000001504);
insert into sys_role_menu values (1761300000000000004, 1761400000000001505);
insert into sys_role_menu values (1761300000000000004, 1761400000000001506);
insert into sys_role_menu values (1761300000000000004, 1761400000000001507);
insert into sys_role_menu values (1761300000000000004, 1761400000000001508);
insert into sys_role_menu values (1761300000000000004, 1761400000000001509);
insert into sys_role_menu values (1761300000000000004, 1761400000000001510);
insert into sys_role_menu values (1761300000000000004, 1761400000000001511);

-- ----------------------------
-- 8、角色和部门关联表  角色1-N部门
-- ----------------------------
create table sys_role_dept (
    role_id   bigint(20) not null comment '角色ID',
    dept_id   bigint(20) not null comment '部门ID',
    primary key(role_id, dept_id)
) engine=innodb comment = '角色和部门关联表';

-- ----------------------------
-- 9、用户与岗位关联表  用户1-N岗位
-- ----------------------------
create table sys_user_post
(
    user_id   bigint(20) not null comment '用户ID',
    post_id   bigint(20) not null comment '岗位ID',
    primary key (user_id, post_id)
) engine=innodb comment = '用户与岗位关联表';

-- ----------------------------
-- 初始化-用户与岗位关联表数据
-- ----------------------------
insert into sys_user_post values (1761100000000000001, 1761200000000000001);

-- ----------------------------
-- 10、操作日志记录
-- ----------------------------
create table sys_oper_log (
    oper_id           bigint(20)      not null                   comment '日志主键',
    title             varchar(50)     default ''                 comment '模块标题',
    business_type     int(2)          default 0                  comment '业务类型（0其它 1新增 2修改 3删除）',
    method            varchar(100)    default ''                 comment '方法名称',
    request_method    varchar(10)     default ''                 comment '请求方式',
    operator_type     int(1)          default 0                  comment '操作类别（0其它 1后台用户 2手机端用户）',
    oper_name         varchar(50)     default ''                 comment '操作人员',
    user_id           bigint(20)      default null               comment '操作用户ID',
    dept_id           bigint(20)      default null               comment '操作部门ID',
    dept_name         varchar(50)     default ''                 comment '部门名称',
    client_key        varchar(32)     default ''                 comment '客户端',
    device_type       varchar(32)     default ''                 comment '设备类型',
    browser           varchar(50)     default ''                 comment '浏览器类型',
    os                varchar(50)     default ''                 comment '操作系统',
    oper_url          varchar(255)    default ''                 comment '请求URL',
    oper_ip           varchar(128)    default ''                 comment '主机地址',
    oper_location     varchar(255)    default ''                 comment '操作地点',
    oper_param        varchar(4000)   default ''                 comment '请求参数',
    json_result       varchar(4000)   default ''                 comment '返回参数',
    status            int(1)          default 0                  comment '操作状态（0正常 1异常）',
    error_msg         varchar(4000)   default ''                 comment '错误消息',
    oper_time         datetime                                   comment '操作时间',
    cost_time         bigint(20)      default 0                  comment '消耗时间',
    primary key (oper_id),
    key idx_sys_oper_log_bt (business_type),
    key idx_sys_oper_log_uid (user_id),
    key idx_sys_oper_log_s  (status),
    key idx_sys_oper_log_ot (oper_time)
) engine=innodb comment = '操作日志记录';


-- ----------------------------
-- 11、字典类型表
-- ----------------------------
create table sys_dict_type
(
    dict_id          bigint(20)      not null                   comment '字典主键',
    dict_name        varchar(100)    default ''                 comment '字典名称',
    dict_type        varchar(100)    default ''                 comment '字典类型',
    create_dept      bigint(20)      default null               comment '创建部门',
    create_by        bigint(20)      default null               comment '创建者',
    create_time      datetime                                   comment '创建时间',
    update_by        bigint(20)      default null               comment '更新者',
    update_time      datetime                                   comment '更新时间',
    remark           varchar(500)    default null               comment '备注',
    primary key (dict_id),
    unique (dict_type)
) engine=innodb comment = '字典类型表';

insert into sys_dict_type values(1761500000000000001, '用户性别', 'sys_user_gender', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '用户性别列表');
insert into sys_dict_type values(1761500000000000002, '菜单状态', 'sys_show_hide', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '菜单状态列表');
insert into sys_dict_type values(1761500000000000003, '系统开关', 'sys_normal_disable', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统开关列表');
insert into sys_dict_type values(1761500000000000006, '系统是否', 'sys_yes_no', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统是否列表');
insert into sys_dict_type values(1761500000000000007, '通知类型', 'sys_notice_type', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '通知类型列表');
insert into sys_dict_type values(1761500000000000008, '通知状态', 'sys_notice_status', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '通知状态列表');
insert into sys_dict_type values(1761500000000000009, '操作类型', 'sys_oper_type', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '操作类型列表');
insert into sys_dict_type values(1761500000000000010, '系统状态', 'sys_common_status', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '登录状态列表');
insert into sys_dict_type values(1761500000000000011, '授权类型', 'sys_grant_type', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '认证授权类型');
insert into sys_dict_type values(1761500000000000012, '设备类型', 'sys_device_type', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '客户端设备类型');


-- ----------------------------
-- 12、字典数据表
-- ----------------------------
create table sys_dict_data
(
    dict_code        bigint(20)      not null                   comment '字典编码',
    dict_sort        int(4)          default 0                  comment '字典排序',
    dict_label       varchar(100)    default ''                 comment '字典标签',
    dict_value       varchar(100)    default ''                 comment '字典键值',
    dict_type        varchar(100)    default ''                 comment '字典类型',
    css_class        varchar(100)    default null               comment '样式属性（其他样式扩展）',
    list_class       varchar(100)    default null               comment '表格回显样式',
    is_default       char(1)         default 'N'                comment '是否默认（Y是 N否）',
    create_dept      bigint(20)      default null               comment '创建部门',
    create_by        bigint(20)      default null               comment '创建者',
    create_time      datetime                                   comment '创建时间',
    update_by        bigint(20)      default null               comment '更新者',
    update_time      datetime                                   comment '更新时间',
    remark           varchar(500)    default null               comment '备注',
    primary key (dict_code),
    key idx_sys_dict_data_type (dict_type)
) engine=innodb comment = '字典数据表';

insert into sys_dict_data values(1761600000000000001, 1, '男', '0', 'sys_user_gender', '', '', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '性别男');
insert into sys_dict_data values(1761600000000000002, 2, '女', '1', 'sys_user_gender', '', '', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '性别女');
insert into sys_dict_data values(1761600000000000003, 3, '未知', '2', 'sys_user_gender', '', '', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '性别未知');
insert into sys_dict_data values(1761600000000000004, 1, '显示', '0', 'sys_show_hide', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '显示菜单');
insert into sys_dict_data values(1761600000000000005, 2, '隐藏', '1', 'sys_show_hide', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '隐藏菜单');
insert into sys_dict_data values(1761600000000000006, 1, '正常', '0', 'sys_normal_disable', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000007, 2, '停用', '1', 'sys_normal_disable', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '停用状态');
insert into sys_dict_data values(1761600000000000012, 1, '是', 'Y', 'sys_yes_no', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统默认是');
insert into sys_dict_data values(1761600000000000013, 2, '否', 'N', 'sys_yes_no', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '系统默认否');
insert into sys_dict_data values(1761600000000000014, 1, '通知', '1', 'sys_notice_type', '', 'warning', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '通知');
insert into sys_dict_data values(1761600000000000015, 2, '公告', '2', 'sys_notice_type', '', 'success', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '公告');
insert into sys_dict_data values(1761600000000000016, 1, '正常', '0', 'sys_notice_status', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000017, 2, '关闭', '1', 'sys_notice_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '关闭状态');
insert into sys_dict_data values(1761600000000000029, 99, '其他', '0', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '其他操作');
insert into sys_dict_data values(1761600000000000018, 1, '新增', '1', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '新增操作');
insert into sys_dict_data values(1761600000000000019, 2, '修改', '2', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '修改操作');
insert into sys_dict_data values(1761600000000000020, 3, '删除', '3', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '删除操作');
insert into sys_dict_data values(1761600000000000021, 4, '授权', '4', 'sys_oper_type', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '授权操作');
insert into sys_dict_data values(1761600000000000022, 5, '导出', '5', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '导出操作');
insert into sys_dict_data values(1761600000000000023, 6, '导入', '6', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '导入操作');
insert into sys_dict_data values(1761600000000000024, 7, '强退', '7', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '强退操作');
insert into sys_dict_data values(1761600000000000025, 8, '生成代码', '8', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '生成操作');
insert into sys_dict_data values(1761600000000000026, 9, '清空数据', '9', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '清空操作');
insert into sys_dict_data values(1761600000000000027, 1, '成功', '0', 'sys_common_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000028, 2, '失败', '1', 'sys_common_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '停用状态');
insert into sys_dict_data values(1761600000000000030, 0, '密码认证', 'password', 'sys_grant_type', 'el-check-tag', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '密码认证');
insert into sys_dict_data values(1761600000000000031, 0, '短信认证', 'sms', 'sys_grant_type', 'el-check-tag', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '短信认证');
insert into sys_dict_data values(1761600000000000032, 0, '邮件认证', 'email', 'sys_grant_type', 'el-check-tag', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '邮件认证');
insert into sys_dict_data values(1761600000000000033, 0, '小程序认证', 'xcx', 'sys_grant_type', 'el-check-tag', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '小程序认证');
insert into sys_dict_data values(1761600000000000034, 0, '三方登录认证', 'social', 'sys_grant_type', 'el-check-tag', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '三方登录认证');
insert into sys_dict_data values(1761600000000000035, 0, 'PC', 'pc', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'PC');
insert into sys_dict_data values(1761600000000000036, 0, '安卓', 'android', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '安卓');
insert into sys_dict_data values(1761600000000000037, 0, 'iOS', 'ios', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'iOS');
insert into sys_dict_data values(1761600000000000038, 0, '小程序', 'xcx', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '小程序');


-- ----------------------------
-- 13、参数配置表
-- ----------------------------
create table sys_config (
    config_id         bigint(20)      not null                   comment '参数主键',
    config_name       varchar(100)    default ''                 comment '参数名称',
    config_key        varchar(100)    default ''                 comment '参数键名',
    config_value      varchar(500)    default ''                 comment '参数键值',
    config_type       char(1)         default 'N'                comment '系统内置（Y是 N否）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    remark            varchar(500)    default null               comment '备注',
    primary key (config_id)
) engine=innodb comment = '参数配置表';

insert into sys_config values(1761700000000000001, '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '初始化密码 123456');
insert into sys_config values(1761700000000000002, '账号自助-是否开启用户注册功能', 'sys.account.registerUser', 'false', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '是否开启注册用户功能（true开启，false关闭）');
insert into sys_config values(1761700000000000003, 'OSS预览列表资源开关', 'sys.oss.previewListResource', 'true', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'true:开启, false:关闭');


-- ----------------------------
-- 14、系统访问记录
-- ----------------------------
create table sys_login_info (
    info_id        bigint(20)     not null                  comment '访问ID',
    user_name      varchar(50)    default ''                comment '用户账号',
    client_key     varchar(32)    default ''                comment '客户端',
    device_type    varchar(32)    default ''                comment '设备类型',
    ipaddr         varchar(128)   default ''                comment '登录IP地址',
    login_location varchar(255)   default ''                comment '登录地点',
    browser        varchar(50)    default ''                comment '浏览器类型',
    os             varchar(50)    default ''                comment '操作系统',
    status         char(1)        default '0'               comment '登录状态（0正常 1异常）',
    msg            varchar(255)   default ''                comment '提示消息',
    login_time     datetime                                 comment '访问时间',
    primary key (info_id),
    key idx_sys_login_info_s  (status),
    key idx_sys_login_info_lt (login_time)
) engine=innodb comment = '系统访问记录';


-- ----------------------------
-- 17、通知公告表
-- ----------------------------
create table sys_notice (
    notice_id         bigint(20)      not null                   comment '公告ID',
    notice_title      varchar(50)     not null                   comment '公告标题',
    notice_type       char(1)         not null                   comment '公告类型（1通知 2公告）',
    notice_content    longblob        default null               comment '公告内容',
    status            char(1)         default '0'                comment '公告状态（0正常 1关闭）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    remark            varchar(255)    default null               comment '备注',
    primary key (notice_id)
) engine=innodb comment = '通知公告表';

-- ----------------------------
-- 初始化-公告信息表数据
-- ----------------------------
insert into sys_notice values(1761800000000000001, 'IWS+上线提醒', '2', '智能仓储管理系统已就绪', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '管理员');
insert into sys_notice values(1761800000000000002, 'IWS+维护通知', '1', '智能仓储管理系统计划维护', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '管理员');


-- ----------------------------
-- 18、消息记录表
-- ----------------------------
create table sys_message (
    message_id        bigint(20)      not null                   comment '消息ID',
    category          varchar(20)     not null                   comment '消息分组(system/notice/workflow)',
    type              varchar(20)     not null                   comment '消息类型',
    source            varchar(20)     not null                   comment '消息来源',
    title             varchar(100)    default ''                 comment '标题',
    message           varchar(500)    default ''                 comment '摘要消息',
    content           longtext                                   comment '详细内容',
    data_json         longtext                                   comment '扩展数据JSON',
    path              varchar(500)    default null               comment '前端跳转路径',
    send_user_ids     varchar(2000)   not null default '0'       comment '目标用户ID串，0表示全局',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    primary key (message_id),
    key idx_sys_message_category_time (category, create_time)
) engine=innodb comment = '消息记录表';


-- ----------------------------
-- 19、代码生成业务表
-- ----------------------------
create table gen_table (
    table_id          bigint(20)      not null                   comment '编号',
    data_name         varchar(200)    default ''                 comment '数据源名称',
    table_name        varchar(200)    default ''                 comment '表名称',
    table_comment     varchar(500)    default ''                 comment '表描述',
    class_name        varchar(100)    default ''                 comment '实体类名称',
    tpl_category      varchar(200)    default 'crud'             comment '使用的模板（crud单表操作 tree树表操作）',
    frontend_type     varchar(50)     default 'vue'              comment '前端模板类型，对应 vm 下的模板目录',
    package_name      varchar(100)                               comment '生成包路径',
    module_name       varchar(30)                                comment '生成模块名',
    business_name     varchar(30)                                comment '生成业务名',
    function_name     varchar(50)                                comment '生成功能名',
    function_author   varchar(50)                                comment '生成功能作者',
    gen_type          char(1)         default '0'                comment '生成代码方式（0zip压缩包 1自定义路径）',
    gen_path          varchar(200)    default '/'                comment '生成路径（不填默认项目路径）',
    options           varchar(1000)                              comment '其它生成选项',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    remark            varchar(500)    default null               comment '备注',
    primary key (table_id)
) engine=innodb comment = '代码生成业务表';


-- ----------------------------
-- 19、代码生成业务表字段
-- ----------------------------
create table gen_table_column (
    column_id         bigint(20)      not null                   comment '编号',
    table_id          bigint(20)                                 comment '归属表编号',
    column_name       varchar(200)                               comment '列名称',
    column_comment    varchar(500)                               comment '列描述',
    column_type       varchar(100)                               comment '列类型',
    java_type         varchar(500)                               comment 'JAVA类型',
    java_field        varchar(200)                               comment 'JAVA字段名',
    is_pk             char(1)                                    comment '是否主键（1是）',
    is_increment      char(1)                                    comment '是否自增（1是）',
    is_required       char(1)                                    comment '是否必填（1是）',
    is_insert         char(1)                                    comment '是否为插入字段（1是）',
    is_edit           char(1)                                    comment '是否编辑字段（1是）',
    is_list           char(1)                                    comment '是否列表字段（1是）',
    is_query          char(1)                                    comment '是否查询字段（1是）',
    query_type        varchar(200)    default 'EQ'               comment '查询方式（等于、不等于、大于、小于、范围）',
    html_type         varchar(200)                               comment '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
    dict_type         varchar(200)    default ''                 comment '字典类型',
    sort              int                                        comment '排序',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null               comment '创建者',
    create_time       datetime                                   comment '创建时间',
    update_by         bigint(20)      default null               comment '更新者',
    update_time       datetime                                   comment '更新时间',
    primary key (column_id)
) engine=innodb comment = '代码生成业务表字段';

-- ----------------------------
-- OSS对象存储表
-- ----------------------------
create table sys_oss (
    oss_id          bigint(20)   not null                   comment '对象存储主键',
    file_name       varchar(255) not null default ''        comment '文件名',
    original_name   varchar(255) not null default ''        comment '原名',
    file_suffix     varchar(10)  not null default ''        comment '文件后缀名',
    url             varchar(500) not null                   comment 'URL地址',
    ext1            text                  default null      comment '扩展字段',
    create_dept     bigint(20)            default null      comment '创建部门',
    create_time     datetime              default null      comment '创建时间',
    create_by       bigint(20)            default null      comment '上传人',
    update_time     datetime              default null      comment '更新时间',
    update_by       bigint(20)            default null      comment '更新人',
    service         varchar(20)  not null default 'minio'   comment '服务商',
    primary key (oss_id)
) engine=innodb comment ='OSS对象存储表';

-- ----------------------------
-- OSS对象存储动态配置表
-- ----------------------------
create table sys_oss_config (
    oss_config_id   bigint(20)    not null                  comment '主键',
    config_key      varchar(20)   not null  default ''      comment '配置key',
    access_key      varchar(255)            default ''      comment 'accessKey',
    secret_key      varchar(255)            default ''      comment '秘钥',
    bucket_name     varchar(255)            default ''      comment '桶名称',
    prefix          varchar(255)            default ''      comment '前缀',
    endpoint        varchar(255)            default ''      comment '访问站点',
    domain_url      varchar(255)            default ''      comment '自定义域名',
    is_https        char(1)                 default 'N'     comment '是否https（Y=是,N=否）',
    region          varchar(255)            default ''      comment '域',
    access_policy   char(1)       not null  default '1'     comment '桶权限类型(0=private 1=public 2=custom)',
    status          char(1)                 default 'N'     comment '是否默认（Y=是,N=否）',
    ext1            varchar(255)            default ''      comment '扩展字段',
    create_dept     bigint(20)              default null    comment '创建部门',
    create_by       bigint(20)              default null    comment '创建者',
    create_time     datetime                default null    comment '创建时间',
    update_by       bigint(20)              default null    comment '更新者',
    update_time     datetime                default null    comment '更新时间',
    remark          varchar(500)            default null    comment '备注',
    primary key (oss_config_id)
) engine=innodb comment='对象存储配置表';

insert into sys_oss_config values (1761900000000000001, 'minio', 'innolux', 'innolux123', 'innolux-wms', '', '127.0.0.1:9000', '', 'N', '', '1', 'Y', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), null);
insert into sys_oss_config values (1761900000000000002, 'qiniu', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'innolux-wms', '', 's3-cn-north-1.qiniucs.com', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), null);
insert into sys_oss_config values (1761900000000000003, 'aliyun', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'innolux-wms', '', 'oss-cn-beijing.aliyuncs.com', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), null);
insert into sys_oss_config values (1761900000000000004, 'qcloud', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'innolux-wms-1240000000', '', 'cos.ap-beijing.myqcloud.com', '', 'N', 'ap-beijing', '1', 'N', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), null);
insert into sys_oss_config values (1761900000000000005, 'image', 'innolux', 'innolux123', 'innolux-wms', 'image', '127.0.0.1:9000', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), null);

-- ----------------------------
-- 系统授权表
-- ----------------------------
create table sys_client (
    id                  bigint(20)    not null            comment 'id',
    client_id           varchar(64)   default null        comment '客户端id',
    client_key          varchar(32)   default null        comment '客户端key',
    client_secret       varchar(255)  default null        comment '客户端秘钥',
    grant_type          varchar(255)  default null        comment '授权类型',
    device_type         varchar(32)   default null        comment '设备类型',
    access_path         varchar(2000) default null        comment '允许访问路径',
    ip_whitelist        varchar(1000) default null        comment 'IP白名单',
    active_timeout      int(11)       default 1800        comment 'token活跃超时时间',
    timeout             int(11)       default 604800      comment 'token固定超时',
    status              char(1)       default '0'         comment '状态（0正常 1停用）',
    del_flag            char(1)       default '0'         comment '删除标志（0代表存在 1代表删除）',
    create_dept         bigint(20)    default null        comment '创建部门',
    create_by           bigint(20)    default null        comment '创建者',
    create_time         datetime      default null        comment '创建时间',
    update_by           bigint(20)    default null        comment '更新者',
    update_time         datetime      default null        comment '更新时间',
    primary key (id)
) engine=innodb comment='系统授权表';

insert into sys_client values (1762000000000000001, 'e5cd7e4891bf95d1d19206ce24a7b32e', 'pc', 'pc123', 'password,social', 'pc', null, null, 1800, 604800, 0, 0, 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate());
insert into sys_client values (1762000000000000002, '428a8310cd442757ae699df5d894f051', 'app', 'app123', 'password,sms,social', 'android', '/app/**', null, 1800, 604800, 0, 0, 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate());


CREATE TABLE test_demo
(
    id          bigint(0)    NOT NULL COMMENT '主键',
    dept_id     bigint(0)    NULL DEFAULT NULL COMMENT '部门id',
    user_id     bigint(0)    NULL DEFAULT NULL COMMENT '用户id',
    order_num   int(0)       NULL DEFAULT 0 COMMENT '排序号',
    test_key    varchar(255) NULL DEFAULT NULL COMMENT 'key键',
    value       varchar(255) NULL DEFAULT NULL COMMENT '值',
    version     int(0)       NULL DEFAULT 0 COMMENT '版本',
    create_dept bigint(0)    NULL DEFAULT NULL COMMENT '创建部门',
    create_time datetime(0)  NULL DEFAULT NULL COMMENT '创建时间',
    create_by   bigint(0)    NULL DEFAULT NULL COMMENT '创建人',
    update_time datetime(0)  NULL DEFAULT NULL COMMENT '更新时间',
    update_by   bigint(0)    NULL DEFAULT NULL COMMENT '更新人',
    del_flag    int(0)       NULL DEFAULT 0 COMMENT '删除标志',
    PRIMARY KEY (id) USING BTREE
) ENGINE = InnoDB COMMENT = '测试单表';

CREATE TABLE test_tree
(
    id          bigint(0)    NOT NULL COMMENT '主键',
    parent_id   bigint(0)    NULL DEFAULT 0 COMMENT '父id',
    dept_id     bigint(0)    NULL DEFAULT NULL COMMENT '部门id',
    user_id     bigint(0)    NULL DEFAULT NULL COMMENT '用户id',
    tree_name   varchar(255) NULL DEFAULT NULL COMMENT '值',
    version     int(0)       NULL DEFAULT 0 COMMENT '版本',
    create_dept bigint(0)    NULL DEFAULT NULL COMMENT '创建部门',
    create_time datetime(0)  NULL DEFAULT NULL COMMENT '创建时间',
    create_by   bigint(0)    NULL DEFAULT NULL COMMENT '创建人',
    update_time datetime(0)  NULL DEFAULT NULL COMMENT '更新时间',
    update_by   bigint(0)    NULL DEFAULT NULL COMMENT '更新人',
    del_flag    int(0)       NULL DEFAULT 0 COMMENT '删除标志',
    PRIMARY KEY (id) USING BTREE
) ENGINE = InnoDB COMMENT = '测试树表';

INSERT INTO test_demo VALUES (1762100000000000001, 1761000000000000102, 1761100000000000004, 1, '测试数据权限', '测试', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000002, 1761000000000000102, 1761100000000000003, 2, '子节点1', '111', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000003, 1761000000000000102, 1761100000000000003, 3, '子节点2', '222', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000004, 1761000000000000108, 1761100000000000004, 4, '测试数据', 'demo', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000005, 1761000000000000108, 1761100000000000003, 13, '子节点11', '1111', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000006, 1761000000000000108, 1761100000000000003, 12, '子节点22', '2222', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000007, 1761000000000000108, 1761100000000000003, 11, '子节点33', '3333', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000008, 1761000000000000108, 1761100000000000003, 10, '子节点44', '4444', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000009, 1761000000000000108, 1761100000000000003, 9, '子节点55', '5555', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000010, 1761000000000000108, 1761100000000000003, 8, '子节点66', '6666', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000011, 1761000000000000108, 1761100000000000003, 7, '子节点77', '7777', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000012, 1761000000000000108, 1761100000000000003, 6, '子节点88', '8888', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000013, 1761000000000000108, 1761100000000000003, 5, '子节点99', '9999', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);

INSERT INTO test_tree VALUES (1762200000000000001, 0, 1761000000000000102, 1761100000000000004, '测试数据权限', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000002, 1762200000000000001, 1761000000000000102, 1761100000000000003, '子节点1', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000003, 1762200000000000002, 1761000000000000102, 1761100000000000003, '子节点2', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000004, 0, 1761000000000000108, 1761100000000000004, '测试树1', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000005, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点11', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000006, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点22', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000007, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点33', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000008, 1762200000000000005, 1761000000000000108, 1761100000000000003, '子节点44', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000009, 1762200000000000006, 1761000000000000108, 1761100000000000003, '子节点55', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000010, 1762200000000000007, 1761000000000000108, 1761100000000000003, '子节点66', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000011, 1762200000000000007, 1761000000000000108, 1761100000000000003, '子节点77', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000012, 1762200000000000010, 1761000000000000108, 1761100000000000003, '子节点88', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000013, 1762200000000000010, 1761000000000000108, 1761100000000000003, '子节点99', 0, 1761000000000000103, sysdate(), 1761100000000000001, NULL, NULL, 0);



-- ===================================================
-- 任务调度数据
-- Segment: 任务调度初始化
-- ===================================================

SET NAMES utf8mb4;

CREATE TABLE `sj_namespace`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        varchar(64)         NOT NULL COMMENT '名称',
    `unique_id`   varchar(64)         NOT NULL COMMENT '唯一id',
    `description` varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `deleted`     tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`   datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`   datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`),
    UNIQUE KEY `uk_unique_id` (`unique_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='命名空间';

INSERT INTO `sj_namespace` VALUES (1, 'Development', 'dev', '', 0, now(), now());
INSERT INTO `sj_namespace` VALUES (2, 'Production', 'prod', '', 0, now(), now());

CREATE TABLE `sj_group_config`
(
    `id`                bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`      varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`        varchar(64)         NOT NULL DEFAULT '' COMMENT '组名称',
    `description`       varchar(256)        NOT NULL DEFAULT '' COMMENT '组描述',
    `token`             varchar(64)         NOT NULL DEFAULT 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT' COMMENT 'token',
    `group_status`      tinyint(4)          NOT NULL DEFAULT 0 COMMENT '组状态 0、未启用 1、启用',
    `version`           int(11)             NOT NULL COMMENT '版本号',
    `group_partition`   int(11)             NOT NULL COMMENT '分区',
    `id_generator_mode` tinyint(4)          NOT NULL DEFAULT 1 COMMENT '唯一id生成模式 默认号段模式',
    `init_scene`        tinyint(4)          NOT NULL DEFAULT 0 COMMENT '是否初始化场景 0:否 1:是',
    `create_dt`         datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`         datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_namespace_id_group_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='组配置';

INSERT INTO `sj_group_config` VALUES (1, 'dev', 'iws_group', '', 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT', 1, 1, 0, 1, 1,  now(), now());
INSERT INTO `sj_group_config` VALUES (2, 'prod', 'iws_group', '', 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT', 1, 1, 0, 1, 1,  now(), now());

CREATE TABLE `sj_notify_config`
(
    `id`                     bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`           varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`             varchar(64)         NOT NULL COMMENT '组名称',
    `notify_name`            varchar(64)         NOT NULL DEFAULT '' COMMENT '通知名称',
    `system_task_type`       tinyint(4)          NOT NULL DEFAULT 3 COMMENT '任务类型 1. 重试任务 2. 重试回调 3、JOB任务 4、WORKFLOW任务',
    `notify_status`          tinyint(4)          NOT NULL DEFAULT 0 COMMENT '通知状态 0、未启用 1、启用',
    `recipient_ids`          varchar(128)        NOT NULL COMMENT '接收人id列表',
    `notify_threshold`       int(11)             NOT NULL DEFAULT 0 COMMENT '通知阈值',
    `notify_scene`           tinyint(4)          NOT NULL DEFAULT 0 COMMENT '通知场景',
    `rate_limiter_status`    tinyint(4)          NOT NULL DEFAULT 0 COMMENT '限流状态 0、未启用 1、启用',
    `rate_limiter_threshold` int(11)             NOT NULL DEFAULT 0 COMMENT '每秒限流阈值',
    `description`            varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `create_dt`              datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`              datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name_scene_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知配置';

CREATE TABLE `sj_notify_recipient`
(
    `id`               bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`     varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `recipient_name`   varchar(64)         NOT NULL COMMENT '接收人名称',
    `notify_type`      tinyint(4)          NOT NULL DEFAULT 0 COMMENT '通知类型 1、钉钉 2、邮件 3、企业微信 4 飞书 5 webhook',
    `notify_attribute` varchar(512)        NOT NULL COMMENT '配置属性',
    `description`      varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `create_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id` (`namespace_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='告警通知接收人';

CREATE TABLE `sj_retry_dead_letter`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`    varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`      varchar(64)         NOT NULL COMMENT '组名称',
    `group_id`        bigint(20)          NOT NULL COMMENT '组Id',
    `scene_name`      varchar(64)         NOT NULL COMMENT '场景名称',
    `scene_id`        bigint(20)          NOT NULL COMMENT '场景ID',
    `idempotent_id`   varchar(64)         NOT NULL COMMENT '幂等id',
    `biz_no`          varchar(64)         NOT NULL DEFAULT '' COMMENT '业务编号',
    `executor_name`   varchar(512)        NOT NULL DEFAULT '' COMMENT '执行器名称',
    -- jackson 兼容历史数据 预计1.8.0默认改为fury
    `serializer_name` varchar(32)         NOT NULL DEFAULT 'jackson' COMMENT '执行方法参数序列化器名称',
    `args_str`        text                NOT NULL COMMENT '执行方法参数',
    `ext_attrs`       text                NOT NULL COMMENT '扩展字段',
    `create_dt`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name_scene_name` (`namespace_id`, `group_name`, `scene_name`),
    KEY `idx_idempotent_id` (`idempotent_id`),
    KEY `idx_biz_no` (`biz_no`),
    KEY `idx_create_dt` (`create_dt`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='死信队列表';

CREATE TABLE `sj_retry`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`    varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`      varchar(64)         NOT NULL COMMENT '组名称',
    `group_id`        bigint(20)          NOT NULL COMMENT '组Id',
    `scene_name`      varchar(64)         NOT NULL COMMENT '场景名称',
    `scene_id`        bigint(20)          NOT NULL COMMENT '场景ID',
    `idempotent_id`   varchar(64)         NOT NULL COMMENT '幂等id',
    `biz_no`          varchar(64)         NOT NULL DEFAULT '' COMMENT '业务编号',
    `executor_name`   varchar(512)        NOT NULL DEFAULT '' COMMENT '执行器名称',
    `args_str`        text                NOT NULL COMMENT '执行方法参数',
    `ext_attrs`       text                NOT NULL COMMENT '扩展字段',
    -- jackson 兼容历史数据 预计1.8.0默认改为fury
    `serializer_name` varchar(32)         NOT NULL DEFAULT 'jackson' COMMENT '执行方法参数序列化器名称',
    `next_trigger_at` bigint(13)          NOT NULL COMMENT '下次触发时间',
    `retry_count`     int(11)             NOT NULL DEFAULT 0 COMMENT '重试次数',
    `retry_status`    tinyint(4)          NOT NULL DEFAULT 0 COMMENT '重试状态 0、重试中 1、成功 2、最大重试次数',
    `task_type`       tinyint(4)          NOT NULL DEFAULT 1 COMMENT '任务类型 1、重试数据 2、回调数据',
    `bucket_index`    int(11)             NOT NULL DEFAULT 0 COMMENT 'bucket',
    `parent_id`       bigint(20)          NOT NULL DEFAULT 0 COMMENT '父节点id',
    `deleted`         bigint(20)          NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_dt`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_biz_no` (`biz_no`),
    KEY `idx_idempotent_id` (`idempotent_id`),
    KEY `idx_retry_status_bucket_index` (`retry_status`, `bucket_index`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_create_dt` (`create_dt`),
    UNIQUE KEY `uk_scene_tasktype_idempotentid_deleted` (`scene_id`, `task_type`, `idempotent_id`, `deleted`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='重试信息表';

CREATE TABLE `sj_retry_task`
(
    `id`               bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`     varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`       varchar(64)         NOT NULL COMMENT '组名称',
    `scene_name`       varchar(64)         NOT NULL COMMENT '场景名称',
    `retry_id`         bigint(20)          NOT NULL COMMENT '重试信息Id',
    `ext_attrs`        text                NOT NULL COMMENT '扩展字段',
    `task_status`      tinyint(4)          NOT NULL DEFAULT 1 COMMENT '重试状态',
    `task_type`        tinyint(4)          NOT NULL DEFAULT 1 COMMENT '任务类型 1、重试数据 2、回调数据',
    `operation_reason` tinyint(4)          NOT NULL DEFAULT 0 COMMENT '操作原因',
    `client_info`      varchar(128)        DEFAULT NULL COMMENT '客户端地址 clientId#ip:port',
    `create_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_group_name_scene_name` (`namespace_id`, `group_name`, `scene_name`),
    KEY `task_status` (`task_status`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_retry_id` (`retry_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='重试任务表';

CREATE TABLE `sj_retry_task_log_message`
(
    `id`            bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`  varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`    varchar(64)         NOT NULL COMMENT '组名称',
    `retry_id`      bigint(20)          NOT NULL COMMENT '重试信息Id',
    `retry_task_id` bigint(20)          NOT NULL COMMENT '重试任务Id',
    `message`       longtext            NOT NULL COMMENT '异常信息',
    `log_num`       int(11)             NOT NULL DEFAULT 1 COMMENT '日志数量',
    `real_time`     bigint(13)          NOT NULL DEFAULT 0 COMMENT '上报时间',
    `create_dt`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name_retry_task_id` (`namespace_id`, `group_name`, `retry_task_id`),
    KEY `idx_create_dt` (`create_dt`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务调度日志信息记录表';

CREATE TABLE `sj_retry_scene_config`
(
    `id`                  bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`        varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `scene_name`          varchar(64)         NOT NULL COMMENT '场景名称',
    `group_name`          varchar(64)         NOT NULL COMMENT '组名称',
    `scene_status`        tinyint(4)          NOT NULL DEFAULT 0 COMMENT '组状态 0、未启用 1、启用',
    `max_retry_count`     int(11)             NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    `back_off`            tinyint(4)          NOT NULL DEFAULT 1 COMMENT '1、默认等级 2、固定间隔时间 3、CRON 表达式',
    `trigger_interval`    varchar(16)         NOT NULL DEFAULT '' COMMENT '间隔时长',
    `notify_ids`          varchar(128)        NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
    `deadline_request`    bigint(20) unsigned NOT NULL DEFAULT 60000 COMMENT 'Deadline Request 调用链超时 单位毫秒',
    `executor_timeout`    int(11) unsigned    NOT NULL DEFAULT 5 COMMENT '任务执行超时时间，单位秒',
    `route_key`           tinyint(4)          NOT NULL DEFAULT 4 COMMENT '路由策略',
    `block_strategy`      tinyint(4)          NOT NULL DEFAULT 1 COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行',
    `cb_status`           tinyint(4)          NOT NULL DEFAULT 0 COMMENT '回调状态 0、不开启 1、开启',
    `cb_trigger_type`     tinyint(4)          NOT NULL DEFAULT 1 COMMENT '1、默认等级 2、固定间隔时间 3、CRON 表达式',
    `cb_max_count`        int(11)             NOT NULL DEFAULT 16 COMMENT '回调的最大执行次数',
    `cb_trigger_interval` varchar(16)         NOT NULL DEFAULT '' COMMENT '回调的最大执行次数',
    `owner_id`            bigint(20)          NULL     DEFAULT NULL COMMENT '负责人id',
    `labels`              varchar(512)        NULL     DEFAULT '' COMMENT '标签',
    `description`         varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `create_dt`           datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`           datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_namespace_id_group_name_scene_name` (`namespace_id`, `group_name`, `scene_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='场景配置';

CREATE TABLE `sj_server_node`
(
    `id`           bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id` varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`   varchar(64)         NOT NULL COMMENT '组名称',
    `host_id`      varchar(64)         NOT NULL COMMENT '主机id',
    `host_ip`      varchar(64)         NOT NULL COMMENT '机器ip',
    `host_port`    int(16)             NOT NULL COMMENT '机器端口',
    `expire_at`    datetime            NOT NULL COMMENT '过期时间',
    `node_type`    tinyint(4)          NOT NULL COMMENT '节点类型 1、客户端 2、是服务端',
    `ext_attrs`    varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `labels`       varchar(512)        NULL     DEFAULT '' COMMENT '标签',
    `create_dt`    datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`    datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`),
    KEY `idx_expire_at_node_type` (`expire_at`, `node_type`),
    UNIQUE KEY `uk_host_id_host_ip` (`host_id`, `host_ip`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='服务器节点';

CREATE TABLE `sj_distributed_lock`
(
    `name`       varchar(64)         NOT NULL COMMENT '锁名称',
    `lock_until` timestamp(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '锁定时长',
    `locked_at`  timestamp(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '锁定时间',
    `locked_by`  varchar(255)        NOT NULL COMMENT '锁定者',
    `create_dt`  datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`  datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='锁定表';

CREATE TABLE `sj_system_user`
(
    `id`        bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`  varchar(64)         NOT NULL COMMENT '账号',
    `password`  varchar(128)        NOT NULL COMMENT '密码',
    `role`      tinyint(4)          NOT NULL DEFAULT 0 COMMENT '角色：1-普通用户、2-管理员',
    `create_dt` datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt` datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户表';

-- pwd: admin
INSERT INTO `sj_system_user` (username, password, role)
VALUES ('admin', '465c194afb65670f38322df087f0a9bb225cc257e43eb4ac5a0c98ef5b3173ac', 2);

CREATE TABLE `sj_system_user_permission`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `group_name`     varchar(64)         NOT NULL COMMENT '组名称',
    `namespace_id`   varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `system_user_id` bigint(20)          NOT NULL COMMENT '系统用户id',
    `create_dt`      datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`      datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_namespace_id_group_name_system_user_id` (`namespace_id`, `group_name`, `system_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户权限表';

-- 分布式调度DDL
CREATE TABLE `sj_job`
(
    `id`               bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`     varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `biz_id`           varchar(64)         NOT NULL COMMENT '业务ID',
    `group_name`       varchar(64)         NOT NULL COMMENT '组名称',
    `job_name`         varchar(64)         NOT NULL COMMENT '名称',
    `args_str`         text                         DEFAULT NULL COMMENT '执行方法参数',
    `args_type`        tinyint(4)          NOT NULL DEFAULT 1 COMMENT '参数类型 ',
    `next_trigger_at`  bigint(13)          NOT NULL COMMENT '下次触发时间',
    `job_status`       tinyint(4)          NOT NULL DEFAULT 1 COMMENT '任务状态 0、关闭、1、开启',
    `task_type`        tinyint(4)          NOT NULL DEFAULT 1 COMMENT '任务类型 1、集群 2、广播 3、切片',
    `route_key`        tinyint(4)          NOT NULL DEFAULT 4 COMMENT '路由策略',
    `executor_type`    tinyint(4)          NOT NULL DEFAULT 1 COMMENT '执行器类型',
    `executor_info`    varchar(255)                 DEFAULT NULL COMMENT '执行器名称',
    `trigger_type`     tinyint(4)          NOT NULL COMMENT '触发类型 1.CRON 表达式 2. 固定时间',
    `trigger_interval` varchar(255)        NOT NULL COMMENT '间隔时长',
    `block_strategy`   tinyint(4)          NOT NULL DEFAULT 1 COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行 4、恢复',
    `executor_timeout` int(11)             NOT NULL DEFAULT 0 COMMENT '任务执行超时时间，单位秒',
    `max_retry_times`  int(11)             NOT NULL DEFAULT 0 COMMENT '最大重试次数',
    `parallel_num`     int(11)             NOT NULL DEFAULT 1 COMMENT '并行数',
    `retry_interval`   int(11)             NOT NULL DEFAULT 0 COMMENT '重试间隔(s)',
    `bucket_index`     int(11)             NOT NULL DEFAULT 0 COMMENT 'bucket',
    `resident`         tinyint(4)          NOT NULL DEFAULT 0 COMMENT '是否是常驻任务',
    `notify_ids`       varchar(128)        NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
    `owner_id`         bigint(20)          NULL     DEFAULT NULL COMMENT '负责人id',
    `labels`           varchar(512)        NULL     DEFAULT '' COMMENT '标签',
    `description`      varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `ext_attrs`        varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `deleted`          tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`),
    KEY `idx_job_status_bucket_index` (`job_status`, `bucket_index`),
    KEY `idx_create_dt` (`create_dt`),
    UNIQUE KEY `uk_sj_job_01` (`namespace_id`, `biz_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务信息';

INSERT INTO `sj_job` VALUES (1, 'dev', 'demo-job', 'iws_group', 'demo-job', null, 1, 1710344035622, 1, 1, 4, 1, 'testJobExecutor', 2, '60', 1, 60, 3, 1, 1, 116, 0, '', 1, '','', '', 0 , now(), now());

CREATE TABLE `sj_job_log_message`
(
    `id`            bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`  varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`    varchar(64)         NOT NULL COMMENT '组名称',
    `job_id`        bigint(20)          NOT NULL COMMENT '任务信息id',
    `task_batch_id` bigint(20)          NOT NULL COMMENT '任务批次id',
    `task_id`       bigint(20)          NOT NULL COMMENT '调度任务id',
    `message`       longtext            NOT NULL COMMENT '调度信息',
    `log_num`       int(11)             NOT NULL DEFAULT 1 COMMENT '日志数量',
    `real_time`     bigint(13)          NOT NULL DEFAULT 0 COMMENT '上报时间',
    `ext_attrs`     varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `create_dt`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_batch_id_task_id` (`task_batch_id`, `task_id`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='调度日志';

CREATE TABLE `sj_job_task`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`   varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`     varchar(64)         NOT NULL COMMENT '组名称',
    `job_id`         bigint(20)          NOT NULL COMMENT '任务信息id',
    `task_batch_id`  bigint(20)          NOT NULL COMMENT '调度任务id',
    `parent_id`      bigint(20)          NOT NULL DEFAULT 0 COMMENT '父执行器id',
    `task_status`    tinyint             NOT NULL DEFAULT 0 COMMENT '执行的状态 0、失败 1、成功',
    `retry_count`    int(11)             NOT NULL DEFAULT 0 COMMENT '重试次数',
    `mr_stage`       tinyint                      DEFAULT NULL COMMENT '动态分片所处阶段 1:map 2:reduce 3:mergeReduce',
    `leaf`           tinyint             NOT NULL DEFAULT '1' COMMENT '叶子节点',
    `task_name`      varchar(255)        NOT NULL DEFAULT '' COMMENT '任务名称',
    `client_info`    varchar(128)                 DEFAULT NULL COMMENT '客户端地址 clientId#ip:port',
    `wf_context`     text                         DEFAULT NULL COMMENT '工作流全局上下文',
    `result_message` text                NOT NULL COMMENT '执行结果',
    `args_str`       text                         DEFAULT NULL COMMENT '执行方法参数',
    `args_type`      tinyint             NOT NULL DEFAULT 1 COMMENT '参数类型 ',
    `ext_attrs`      varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `create_dt`      datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`      datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_batch_id_task_status` (`task_batch_id`, `task_status`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务实例';

CREATE TABLE `sj_job_task_batch`
(
    `id`                      bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`            varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`              varchar(64)         NOT NULL COMMENT '组名称',
    `job_id`                  bigint(20)          NOT NULL COMMENT '任务id',
    `workflow_node_id`        bigint(20)          NOT NULL DEFAULT 0 COMMENT '工作流节点id',
    `parent_workflow_node_id` bigint(20)          NOT NULL DEFAULT 0 COMMENT '工作流任务父批次id',
    `workflow_task_batch_id`  bigint(20)          NOT NULL DEFAULT 0 COMMENT '工作流任务批次id',
    `task_batch_status`       tinyint(4)          NOT NULL DEFAULT 0 COMMENT '任务批次状态 0、失败 1、成功',
    `operation_reason`        tinyint(4)          NOT NULL DEFAULT 0 COMMENT '操作原因',
    `execution_at`            bigint(13)          NOT NULL DEFAULT 0 COMMENT '任务执行时间',
    `system_task_type`        tinyint(4)          NOT NULL DEFAULT 3 COMMENT '任务类型 3、JOB任务 4、WORKFLOW任务',
    `parent_id`               varchar(64)         NOT NULL DEFAULT '' COMMENT '父节点',
    `ext_attrs`               varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `deleted`                 tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`               datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`               datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id_task_batch_status` (`job_id`, `task_batch_status`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`),
    KEY `idx_workflow_task_batch_id_workflow_node_id` (`workflow_task_batch_id`, `workflow_node_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务批次';

CREATE TABLE `sj_job_summary`
(
    `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`     VARCHAR(64)     NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`       VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '组名称',
    `business_id`      bigint          NOT NULL COMMENT '业务id (job_id或workflow_id)',
    `system_task_type` tinyint(4)      NOT NULL DEFAULT 3 COMMENT '任务类型 3、JOB任务 4、WORKFLOW任务',
    `trigger_at`       datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计时间',
    `success_num`      int             NOT NULL DEFAULT 0 COMMENT '执行成功-日志数量',
    `fail_num`         int             NOT NULL DEFAULT 0 COMMENT '执行失败-日志数量',
    `fail_reason`      varchar(512)    NOT NULL DEFAULT '' COMMENT '失败原因',
    `stop_num`         int             NOT NULL DEFAULT 0 COMMENT '执行失败-日志数量',
    `stop_reason`      varchar(512)    NOT NULL DEFAULT '' COMMENT '失败原因',
    `cancel_num`       int             NOT NULL DEFAULT 0 COMMENT '执行失败-日志数量',
    `cancel_reason`    varchar(512)    NOT NULL DEFAULT '' COMMENT '失败原因',
    `create_dt`        datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`        datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name_business_id` (`namespace_id`, `group_name`, business_id),
    UNIQUE KEY `uk_trigger_at_system_task_type_business_id` (`trigger_at`, `system_task_type`, `business_id`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4 COMMENT ='DashBoard_Job';

CREATE TABLE `sj_retry_summary`
(
    `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`  VARCHAR(64)     NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '组名称',
    `scene_name`    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '场景名称',
    `trigger_at`    datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计时间',
    `running_num`   int             NOT NULL DEFAULT 0 COMMENT '重试中-日志数量',
    `finish_num`    int             NOT NULL DEFAULT 0 COMMENT '重试完成-日志数量',
    `max_count_num` int             NOT NULL DEFAULT 0 COMMENT '重试到达最大次数-日志数量',
    `suspend_num`   int             NOT NULL DEFAULT 0 COMMENT '暂停重试-日志数量',
    `create_dt`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_trigger_at` (`trigger_at`),
    UNIQUE KEY `uk_scene_name_trigger_at` (`namespace_id`, `group_name`, `scene_name`, `trigger_at`) USING BTREE
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4 COMMENT ='DashBoard_Retry';

CREATE TABLE `sj_workflow`
(
    `id`               bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workflow_name`    varchar(64)         NOT NULL COMMENT '工作流名称',
    `namespace_id`     varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `biz_id`           varchar(64)         NOT NULL COMMENT '业务ID',
    `group_name`       varchar(64)         NOT NULL COMMENT '组名称',
    `workflow_status`  tinyint(4)          NOT NULL DEFAULT 1 COMMENT '工作流状态 0、关闭、1、开启',
    `trigger_type`     tinyint(4)          NOT NULL COMMENT '触发类型 1.CRON 表达式 2. 固定时间',
    `trigger_interval` varchar(255)        NOT NULL COMMENT '间隔时长',
    `next_trigger_at`  bigint              NOT NULL COMMENT '下次触发时间',
    `block_strategy`   tinyint(4)          NOT NULL DEFAULT 1 COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行',
    `executor_timeout` int(11)             NOT NULL DEFAULT 0 COMMENT '任务执行超时时间，单位秒',
    `description`      varchar(256)        NOT NULL DEFAULT '' COMMENT '描述',
    `flow_info`        text                         DEFAULT NULL COMMENT '流程信息',
    `wf_context`       text                         DEFAULT NULL COMMENT '上下文',
    `notify_ids`       varchar(128)        NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
    `bucket_index`     int(11)             NOT NULL DEFAULT 0 COMMENT 'bucket',
    `version`          int(11)             NOT NULL COMMENT '版本号',
    `owner_id`         bigint(20)          NULL     DEFAULT NULL COMMENT '负责人id',
    `ext_attrs`        varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `deleted`          tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`        datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`),
    UNIQUE KEY `uk_sj_workflow_01` (`namespace_id`, `biz_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流';

CREATE TABLE `sj_workflow_node`
(
    `id`                   bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`         varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `node_name`            varchar(64)         NOT NULL COMMENT '节点名称',
    `group_name`           varchar(64)         NOT NULL COMMENT '组名称',
    `job_id`               bigint(20)          NOT NULL COMMENT '任务信息id',
    `workflow_id`          bigint(20)          NOT NULL COMMENT '工作流ID',
    `node_type`            tinyint(4)          NOT NULL DEFAULT 1 COMMENT '1、任务节点 2、条件节点',
    `expression_type`      tinyint(4)          NOT NULL DEFAULT 0 COMMENT '1、SpEl、2、Aviator 3、QL',
    `fail_strategy`        tinyint(4)          NOT NULL DEFAULT 1 COMMENT '失败策略 1、跳过 2、阻塞',
    `workflow_node_status` tinyint(4)          NOT NULL DEFAULT 1 COMMENT '工作流节点状态 0、关闭、1、开启',
    `priority_level`       int(11)             NOT NULL DEFAULT 1 COMMENT '优先级',
    `node_info`            text                         DEFAULT NULL COMMENT '节点信息 ',
    `version`              int(11)             NOT NULL COMMENT '版本号',
    `ext_attrs`            varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `deleted`              tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`            datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`            datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流节点';

CREATE TABLE `sj_workflow_task_batch`
(
    `id`                bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`      varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`        varchar(64)         NOT NULL COMMENT '组名称',
    `workflow_id`       bigint(20)          NOT NULL COMMENT '工作流任务id',
    `task_batch_status` tinyint(4)          NOT NULL DEFAULT 0 COMMENT '任务批次状态 0、失败 1、成功',
    `operation_reason`  tinyint(4)          NOT NULL DEFAULT 0 COMMENT '操作原因',
    `flow_info`         text                         DEFAULT NULL COMMENT '流程信息',
    `wf_context`        text                         DEFAULT NULL COMMENT '全局上下文',
    `execution_at`      bigint(13)          NOT NULL DEFAULT 0 COMMENT '任务执行时间',
    `ext_attrs`         varchar(256)        NULL     DEFAULT '' COMMENT '扩展字段',
    `version`           int(11)             NOT NULL DEFAULT 1 COMMENT '版本号',
    `deleted`           tinyint(4)          NOT NULL DEFAULT 0 COMMENT '逻辑删除 1、删除',
    `create_dt`         datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`         datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id_task_batch_status` (`workflow_id`, `task_batch_status`),
    KEY `idx_create_dt` (`create_dt`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流批次';

CREATE TABLE `sj_job_executor`
(
    `id`            bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `namespace_id`  varchar(64)         NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
    `group_name`    varchar(64)         NOT NULL COMMENT '组名称',
    `executor_info` varchar(256)        NOT NULL COMMENT '任务执行器名称',
    `executor_type` varchar(3)          NOT NULL COMMENT '1:java 2:python 3:go',
    `create_dt`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_dt`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_namespace_id_group_name` (`namespace_id`, `group_name`),
    KEY `idx_create_dt` (`create_dt`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务执行器信息';



-- ===================================================
-- 工作流数据
-- Segment: 工作流初始化
-- ===================================================

-- ----------------------------
-- 0、warm-flow-all.sql，地址：https://gitee.com/Iws/warm-flow/blob/master/sql/mysql/warm-flow-all.sql
-- ----------------------------
CREATE TABLE `flow_definition`
(
    `id`              bigint          NOT NULL COMMENT '主键id',
    `flow_code`       varchar(40)     NOT NULL COMMENT '流程编码',
    `flow_name`       varchar(100)    NOT NULL COMMENT '流程名称',
    `model_value`     varchar(40)     NOT NULL DEFAULT 'CLASSICS' COMMENT '设计器模型（CLASSICS经典模型 MIMIC仿钉钉模型）',
    `category`        varchar(100)             DEFAULT NULL COMMENT '流程类别',
    `version`         varchar(20)     NOT NULL COMMENT '流程版本',
    `is_publish`      tinyint(1)      NOT NULL DEFAULT '0' COMMENT '是否发布（0未发布 1已发布 9失效）',
    `form_custom`     char(1)                  DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
    `form_path`       varchar(100)             DEFAULT NULL COMMENT '审批表单路径',
    `activity_status` tinyint(1)      NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
    `listener_type`   varchar(100)             DEFAULT NULL COMMENT '监听器类型',
    `listener_path`   varchar(400)             DEFAULT NULL COMMENT '监听器路径',
    `ext`             varchar(500)             DEFAULT NULL COMMENT '业务详情 存业务表对象json字符串',
    `create_time`     datetime                 DEFAULT NULL COMMENT '创建时间',
    `create_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `update_time`     datetime                 DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `del_flag`        char(1)                  DEFAULT '0' COMMENT '删除标志',
    `tenant_id`       varchar(40)              DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='流程定义表';

CREATE TABLE `flow_node`
(
    `id`              bigint        NOT NULL COMMENT '主键id',
    `node_type`       tinyint(1)      NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `definition_id`   bigint          NOT NULL COMMENT '流程定义id',
    `node_code`       varchar(100)    NOT NULL COMMENT '流程节点编码',
    `node_name`       varchar(100)  DEFAULT NULL COMMENT '流程节点名称',
    `permission_flag` varchar(200)  DEFAULT NULL COMMENT '权限标识（权限类型:权限标识，可以多个，用@@隔开)',
    `node_ratio`      varchar(200)  DEFAULT NULL COMMENT '流程签署比例值',
    `coordinate`      varchar(100)  DEFAULT NULL COMMENT '坐标',
    `any_node_skip`   varchar(100)  DEFAULT NULL COMMENT '任意结点跳转',
    `listener_type`   varchar(100)  DEFAULT NULL COMMENT '监听器类型',
    `listener_path`   varchar(400)  DEFAULT NULL COMMENT '监听器路径',
    `form_custom`     char(1)       DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
    `form_path`       varchar(100)  DEFAULT NULL COMMENT '审批表单路径',
    `version`         varchar(20)     NOT NULL COMMENT '版本',
    `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
    `create_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `update_time`     datetime      DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `ext`             text          COMMENT '节点扩展属性',
    `del_flag`        char(1)       DEFAULT '0' COMMENT '删除标志',
    `tenant_id`       varchar(40)   DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='流程节点表';

CREATE TABLE `flow_skip`
(
    `id`             bigint       NOT NULL COMMENT '主键id',
    `definition_id`  bigint          NOT NULL COMMENT '流程定义id',
    `now_node_code`  varchar(100)    NOT NULL COMMENT '当前流程节点的编码',
    `now_node_type`  tinyint(1)   DEFAULT NULL COMMENT '当前节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `next_node_code` varchar(100)    NOT NULL COMMENT '下一个流程节点的编码',
    `next_node_type` tinyint(1)   DEFAULT NULL COMMENT '下一个节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `skip_name`      varchar(100) DEFAULT NULL COMMENT '跳转名称',
    `skip_type`      varchar(40)  DEFAULT NULL COMMENT '跳转类型（PASS审批通过 REJECT退回）',
    `skip_condition` varchar(200) DEFAULT NULL COMMENT '跳转条件',
    `coordinate`     varchar(100) DEFAULT NULL COMMENT '坐标',
    `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
    `create_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `del_flag`       char(1)      DEFAULT '0' COMMENT '删除标志',
    `tenant_id`      varchar(40)  DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='节点跳转关联表';

CREATE TABLE `flow_instance`
(
    `id`              bigint      NOT NULL COMMENT '主键id',
    `definition_id`   bigint      NOT NULL COMMENT '对应flow_definition表的id',
    `business_id`     varchar(40) NOT NULL COMMENT '业务id',
    `node_type`       tinyint(1)  NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `node_code`       varchar(40) NOT NULL COMMENT '流程节点编码',
    `node_name`       varchar(100)         DEFAULT NULL COMMENT '流程节点名称',
    `variable`        text COMMENT '任务变量',
    `flow_status`     varchar(20) NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
    `activity_status` tinyint(1)  NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
    `def_json`        text COMMENT '流程定义json',
    `create_time`     datetime             DEFAULT NULL COMMENT '创建时间',
    `create_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `update_time`     datetime             DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `ext`             varchar(500)         DEFAULT NULL COMMENT '扩展字段，预留给业务系统使用',
    `del_flag`        char(1)              DEFAULT '0' COMMENT '删除标志',
    `tenant_id`       varchar(40)          DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='流程实例表';

CREATE TABLE `flow_task`
(
    `id`            bigint       NOT NULL COMMENT '主键id',
    `definition_id` bigint       NOT NULL COMMENT '对应flow_definition表的id',
    `instance_id`   bigint       NOT NULL COMMENT '对应flow_instance表的id',
    `node_code`     varchar(100) NOT NULL COMMENT '节点编码',
    `node_name`     varchar(100) DEFAULT NULL COMMENT '节点名称',
    `node_type`     tinyint(1)   NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `flow_status`   varchar(20)  NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
    `form_custom`   char(1)      DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
    `form_path`     varchar(100) DEFAULT NULL COMMENT '审批表单路径',
    `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
    `create_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `del_flag`      char(1)      DEFAULT '0' COMMENT '删除标志',
    `tenant_id`     varchar(40)  DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='待办任务表';

CREATE TABLE `flow_his_task`
(
    `id`               bigint(20)                   NOT NULL COMMENT '主键id',
    `definition_id`    bigint(20)                   NOT NULL COMMENT '对应flow_definition表的id',
    `instance_id`      bigint(20)                   NOT NULL COMMENT '对应flow_instance表的id',
    `task_id`          bigint(20)                   NOT NULL COMMENT '对应flow_task表的id',
    `node_code`        varchar(100)                 DEFAULT NULL COMMENT '开始节点编码',
    `node_name`        varchar(100)                 DEFAULT NULL COMMENT '开始节点名称',
    `node_type`        tinyint(1)                   DEFAULT NULL COMMENT '开始节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
    `target_node_code` varchar(200)                 DEFAULT NULL COMMENT '目标节点编码',
    `target_node_name` varchar(200)                 DEFAULT NULL COMMENT '结束节点名称',
    `approver`         varchar(40)                  DEFAULT NULL COMMENT '审批人',
    `cooperate_type`   tinyint(1)                   NOT NULL DEFAULT '0' COMMENT '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)',
    `collaborator`     varchar(500)                 DEFAULT NULL COMMENT '协作人',
    `skip_type`        varchar(10)                  NOT NULL COMMENT '流转类型（PASS通过 REJECT退回 NONE无动作）',
    `flow_status`      varchar(20)                  NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
    `form_custom`      char(1)                      DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
    `form_path`        varchar(100)                 DEFAULT NULL COMMENT '审批表单路径',
    `message`          varchar(500)                 DEFAULT NULL COMMENT '审批意见',
    `variable`         TEXT                         DEFAULT NULL COMMENT '任务变量',
    `ext`              TEXT                         DEFAULT NULL COMMENT '业务详情 存业务表对象json字符串',
    `create_time`      datetime                     DEFAULT NULL COMMENT '任务开始时间',
    `update_time`      datetime                     DEFAULT NULL COMMENT '审批完成时间',
    `del_flag`         char(1)                      DEFAULT '0' COMMENT '删除标志',
    `tenant_id`        varchar(40)                  DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB COMMENT ='历史任务记录表';


CREATE TABLE `flow_user`
(
    `id`           bigint      NOT NULL COMMENT '主键id',
    `type`         char(1)         NOT NULL COMMENT '人员类型（1待办任务的审批人权限 2待办任务的转办人权限 3待办任务的委托人权限）',
    `processed_by` varchar(80) DEFAULT NULL COMMENT '权限人',
    `associated`   bigint          NOT NULL COMMENT '任务表id',
    `create_time`  datetime    DEFAULT NULL COMMENT '创建时间',
    `create_by`    varchar(80) DEFAULT NULL COMMENT '创建人',
    `update_time`  datetime    DEFAULT NULL COMMENT '更新时间',
    `update_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `del_flag`     char(1)     DEFAULT '0' COMMENT '删除标志',
    `tenant_id`    varchar(40) DEFAULT NULL COMMENT '租户id',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `user_processed_type` (`processed_by`, `type`),
    KEY `user_associated` (`associated`) USING BTREE
) ENGINE = InnoDB COMMENT ='流程用户表';

-- ----------------------------
-- 流程分类表
-- ----------------------------
create table flow_category
(
    category_id   bigint(20)  not null comment '流程分类ID',
    parent_id     bigint(20)   default 0 comment '父流程分类id',
    ancestors     varchar(500) default '' comment '祖级列表',
    category_name varchar(30) not null comment '流程分类名称',
    order_num     int(4)       default 0 comment '显示顺序',
    del_flag      char(1)      default '0' comment '删除标志（0代表存在 1代表删除）',
    create_dept   bigint(20)  null comment '创建部门',
    create_by     bigint(20)  null comment '创建者',
    create_time   datetime    null comment '创建时间',
    update_by     bigint(20)  null comment '更新者',
    update_time   datetime    null comment '更新时间',
    primary key (category_id)
) engine = innodb comment = '流程分类';

INSERT INTO flow_category values (1762300000000000100, 0, '0', 'OA审批', 0, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000101, 1762300000000000100, '0,1762300000000000100', '假勤管理', 0, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000102, 1762300000000000100, '0,1762300000000000100', '人事管理', 1, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000103, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '请假', 0, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000104, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '出差', 1, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000105, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '加班', 2, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000106, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '换班', 3, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000107, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '外出', 4, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000108, 1762300000000000102, '0,1762300000000000100,1762300000000000102', '转正', 1, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);
INSERT INTO flow_category values (1762300000000000109, 1762300000000000102, '0,1762300000000000100,1762300000000000102', '离职', 2, '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null);

-- ----------------------------
-- 流程spel表达式定义表
-- ----------------------------

CREATE TABLE flow_spel (
    id bigint(20) NOT NULL COMMENT '主键id',
    component_name varchar(255) DEFAULT NULL COMMENT '组件名称',
    method_name varchar(255) DEFAULT NULL COMMENT '方法名',
    method_params varchar(255) DEFAULT NULL COMMENT '参数',
    view_spel varchar(255) DEFAULT NULL COMMENT '预览spel表达式',
    remark varchar(255) DEFAULT NULL COMMENT '备注',
    status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
    del_flag char(1) DEFAULT '0' COMMENT '删除标志',
    create_dept bigint(20) DEFAULT NULL COMMENT '创建部门',
    create_by bigint(20) DEFAULT NULL COMMENT '创建者',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_by bigint(20) DEFAULT NULL COMMENT '更新者',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT='流程spel表达式定义表';

INSERT INTO flow_spel VALUES (1762400000000000001, 'spelRuleComponent', 'selectDeptLeaderById', 'initiatorDeptId', '#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}', '根据部门id获取部门负责人', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate());
INSERT INTO flow_spel VALUES (1762400000000000002, NULL, NULL, 'initiator', '${initiator}', '流程发起人', '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate());

-- ----------------------------
-- 流程实例业务扩展表
-- ----------------------------

create table flow_instance_biz_ext (
    id             bigint                       not null comment '主键id',
    create_dept    bigint                       null comment '创建部门',
    create_by      bigint                       null comment '创建者',
    create_time    datetime                     null comment '创建时间',
    update_by      bigint                       null comment '更新者',
    update_time    datetime                     null comment '更新时间',
    business_code  varchar(255)                 null comment '业务编码',
    business_title varchar(1000)                null comment '业务标题',
    del_flag       char        default '0'      null comment '删除标志（0代表存在 1代表删除）',
    instance_id    bigint                       null comment '流程实例Id',
    business_id    varchar(255)                 null comment '业务Id',
    PRIMARY KEY (id)
)  ENGINE = InnoDB COMMENT '流程实例业务扩展表';

-- ----------------------------
-- 请假单信息
-- ----------------------------

create table test_leave
(
    id          bigint(20)   not null comment 'id',
    apply_code  varchar(50)  not null comment '申请编号',
    leave_type  varchar(255) not null comment '请假类型',
    start_date  datetime     not null comment '开始时间',
    end_date    datetime     not null comment '结束时间',
    leave_days  int(10)      not null comment '请假天数',
    remark      varchar(255) null comment '请假原因',
    status      varchar(255) null comment '状态',
    create_dept bigint       null comment '创建部门',
    create_by   bigint       null comment '创建者',
    create_time datetime     null comment '创建时间',
    update_by   bigint       null comment '更新者',
    update_time datetime     null comment '更新时间',
    PRIMARY KEY (id) USING BTREE
) ENGINE = InnoDB COMMENT = '请假申请表';

insert into sys_menu values (1761400000000011616, '工作流', 0, 6, 'workflow', '', '', 'N', 'Y', 'M', '0', '0', '', 'workflow', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011618, '我的任务', 0, 7, 'task', '', '', 'N', 'Y', 'M', '0', '0', '', 'my-task', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011619, '我的待办', 1761400000000011618, 2, 'taskWaiting', 'workflow/task/taskWaiting', '', 'N', 'N', 'C', '0', '0', '', 'waiting', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011632, '我的已办', 1761400000000011618, 3, 'taskFinish', 'workflow/task/taskFinish', '', 'N', 'N', 'C', '0', '0', '', 'finish', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011633, '我的抄送', 1761400000000011618, 4, 'taskCopyList', 'workflow/task/taskCopyList', '', 'N', 'N', 'C', '0', '0', '', 'my-copy', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011620, '流程定义', 1761400000000011616, 3, 'processDefinition', 'workflow/processDefinition/index', '', 'N', 'N', 'C', '0', '0', 'workflow:definition:list', 'process-definition', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011621, '流程实例', 1761400000000011630, 1, 'processInstance', 'workflow/processInstance/index', '', 'N', 'N', 'C', '0', '0', 'workflow:instance:list', 'tree-table', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011622, '流程分类', 1761400000000011616, 1, 'category', 'workflow/category/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:category:list', 'category', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011801, '流程表达式', 1761400000000011616, 2, 'spel', 'workflow/spel/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:spel:list', 'input', '', '', 1761000000000000103, 1761100000000000001, sysdate(), 1761100000000000001, sysdate(), '流程达式定义菜单');
insert into sys_menu values (1761400000000011629, '我发起的', 1761400000000011618, 1, 'myDocument', 'workflow/task/myDocument', '', 'N', 'N', 'C', '0', '0', 'workflow:instance:currentList', 'guide', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011630, '流程监控', 1761400000000011616, 4, 'processMonitor', '', '', 'N', 'Y', 'M', '0', '0', '', 'monitor', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011631, '待办任务', 1761400000000011630, 2, 'allTaskWaiting', 'workflow/task/allTaskWaiting', '', 'N', 'N', 'C', '0', '0', 'workflow:task:list', 'waiting', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000011660, '待办任务修改', 1761400000000011631, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:task:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011700, '流程设计', 1761400000000011616, 5, 'design/index', 'workflow/processDefinition/design', '', 'N', 'N', 'C', '1', '0', 'workflow:leave:edit', '#', '/workflow/processDefinition', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011701, '请假申请', 1761400000000011616, 6, 'leaveEdit/index', 'workflow/leave/leaveEdit', '', 'N', 'N', 'C', '1', '0', 'workflow:leave:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
-- 流程分类管理相关按钮
insert into sys_menu values (1761400000000011623, '流程分类查询', 1761400000000011622, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011624, '流程分类新增', 1761400000000011622, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011625, '流程分类修改', 1761400000000011622, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011626, '流程分类删除', 1761400000000011622, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011627, '流程分类导出', 1761400000000011622, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 流程实例管理相关按钮
insert into sys_menu values (1761400000000011653, '流程实例查询', 1761400000000011621, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011654, '流程变量查询', 1761400000000011621, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:variableQuery', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011655, '流程变量修改', 1761400000000011621, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:variable', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011656, '流程实例激活/挂起', 1761400000000011621, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:active', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011657, '流程实例删除', 1761400000000011621, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011658, '流程实例作废', 1761400000000011621, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:invalid', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011659, '流程实例撤销', 1761400000000011621, 7, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:cancel', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 流程定义管理相关按钮
insert into sys_menu values (1761400000000011644, '流程定义查询', 1761400000000011620, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011645, '流程定义新增', 1761400000000011620, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011646, '流程定义修改', 1761400000000011620, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011647, '流程定义删除', 1761400000000011620, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011648, '流程定义导出', 1761400000000011620, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011649, '流程定义导入', 1761400000000011620, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:import', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011650, '流程定义发布/取消发布', 1761400000000011620, 7, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:publish', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011651, '流程定义复制', 1761400000000011620, 8, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:copy', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values (1761400000000011652, '流程定义激活/挂起', 1761400000000011620, 9, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:active', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 流程表达式管理相关按钮
INSERT INTO sys_menu VALUES (1761400000000011802, '流程达式定义查询', 1761400000000011801, 1, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011803, '流程达式定义新增', 1761400000000011801, 2, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011804, '流程达式定义修改', 1761400000000011801, 3, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011805, '流程达式定义删除', 1761400000000011801, 4, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011806, '流程达式定义导出', 1761400000000011801, 5, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
-- 请假测试相关按钮
insert into sys_menu VALUES (1761400000000011638, '请假申请', 1761400000000000005, 1, 'leave', 'workflow/leave/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:leave:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '请假申请菜单');
insert into sys_menu VALUES (1761400000000011639, '请假申请查询', 1761400000000011638, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu VALUES (1761400000000011640, '请假申请新增', 1761400000000011638, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu VALUES (1761400000000011641, '请假申请修改', 1761400000000011638, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu VALUES (1761400000000011642, '请假申请删除', 1761400000000011638, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');
insert into sys_menu VALUES (1761400000000011643, '请假申请导出', 1761400000000011638, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:export', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '');

INSERT INTO sys_dict_type VALUES (1761500000000000013, '业务状态', 'wf_business_status', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '业务状态列表');
INSERT INTO sys_dict_type VALUES (1761500000000000014, '表单类型', 'wf_form_type', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '表单类型列表');
INSERT INTO sys_dict_type VALUES (1761500000000000015, '任务状态', 'wf_task_status', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '任务状态');
INSERT INTO sys_dict_data VALUES (1761600000000000039, 1, '已撤销', 'cancel', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '已撤销');
INSERT INTO sys_dict_data VALUES (1761600000000000040, 2, '草稿', 'draft', 'wf_business_status', '', 'info', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '草稿');
INSERT INTO sys_dict_data VALUES (1761600000000000041, 3, '待审核', 'waiting', 'wf_business_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '待审核');
INSERT INTO sys_dict_data VALUES (1761600000000000042, 4, '已完成', 'finish', 'wf_business_status', '', 'success', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '已完成');
INSERT INTO sys_dict_data VALUES (1761600000000000043, 5, '已作废', 'invalid', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '已作废');
INSERT INTO sys_dict_data VALUES (1761600000000000044, 6, '已退回', 'back', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '已退回');
INSERT INTO sys_dict_data VALUES (1761600000000000045, 7, '已终止', 'termination', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '已终止');
INSERT INTO sys_dict_data VALUES (1761600000000000046, 1, '自定义表单', 'static', 'wf_form_type', '', 'success', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '自定义表单');
INSERT INTO sys_dict_data VALUES (1761600000000000047, 2, '动态表单', 'dynamic', 'wf_form_type', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '动态表单');
INSERT INTO sys_dict_data VALUES (1761600000000000048, 1, '撤销', 'cancel', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '撤销');
INSERT INTO sys_dict_data VALUES (1761600000000000049, 2, '通过', 'pass', 'wf_task_status', '', 'success', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '通过');
INSERT INTO sys_dict_data VALUES (1761600000000000050, 3, '待审核', 'waiting', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '待审核');
INSERT INTO sys_dict_data VALUES (1761600000000000051, 4, '作废', 'invalid', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '作废');
INSERT INTO sys_dict_data VALUES (1761600000000000052, 5, '退回', 'back', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '退回');
INSERT INTO sys_dict_data VALUES (1761600000000000053, 6, '终止', 'termination', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '终止');
INSERT INTO sys_dict_data VALUES (1761600000000000054, 7, '转办', 'transfer', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '转办');
INSERT INTO sys_dict_data VALUES (1761600000000000055, 8, '委托', 'depute', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '委托');
INSERT INTO sys_dict_data VALUES (1761600000000000056, 9, '抄送', 'copy', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '抄送');
INSERT INTO sys_dict_data VALUES (1761600000000000057, 10, '加签', 'sign', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '加签');
INSERT INTO sys_dict_data VALUES (1761600000000000058, 11, '减签', 'sign_off', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '减签');
INSERT INTO sys_dict_data VALUES (1761600000000000059, 11, '超时', 'timeout', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, sysdate(), NULL, NULL, '超时');



-- ===================================================
-- AI 扩展数据
-- Segment: AI 扩展初始化
-- ===================================================

-- ============================================================
-- Snail AI MySQL 全量建表脚本（仅 CREATE，无 ALTER）
-- 使用：mysql -u user -p database < iws_init.sql
-- ============================================================


-- ============================================================
-- 一、用户与权限
-- ============================================================

-- 1.1 用户表
CREATE TABLE sai_user
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    role           INT,
    totals         INT,
    username       VARCHAR(255),
    nickname       VARCHAR(128)         DEFAULT NULL COMMENT '用户昵称',
    email          VARCHAR(64),
    password       VARCHAR(255) NOT NULL,
    resource_id    BIGINT               DEFAULT NULL COMMENT '头像资源ID，关联 sai_resource.id',
    create_dt      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 1.2 OpenAPI 外部用户映射表
CREATE TABLE sai_openapi_user
(
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id           VARCHAR(128) NOT NULL COMMENT '关联 sai_app.app_id',
    open_id          VARCHAR(64)  NOT NULL COMMENT '平台分配的唯一标识（UUID）',
    platform_user_id BIGINT       NOT NULL COMMENT '关联 sai_user.id，注册时自动创建',
    external_id      VARCHAR(256) DEFAULT NULL COMMENT '外部系统的用户标识（可选，幂等用）',
    create_dt        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_dt        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_open (app_id, open_id),
    UNIQUE KEY uk_app_external (app_id, external_id),
    INDEX            idx_open_id (open_id),
    INDEX            idx_platform_user (platform_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='OpenAPI 外部用户映射表';


-- ============================================================
-- 二、AI 模型管理
-- ============================================================

-- 2.1 AI 模型提供商表
CREATE TABLE IF NOT EXISTS sai_model_provider
(
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_name VARCHAR(255) NOT NULL COMMENT '提供商名称',
    provider_key  VARCHAR(50)  NOT NULL COMMENT '提供商标识符',
    description   TEXT COMMENT '提供商描述',
    icon_url      VARCHAR(500) COMMENT 'LOGO图标URL',
    is_enabled    TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_dt    TIMESTAMP  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_dt    TIMESTAMP  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_provider_name (provider_name),
    UNIQUE KEY uk_provider_key (provider_key)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = 'AI模型提供商表';

CREATE INDEX idx_provider_key ON sai_model_provider (provider_key);
CREATE INDEX idx_is_enabled ON sai_model_provider (is_enabled);

-- 2.2 AI模型配置表
CREATE TABLE IF NOT EXISTS sai_model_config
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_id  BIGINT       NOT NULL COMMENT '提供商ID',
    model_name   VARCHAR(255) NOT NULL COMMENT '模型名称',
    model_key    VARCHAR(100) NOT NULL COMMENT '模型标识符',
    model_type   VARCHAR(50)  NOT NULL COMMENT '模型类型(CHAT/EMBEDDING/RERANKER/IMAGE/SPEECH)',
    adapter_key  VARCHAR(100) COMMENT '底层协议适配器标识(openai-compatible/http等)',
    description  VARCHAR(1000) COMMENT '模型描述',
    api_key      VARCHAR(1000) COMMENT 'API密钥(加密存储)',
    api_endpoint VARCHAR(500) COMMENT 'API端点URL',
    config_json  TEXT COMMENT '模型参数配置(JSON格式)',
    owner_id     BIGINT COMMENT '所有者ID(NULL=全局,具体值=用户ID)',
    scope        VARCHAR(20)  NOT NULL DEFAULT 'GLOBAL' COMMENT '作用域(GLOBAL/PERSONAL)',
    is_default   TINYINT(1)            DEFAULT 0 COMMENT '是否为默认模型',
    is_enabled   TINYINT(1)            DEFAULT 1 COMMENT '是否启用',
    created_dt   TIMESTAMP             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_dt   TIMESTAMP             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY fk_provider_id (provider_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = 'AI模型配置表';

CREATE INDEX idx_provider_model_type ON sai_model_config (provider_id, model_type);
CREATE INDEX idx_model_type_enabled ON sai_model_config (model_type, is_enabled);
CREATE INDEX idx_owner_id ON sai_model_config (owner_id);
CREATE INDEX idx_is_default ON sai_model_config (is_default);
CREATE INDEX idx_scope ON sai_model_config (scope);
CREATE INDEX idx_model_key ON sai_model_config (model_key);

-- 2.3 模型使用统计表
CREATE TABLE IF NOT EXISTS sai_model_usage_stat
(
    id                BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id          BIGINT    NOT NULL COMMENT '模型ID',
    user_id           BIGINT    NOT NULL COMMENT '用户ID',
    total_calls       BIGINT         DEFAULT 0 COMMENT '总调用次数',
    success_calls     BIGINT         DEFAULT 0 COMMENT '成功调用次数',
    failed_calls      BIGINT         DEFAULT 0 COMMENT '失败调用次数',
    total_tokens_used BIGINT         DEFAULT 0 COMMENT '总Token使用量',
    total_cost        DECIMAL(18, 8) DEFAULT 0 COMMENT '总费用',
    avg_response_time BIGINT         DEFAULT 0 COMMENT '平均响应时间(毫秒)',
    last_used_dt      TIMESTAMP NULL DEFAULT NULL COMMENT '最后使用时间',
    created_dt        TIMESTAMP      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_dt        TIMESTAMP      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY unique_model_user (model_id, user_id),
    KEY fk_stat_model_id (model_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '模型使用统计表';

CREATE INDEX idx_model_id ON sai_model_usage_stat (model_id);
CREATE INDEX idx_user_id ON sai_model_usage_stat (user_id);
CREATE INDEX idx_last_used_dt ON sai_model_usage_stat (last_used_dt);


-- ============================================================
-- 三、智能体（Agent）
-- ============================================================

-- 3.1 智能体主表
CREATE TABLE IF NOT EXISTS sai_agent
(
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL COMMENT '智能体名称',
    description             TEXT COMMENT '智能体描述',
    avatar                  VARCHAR(512) COMMENT '头像URL',
    instruction             TEXT COMMENT '系统指令(System Prompt)',
    greeting                TEXT COMMENT '欢迎语',
    preset_questions        TEXT COMMENT '预设问题列表（JSON数组字符串）',
    chat_model_id           BIGINT COMMENT '关联的对话模型ID',
    memory_enabled          TINYINT(1)  DEFAULT 0 COMMENT '是否启用记忆库',
    mcp_enabled             TINYINT(1)  DEFAULT 0 COMMENT '是否启用MCP',
    skill_enabled           TINYINT(1)  DEFAULT 0 COMMENT '是否启用Skill',
    web_search_enabled      TINYINT(1)  DEFAULT 0 COMMENT '是否启用联网搜索',
    rag_enabled             TINYINT(1)  DEFAULT 0 COMMENT '是否启用RAG',
    rag_ids                 VARCHAR(64) NULL COMMENT '绑定的RAG ID列表，逗号分隔，最多5个',
    rag_call_mode           TINYINT     DEFAULT 1 COMMENT 'RAG调用方式: 1=智能调用 2=强制调用',
    short_term_memory_size  INT         DEFAULT 20 COMMENT '短期记忆滑动窗口保留条数',
    creator_id              BIGINT COMMENT '创建者用户ID',
    is_featured             TINYINT(1)  DEFAULT 0 COMMENT '是否精选',
    view_count              INT         DEFAULT 0 COMMENT '浏览次数',
    status                  TINYINT     DEFAULT 1 COMMENT '状态: 1-活跃 2-非活跃 3-已废弃 4-已禁用',
    config                  TEXT COMMENT '扩展配置(预留)',
    app_id                  VARCHAR(128) NULL COMMENT '关联应用ID(NULL=本地执行)',
    create_dt               TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_dt               TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体表';

CREATE INDEX idx_agent_creator ON sai_agent (creator_id);
CREATE INDEX idx_agent_featured ON sai_agent (is_featured);

-- 3.2 智能体对话表
CREATE TABLE IF NOT EXISTS sai_agent_conversation
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT      NOT NULL COMMENT '智能体ID',
    user_id         BIGINT      NOT NULL COMMENT '用户ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '对话ID(UUID)',
    title           VARCHAR(255) COMMENT '对话标题',
    create_dt       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conv_id (conversation_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体对话表';

CREATE INDEX idx_agent_conv_agent ON sai_agent_conversation (agent_id);
CREATE INDEX idx_agent_conv_user ON sai_agent_conversation (user_id);

-- 3.3 智能体对话消息记录表
CREATE TABLE IF NOT EXISTS sai_agent_conversation_record
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT      NOT NULL COMMENT '智能体ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '对话ID',
    user_id         BIGINT      NOT NULL COMMENT '用户ID',
    role            VARCHAR(16) DEFAULT 'user' COMMENT 'user/assistant',
    content         TEXT COMMENT '消息内容',
    thinking        TEXT COMMENT '思考过程（仅assistant）',
    metadata        LONGTEXT    DEFAULT NULL COMMENT '消息扩展元数据JSON',
    status          INT         DEFAULT 1 COMMENT '1=成功,2=失败,3=进行中',
    input_tokens    INT         DEFAULT 0 COMMENT '输入Token数（prompt）',
    output_tokens   INT         DEFAULT 0 COMMENT '输出Token数（completion）',
    cache_tokens    INT         DEFAULT 0 COMMENT '缓存命中Token数',
    create_dt       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体对话消息记录';

CREATE INDEX idx_agent_rec_conv ON sai_agent_conversation_record (conversation_id);

-- 3.4 智能体使用统计表
CREATE TABLE IF NOT EXISTS sai_agent_usage_stat
(
    id                 BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id           BIGINT NOT NULL COMMENT '智能体ID',
    user_id            BIGINT NOT NULL COMMENT '用户ID',
    message_count      INT          DEFAULT 0 COMMENT '消息条数',
    conversation_count INT          DEFAULT 0 COMMENT '对话轮次',
    stat_date          DATE   NOT NULL COMMENT '统计日期',
    create_dt          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_dt          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_user_date (agent_id, user_id, stat_date)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体使用统计';

CREATE INDEX idx_usage_agent ON sai_agent_usage_stat (agent_id);
CREATE INDEX idx_usage_date ON sai_agent_usage_stat (stat_date);

-- 3.5 用户订阅的智能体（多对多）
CREATE TABLE IF NOT EXISTS sai_user_agent
(
    id         BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT    NOT NULL COMMENT '用户ID',
    agent_id   BIGINT    NOT NULL COMMENT '智能体ID',
    create_dt  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_agent (user_id, agent_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '用户订阅的智能体';

CREATE INDEX idx_user_agent_user ON sai_user_agent (user_id);


-- ============================================================
-- 四、RAG 知识库
-- ============================================================

-- 4.1 知识库主表
CREATE TABLE sai_rag
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL,
    description               TEXT,
    icon                      VARCHAR(512),
    embedding_model_id        BIGINT(128)  NOT NULL,
    dimension_of_vector_model INT          NOT NULL COMMENT '向量维度',
    rerank_model_id           BIGINT(128),
    search_engine_instance_id BIGINT(128),
    vector_store_instance_id  BIGINT(128),
    search_engine_enable      TINYINT(1)            DEFAULT 0,
    delimiter                 VARCHAR(32)           DEFAULT '\n\n',
    rag_enhancement           TEXT,
    config                    TEXT                  DEFAULT NULL COMMENT 'RAG检索和问答的页面配置参数',
    dedup_strategy            TINYINT(1)   NOT NULL DEFAULT 2            COMMENT '去重策略: 0=NONE 1=BY_NAME 2=BY_CONTENT 3=BY_NAME_OR_CONTENT',
    dedup_action              TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '冲突动作: 0=REJECT 1=SKIP 2=OVERWRITE',
    upload_confirm            TINYINT(1)   NOT NULL DEFAULT 1            COMMENT '上传前二次确认: 0-关 1-开',
    create_dt                 TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    update_dt                 TIMESTAMP             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4.2 RAG 文档表
CREATE TABLE sai_rag_document
(
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rag_id       BIGINT NOT NULL,
    name         VARCHAR(255),
    file_type    VARCHAR(32),
    source_type  VARCHAR(32),
    source_path  VARCHAR(1024),
    storage_path VARCHAR(1024),
    storage_type VARCHAR(32) DEFAULT 'LOCAL',
    file_size    BIGINT      DEFAULT 0,
    content      TEXT,
    status       TINYINT(1)  DEFAULT 0 COMMENT '状态: 0-待处理 1-解析中 2-处理中 3-处理完成 4-处理失败',
    error_msg    TEXT,
    chunk_count  INT         DEFAULT 0,
    page_count   INT         DEFAULT 0 COMMENT 'Docling parse page count',
    element_count INT        DEFAULT 0 COMMENT 'Docling parse element count',
    table_count  INT         DEFAULT 0 COMMENT 'Docling parse table count',
    image_count  INT         DEFAULT 0 COMMENT 'Docling parse image count',
    parse_time   INT         DEFAULT 0 COMMENT 'Parse duration in milliseconds',
    md_content   LONGTEXT    DEFAULT NULL COMMENT 'Docling markdown content',
    doc_metadata LONGTEXT    DEFAULT NULL COMMENT 'Docling document metadata JSON',
    content_hash VARCHAR(64) DEFAULT NULL COMMENT '文件内容SHA-256哈希，用于去重',
    resource_id  BIGINT      DEFAULT NULL COMMENT '关联资源库 sai_resource.id',
    create_dt    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_dt    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_rag_doc_rag ON sai_rag_document (rag_id);
CREATE INDEX idx_rag_content_hash ON sai_rag_document (rag_id, content_hash);
CREATE INDEX idx_rag_name ON sai_rag_document (rag_id, name);
CREATE INDEX idx_rag_doc_resource ON sai_rag_document (resource_id);

CREATE TABLE sai_rag_document_image
(
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rag_id        BIGINT NOT NULL,
    document_id   BIGINT NOT NULL,
    chunk_id      BIGINT DEFAULT NULL,
    resource_id   BIGINT DEFAULT NULL,
    image_index   INT,
    image_url     VARCHAR(1024),
    caption       TEXT,
    figure_no     VARCHAR(64),
    figure_title  VARCHAR(512),
    section_title VARCHAR(512),
    source_page   INT,
    document_name VARCHAR(255),
    ocr_text      TEXT,
    create_dt     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt     TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_rag_doc_image_rag ON sai_rag_document_image (rag_id);
CREATE INDEX idx_rag_doc_image_document ON sai_rag_document_image (document_id);
CREATE INDEX idx_rag_doc_image_chunk ON sai_rag_document_image (chunk_id);
CREATE INDEX idx_rag_doc_image_resource ON sai_rag_document_image (resource_id);

-- 4.3 RAG 分块表
CREATE TABLE sai_rag_chunk
(
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rag_id          BIGINT NOT NULL,
    document_id     BIGINT NOT NULL,
    paragraph_index INT,
    chunk_index     INT,
    content         TEXT,
    token_count     INT,
    vector_id       VARCHAR(128),
    content_hash    VARCHAR(64) DEFAULT NULL COMMENT 'chunk内容SHA-256，用于向量去重',
    source_type     VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT 'chunk来源类型：TEXT文本、IMAGE图片',
    create_dt       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_rag_chunk_rag ON sai_rag_chunk (rag_id);
CREATE INDEX idx_rag_chunk_document ON sai_rag_chunk (document_id);
CREATE INDEX idx_chunk_rag_hash ON sai_rag_chunk (rag_id, content_hash);
CREATE INDEX idx_chunk_rag_source_type ON sai_rag_chunk (rag_id, source_type);


-- ============================================================
-- 五、MCP 服务管理
-- ============================================================

-- 5.1 MCP 服务配置表
CREATE TABLE IF NOT EXISTS sai_mcp_server
(
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(255)  NOT NULL COMMENT 'MCP服务名称',
    description      TEXT          COMMENT 'MCP服务描述',
    transport_type   TINYINT(1)    DEFAULT 1 COMMENT '传输类型: 1-SSE 2-Streamable HTTP 3-Stdio',
    base_uri         VARCHAR(1024) COMMENT '服务基础地址(SSE/Streamable HTTP时使用)',
    endpoint         VARCHAR(1024) COMMENT '端点路径(SSE/Streamable HTTP时可选)',
    command          VARCHAR(1024) COMMENT 'Stdio命令(Stdio时必填)',
    args             TEXT          COMMENT 'Stdio命令参数(JSON数组)',
    env_vars         TEXT          COMMENT 'Stdio环境变量(JSON对象)',
    timeout          BIGINT        DEFAULT 60000 COMMENT '超时时间(毫秒)',
    headers          TEXT          COMMENT '请求头(JSON对象)',
    last_connect_dt  TIMESTAMP NULL COMMENT '最后连接时间',
    creator_id       BIGINT        COMMENT '创建者用户ID',
    create_dt        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_dt        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = 'MCP服务配置表';

CREATE INDEX idx_mcp_server_creator ON sai_mcp_server (creator_id);

-- 5.2 智能体与MCP服务关联表（多对多）
CREATE TABLE IF NOT EXISTS sai_agent_mcp_server
(
    id            BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id      BIGINT    NOT NULL COMMENT '智能体ID',
    mcp_server_id BIGINT    NOT NULL COMMENT 'MCP服务ID',
    create_dt     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_mcp (agent_id, mcp_server_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体MCP服务关联表';

CREATE INDEX idx_agent_mcp_agent ON sai_agent_mcp_server (agent_id);
CREATE INDEX idx_agent_mcp_server ON sai_agent_mcp_server (mcp_server_id);


-- ============================================================
-- 六、Skill 技能包管理
-- ============================================================

-- 6.1 Skill 技能包表
CREATE TABLE IF NOT EXISTS sai_skill
(
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(255)  NOT NULL COMMENT 'Skill名称(从SKILL.md解析)',
    description   TEXT          COMMENT 'Skill描述(从SKILL.md解析)',
    file_name     VARCHAR(255)  COMMENT '上传的zip文件名',
    file_path     VARCHAR(1024) COMMENT '解压后存储路径',
    file_size     BIGINT        DEFAULT 0 COMMENT '文件大小(字节)',
    skill_content LONGTEXT          COMMENT 'SKILL.md正文内容(去除frontmatter)',
    storage_path  VARCHAR(500)  DEFAULT NULL COMMENT '对象存储相对路径前缀（如 skills/123/）',
    version       BIGINT        DEFAULT 0 COMMENT '版本号，文件变更时自增，用于缓存一致性校验',
    has_files     TINYINT(1)    DEFAULT 0 COMMENT '是否包含支撑文件（0=仅SKILL.md，1=有scripts/references等）',
    creator_id    BIGINT        COMMENT '创建者用户ID',
    create_dt     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_dt     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = 'Skill技能包表';

CREATE INDEX idx_skill_creator ON sai_skill (creator_id);

-- 6.2 Skill 支撑文件内容表
CREATE TABLE IF NOT EXISTS sai_skill_file
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id   BIGINT       NOT NULL COMMENT 'Skill ID',
    file_path  VARCHAR(255) NOT NULL COMMENT '文件相对路径',
    content    LONGTEXT     NOT NULL COMMENT '文件内容',
    file_size  INT          NOT NULL COMMENT '文件大小(字节)',
    encoding   VARCHAR(50)  DEFAULT 'utf-8' COMMENT '编码方式',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_skill_path (skill_id, file_path) COMMENT '同一Skill不能有重复的文件路径',
    KEY idx_skill_id (skill_id) COMMENT '技能ID索引'
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = 'Skill支撑文件内容表';

-- 6.3 智能体与Skill关联表（多对多）
CREATE TABLE IF NOT EXISTS sai_agent_skill
(
    id         BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT    NOT NULL COMMENT '智能体ID',
    skill_id   BIGINT    NOT NULL COMMENT 'Skill ID',
    create_dt  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill (agent_id, skill_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '智能体Skill关联表';

CREATE INDEX idx_agent_skill_agent ON sai_agent_skill (agent_id);
CREATE INDEX idx_agent_skill_skill ON sai_agent_skill (skill_id);


-- ============================================================
-- 七、客户端应用与节点
-- ============================================================

-- 7.1 客户端应用
CREATE TABLE IF NOT EXISTS sai_app
(
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id         VARCHAR(128) NOT NULL COMMENT '应用唯一标识',
    app_name       VARCHAR(255) NOT NULL COMMENT '应用名称',
    description    VARCHAR(512) COMMENT '应用描述',
    token          VARCHAR(128) NOT NULL COMMENT '通信认证令牌',
    route_strategy VARCHAR(32)  DEFAULT 'LEAST_LOAD' COMMENT '路由策略',
    status         TINYINT(1)   DEFAULT 1 COMMENT '1=启用, 0=停用',
    create_dt      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_dt      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_id (app_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '客户端应用';

-- 7.2 AI客户端实例节点
CREATE TABLE IF NOT EXISTS sai_client_node
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id              VARCHAR(128) NOT NULL COMMENT '所属应用ID',
    host_id             VARCHAR(128) NOT NULL COMMENT '客户端实例唯一标识',
    host_ip             VARCHAR(64)  NOT NULL COMMENT '客户端IP',
    grpc_port           INT          NOT NULL COMMENT '客户端gRPC端口',
    max_concurrent      INT          DEFAULT 10 COMMENT '最大并发对话数',
    active_chats        INT          DEFAULT 0 COMMENT '当前活跃对话数',
    supported_providers TEXT COMMENT '支持的模型提供商(JSON数组)',
    labels              TEXT COMMENT '路由标签',
    expire_dt           DATETIME     NOT NULL COMMENT '过期时间(心跳更新)',
    create_dt           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_dt           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_client_node (app_id, host_id),
    INDEX idx_app_expire (app_id, expire_dt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'AI客户端实例节点';


-- ============================================================
-- 八、存储与资源
-- ============================================================

-- 8.1 存储实例
CREATE TABLE IF NOT EXISTS sai_store_instance
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL COMMENT '实例名称',
    category   TINYINT(1)   NOT NULL COMMENT '分类: 1-向量库 2-搜索引擎',
    type       TINYINT(1)   NOT NULL COMMENT '类型: 1-PG_VECTOR 2-MILVUS 3-ELASTICSEARCH 4-PG_FULLTEXT',
    config     TEXT         DEFAULT NULL COMMENT '连接参数 JSON',
    status     TINYINT(1)   DEFAULT 1 COMMENT '状态: 0-停用 1-启用',
    is_default TINYINT(1)   DEFAULT 0 COMMENT '是否为该 category 下默认实例',
    create_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_dt  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '存储实例';

CREATE INDEX idx_store_instance_category ON sai_store_instance (category);
CREATE INDEX idx_store_instance_type ON sai_store_instance (type);

-- 8.2 通用资源存储
CREATE TABLE IF NOT EXISTS sai_resource
(
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    storage_key   VARCHAR(512)  NOT NULL COMMENT '存储键（相对路径或对象Key）',
    original_name VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_size     BIGINT        DEFAULT 0 COMMENT '文件大小(bytes)',
    mime_type     VARCHAR(128)  COMMENT 'MIME类型',
    storage_type  VARCHAR(32)   NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型: LOCAL/MINIO',
    access_url    VARCHAR(1024) COMMENT '访问URL',
    biz_type      VARCHAR(64)   NOT NULL DEFAULT 'GENERAL' COMMENT '业务类型: AVATAR/ATTACHMENT/DOCUMENT/GENERAL',
    biz_id        BIGINT        COMMENT '关联业务ID',
    creator_id    BIGINT        COMMENT '上传者ID',
    create_dt     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_dt     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_storage_key (storage_key),
    INDEX idx_biz (biz_type, biz_id),
    INDEX idx_creator (creator_id)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci COMMENT = '通用资源存储';


-- ============================================================
-- 九、初始化数据
-- ============================================================

-- 默认管理员：admin / admin123
INSERT INTO sai_user VALUES (1, 2, NULL, 'admin', 'admin', '',  'pbkdf2$120000$c25haWwtYWktYWRtaW4tMQ==$kakglT/wYKOgv/77Ah1stie58d/JbY2nGgq5DwgUBw4=', NULL, NOW(), NOW());

-- 插入常见的AI提供商
INSERT INTO sai_model_provider (provider_name, provider_key, description, is_enabled)
VALUES ('OpenAI', 'openai', 'OpenAI官方模型 (GPT-4, GPT-3.5等)', 1),
       ('Claude', 'claude', 'Anthropic Claude模型', 1),
       ('Ollama', 'ollama', '本地开源模型 (Llama, Mistral等)', 1),
       ('Google Gemini', 'gemini', 'Google Gemini模型', 1),
       ('阿里云百炼', 'qwen', '阿里云百炼 OpenAI 兼容模型 (Qwen等)', 1),
       ('DeepSeek', 'deepseek', 'DeepSeek OpenAI 兼容模型', 1),
       ('智谱AI', 'zhipu', '智谱AI OpenAI 兼容模型 (GLM等)', 1);

INSERT INTO sai_model_config VALUES (1, 5, 'glm-5.1', 'glm-5.1', 'CHAT', 'openai-compatible', '', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '{\"frequencyPenalty\":0.0,\"maxTokens\":20000,\"presencePenalty\":0.0,\"stopSequences\":[],\"stream\":true,\"temperature\":0.7,\"timeoutMs\":300000,\"topK\":1,\"topP\":1.0}', NULL, 'GLOBAL', 1, 1, NOW(), NOW());

INSERT INTO sai_agent VALUES (1, '智测先锋专家', '智测先锋专家是一款专注于软件测试与质量保障领域的智能助手。它能够高效生成覆盖全面的测试用例，深度分析Bug根因并提供修复建议，支持编写自动化测试脚本，以及解读复杂的测试报告。适用于软件开发周期的各个QA阶段，包括单元测试、接口测试、UI自动化及回归测试规划。其核心特点是逻辑严密、注重边界与异常场景，帮助团队大幅提升测试效率与软件质量。', NULL, '你是一位资深的软件测试与质量保障（QA）专家，名为“智测先锋专家”。\\n\\n【角色定位】你是开发团队的最后一道防线，致力于保障软件产品的卓越质量。\\n\\n【专业领域】精通黑盒与白盒测试、自动化测试框架（如Selenium、Pytest）、接口与性能测试、安全测试及CI/CD持续集成流程。\\n\\n【回答风格】逻辑严密、条理清晰、客观专业。善于使用结构化排版（如Markdown列表、代码块、表格）呈现测试用例和步骤，语言精炼，直击痛点。\\n\\n【行为指南】\\n1. 生成测试用例：必须覆盖正常流、异常流、边界值和兼容性等方面，确保测试的全面性与无遗漏。\\n2. 分析Bug根因：从代码逻辑、数据状态、环境配置等多维度推导，不仅给出修复建议，更要提供预防性的测试策略。\\n3. 编写自动化脚本：确保代码规范、包含必要注释与断言（Assert），并明确说明运行依赖与环境配置。\\n4. 需求澄清：若用户提问模糊，主动追问业务背景、技术栈等关键细节，拒绝给出宽泛且无实操价值的答案。\\n5. 风险预警：始终秉持质量第一理念，在解答中适时提示潜在的测试盲区与质量风险。', '你好！我是智测先锋专家，你的专属软件测试与质量保障顾问。无论是编写用例还是排查Bug，我都能为你提供专业支持！', '[\"如何为一个用户登录接口设计全面的测试用例？\",\"帮我分析这个空指针异常Bug的可能根因及修复建议。\",\"请提供一段Python的Pytest接口自动化测试脚本示例。\",\"怎样制定一个高效的回归测试策略？\"]', 2, 0, 0, 0, 0, 0, NULL, 1, 20, 1, 0, 1, 1, NULL, '1', NOW(), NOW());

INSERT INTO sai_app VALUES (1, '1', '测试', '', 'SAI_566a6bfbc26e4998b4841cc927d50c5d', 'LEAST_LOAD', 1, NOW(), NOW());

INSERT INTO sai_openapi_user VALUES (1, '1', '46ed53c6a20044c7bbd870848e80f92f', 1, '1', NOW(), NOW());



-- ===================================================
-- WMS 库存业务表
-- Segment: WMS 库存业务初始化
-- ===================================================

-- ===================================================
-- 升级脚本：V1.0.0__wms_inventory_tables
-- 描述：创建库存相关表（库存表、库存事务流水表、库存预留表、库存冻结/扣留单头、库存冻结/扣留明细）
-- 创建日期：2026-09-16
-- 依赖：无
-- 说明：
--   1. 表名在模板表名基础上统一使用 wms_ 前缀，字段名、类型与说明取自《仓储系统数据库模型审阅》
--      （C:\code\private\innolux-rule\仓储系统数据库模型审阅.md）。
--   2. 模板中标注的“外键”仅表示关联关系，本脚本不建立数据库外键约束，被引用表
--      （wms_material、wms_material_lot、wms_container、wms_storage_position、wms_outbound_order_body、
--      wms_transfer_order_body、wms_cutting_task、wms_cutting_task_detail 等）后续再建，关联一致性由应用层保证。
--   3. 索引按模板标注建立：标注“唯一索引”的列建唯一键，标注“普通索引”的列建普通索引。
--   4. 全部语句使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ===================================================

-- ----------------------------
-- 1、库存表
-- ----------------------------
create table if not exists wms_inventory (
    inventory_id        bigint           not null                    comment '库存主键',
    material_lot_id     bigint           default null                comment '物料批次主键',
    material_code       varchar(50)      not null                    comment '物料编码',
    container_id        varchar(50)      default null                comment '容器编码',
    original_label_info longtext                                     comment '首次入库采集的原始标签报文或标签字段快照',
    inventory_qty       decimal(20,6)    not null default 0.000000   comment '当前库存数量',
    inbound_time        datetime         default null                comment '入库时间',
    frozen_state        int              not null default 0          comment '当前冻结状态投影，冻结事实以扣留单明细为准',
    note                varchar(500)     default null                comment '备注',
    status              varchar(32)      not null                    comment '库存状态',
    production_date     date             default null                comment '生产日期',
    created_by          bigint           not null                    comment '创建人',
    create_time         datetime         not null                    comment '创建时间',
    update_by           bigint           default null                comment '更新人',
    update_time         datetime         default null                comment '更新时间',
    corporation         varchar(50)      default null                comment '库存所属法人',
    compartment_code    varchar(50)      default null                comment '容器格口编号',
    primary key (inventory_id),
    key idx_wms_inventory_material_lot_id (material_lot_id),
    key idx_wms_inventory_material_code   (material_code),
    key idx_wms_inventory_container_id    (container_id),
    key idx_wms_inventory_inbound_time    (inbound_time),
    key idx_wms_inventory_frozen_state    (frozen_state),
    key idx_wms_inventory_status          (status)
) engine=innodb comment = '库存表';

-- ----------------------------
-- 2、库存事务流水表
-- ----------------------------
create table if not exists wms_inventory_transaction (
    id                     bigint         not null auto_increment   comment '主键',
    transaction_no         varchar(64)    not null                   comment '库存事务号',
    transaction_type       varchar(32)    not null                   comment '事务类型：入库、出库、移库、盘盈盘亏、CUTTING_MOTHER_OUTBOUND、CUTTING_MOTHER_RETURN 或 CUTTING_CHILD_OUTBOUND',
    transaction_group_no   varchar(64)    not null                   comment '截料时关联母盘出库、母盘余料回库和多条子盘出库流水',
    movement_scope         varchar(16)    not null                   comment 'PHYSICAL-物理移动或 LOGICAL-逻辑库存变化',
    stock_unit_code        varchar(100)   not null                   comment '本条流水对应的母盘或子盘唯一标识',
    parent_stock_unit_code varchar(100)   default null               comment '子盘对应的母盘唯一标识',
    root_stock_unit_code   varchar(100)   not null                   comment '物料谱系根盘唯一标识',
    inventory_id           bigint         not null                   comment '库存主键',
    material_code          varchar(50)    not null                   comment '物料编码快照',
    material_lot_id        bigint         default null               comment '物料批次主键',
    container_id           varchar(50)    default null               comment '容器',
    storage_id             bigint         default null               comment '储位',
    original_label_info    longtext                                 comment '事务发生时的原始标签快照，用于库存删除后的审计追溯',
    before_qty             decimal(20,6)  not null                   comment '变更前数量',
    change_qty             decimal(20,6)  not null                   comment '本次变更数量，增加为正、减少为负',
    after_qty              decimal(20,6)  not null                   comment '变更后数量',
    source_order_type      varchar(32)    not null                   comment '来源单据类型',
    source_order_code      varchar(100)   not null                   comment '来源单号',
    source_task_type       varchar(32)    default null               comment '来源任务类型，如 CUTTING',
    source_task_id         bigint         default null               comment '来源任务主键',
    operator_id            bigint         default null               comment '操作人',
    occurred_at            datetime       not null                   comment '业务发生时间',
    idempotency_key        varchar(128)   not null                   comment '防止库存重复记账的稳定键',
    remark                 varchar(500)   default null               comment '备注',
    primary key (id),
    unique key uk_wms_inventory_transaction_no (transaction_no),
    unique key uk_wms_inventory_transaction_idempotency_key (idempotency_key),
    key idx_wms_inventory_transaction_type (transaction_type),
    key idx_wms_inventory_transaction_group_no (transaction_group_no),
    key idx_wms_inventory_transaction_movement_scope (movement_scope),
    key idx_wms_inventory_transaction_stock_unit_code (stock_unit_code),
    key idx_wms_inventory_transaction_parent_stock_unit (parent_stock_unit_code),
    key idx_wms_inventory_transaction_root_stock_unit (root_stock_unit_code),
    key idx_wms_inventory_transaction_source_order_type (source_order_type),
    key idx_wms_inventory_transaction_source_order_code (source_order_code),
    key idx_wms_inventory_transaction_occurred_at (occurred_at)
) engine=innodb comment = '库存事务流水表';

-- ----------------------------
-- 3、库存预留表
-- ----------------------------
create table if not exists wms_inventory_reservation (
    id                   bigint         not null auto_increment   comment '主键',
    reservation_no       varchar(64)    not null                   comment '预留编号',
    inventory_id         bigint         not null                   comment '被预留库存',
    material_code        varchar(50)    not null                   comment '物料编码',
    material_lot_id      bigint         default null               comment '物料批次主键',
    reserved_qty         decimal(20,6)  not null                   comment '预留数量',
    source_order_type    varchar(32)    not null                   comment '来源单据类型',
    source_order_code    varchar(100)   not null                   comment '来源单号',
    source_order_line_id bigint         default null               comment '来源单体主键',
    status               varchar(32)    not null                   comment '状态：已预留、已转实物、已释放或已过期',
    expires_at           datetime       default null               comment '预留过期时间',
    created_by           bigint         not null                   comment '创建人',
    create_time          datetime       not null                   comment '创建时间',
    released_by          bigint         default null               comment '释放人',
    released_at          datetime       default null               comment '释放时间',
    primary key (id),
    unique key uk_wms_inventory_reservation_no (reservation_no),
    key idx_wms_inventory_reservation_inventory_id (inventory_id),
    key idx_wms_inventory_reservation_source_order_code (source_order_code),
    key idx_wms_inventory_reservation_status (status),
    key idx_wms_inventory_reservation_expires_at (expires_at)
) engine=innodb comment = '库存预留表';

-- ----------------------------
-- 4、库存冻结/扣留单头
-- ----------------------------
create table if not exists wms_inventory_hold_order (
    id                 bigint         not null auto_increment   comment '主键',
    hold_order_no      varchar(64)    not null                   comment '冻结或扣留单号',
    hold_type          varchar(32)    not null                   comment '冻结类型：质量、盘点、异常、人工或合规扣留',
    scope_type         varchar(32)    not null                   comment '范围类型：按库存、物料、批次、容器、库位或条件范围',
    reason_code        varchar(64)    not null                   comment '原因编码',
    reason_description varchar(500)   not null                   comment '原因说明',
    status             varchar(32)    not null                   comment '状态：待执行、已生效、部分释放或已释放',
    requested_by       bigint         not null                   comment '发起人',
    requested_at       datetime       not null                   comment '发起时间',
    released_by        bigint         default null               comment '解除人',
    released_at        datetime       default null               comment '解除时间',
    create_time        datetime       not null                   comment '创建时间',
    update_time        datetime       default null               comment '更新时间',
    primary key (id),
    unique key uk_wms_inventory_hold_order_no (hold_order_no),
    key idx_wms_inventory_hold_order_reason_code (reason_code),
    key idx_wms_inventory_hold_order_status (status)
) engine=innodb comment = '库存冻结/扣留单头';

-- ----------------------------
-- 5、库存冻结/扣留明细
-- ----------------------------
create table if not exists wms_inventory_hold_detail (
    id             bigint         not null auto_increment   comment '主键',
    hold_order_no  varchar(64)    not null                   comment '冻结或扣留单号',
    inventory_id   bigint         not null                   comment '目标库存',
    material_code  varchar(50)    not null                   comment '物料编码快照',
    material_lot_id bigint        default null               comment '物料批次主键',
    container_id   varchar(50)    default null               comment '容器',
    storage_id     bigint         default null               comment '储位',
    held_qty       decimal(20,6)  not null                   comment '被冻结或扣留数量',
    status_before  varchar(32)    not null                   comment '生效前库存状态',
    status_after   varchar(32)    not null                   comment '生效后库存状态',
    release_status varchar(32)    not null                   comment '释放状态：未释放、部分释放或已释放',
    release_reason varchar(500)   default null               comment '释放原因',
    create_time    datetime       not null                   comment '创建时间',
    update_time    datetime       default null               comment '更新时间',
    primary key (id),
    key idx_wms_inventory_hold_detail_hold_order_no (hold_order_no),
    key idx_wms_inventory_hold_detail_inventory_id (inventory_id)
) engine=innodb comment = '库存冻结/扣留明细';

