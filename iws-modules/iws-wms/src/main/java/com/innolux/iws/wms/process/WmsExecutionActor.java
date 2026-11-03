/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划动作
 * @Version      : V1.0
 * @Description  : 持久业务任务冻结的发起人与执行审计身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

/**
 * 后台动作执行身份。
 *
 * @param userId    发起人用户 ID
 * @param userName  发起人账号
 * @param nickName  发起人名称
 * @param deptId    发起时部门 ID
 * @param deptName  发起时部门名称
 */
public record WmsExecutionActor(
    long userId,
    String userName,
    String nickName,
    Long deptId,
    String deptName
) {
}
