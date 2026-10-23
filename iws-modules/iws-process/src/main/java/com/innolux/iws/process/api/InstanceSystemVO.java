/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例运行查询与命令 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 实例系统模式与确认事实。
 *
 * @param systemCode 系统编码
 * @param postingMode 过账模式
 * @param confirmed 是否已首次确认
 * @param confirmedBy 确认人
 * @param confirmedAt 确认时间
 */
public record InstanceSystemVO(String systemCode, String postingMode, Boolean confirmed, String confirmedBy,
                               String confirmedAt) {
}
