/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务对象范围授权扩展点；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 业务模块注册的对象范围策略；未注册时默认只允许超级对象权限。
 */
public interface ProcessAccessPolicy {

    /**
     * 是否允许访问目标。
     *
     * @param target 授权目标
     * @return true 表示允许
     */
    boolean canAccess(ProcessAccessTarget target);
}
