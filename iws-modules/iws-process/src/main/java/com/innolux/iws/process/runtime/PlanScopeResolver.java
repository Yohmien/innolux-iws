/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 服务端调度范围解析扩展点；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 由业务事实解析调度范围，请求方不能直接指定 scope。
 */
public interface PlanScopeResolver {

    /**
     * 解析受控 scope。
     *
     * @param businessType 业务类型
     * @param planType 计划类型
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @return 非空 scope
     */
    String resolve(String businessType, String planType, String businessId, String taskKey);
}
