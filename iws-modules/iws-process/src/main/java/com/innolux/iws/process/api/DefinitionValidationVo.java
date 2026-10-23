/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划配置与查询 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 发布前校验结果。
 *
 * @param valid 是否通过
 * @param versionId 版本 ID
 * @param revision 聚合版本
 * @param errors 错误列表
 * @param warnings 警告列表
 */
public record DefinitionValidationVo(boolean valid, String versionId, String revision,
                                     java.util.List<String> errors, java.util.List<String> warnings) {
}
