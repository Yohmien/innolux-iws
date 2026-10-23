/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件公开命令与结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 命令结果。
 *
 * @param code 领域结果码
 * @param message 中文说明
 * @param instanceId 计划实例 ID
 * @param revision 最新版本
 * @param confirmedSystems 本次确认系统
 */
public record CommandResult(String code, String message, String instanceId, String revision,
                            java.util.List<String> confirmedSystems) {

    /**
     * 构建成功结果。
     *
     * @param instanceId 实例 ID
     * @param revision 最新版本
     * @param confirmedSystems 确认系统
     * @return 命令结果
     */
    public static CommandResult success(String instanceId, String revision, java.util.List<String> confirmedSystems) {
        return new CommandResult("SUCCEEDED", "操作成功", instanceId, revision, confirmedSystems);
    }
}
