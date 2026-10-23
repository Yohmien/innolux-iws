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
 * 业务调度保存参数。
 *
 * @param businessCode 业务编码
 * @param businessName 业务中文名
 * @param enabled 是否启用
 * @param fairnessWindowSize 公平窗口
 * @param remark 说明
 */
public record BusinessScheduleBo(String businessCode, String businessName, Boolean enabled,
                                 Integer fairnessWindowSize, String remark) {
}
