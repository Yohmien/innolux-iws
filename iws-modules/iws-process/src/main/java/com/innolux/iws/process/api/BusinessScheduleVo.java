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
 * 业务调度展示对象。
 *
 * @param id 业务调度 ID
 * @param businessCode 业务编码
 * @param businessName 业务中文名
 * @param enabled 是否启用
 * @param fairnessWindowSize 公平窗口
 * @param revision 版本
 * @param remark 说明
 * @param planTypes 计划类型
 */
public record BusinessScheduleVo(String id, String businessCode, String businessName, Boolean enabled,
                                 Integer fairnessWindowSize, String revision, String remark,
                                 java.util.List<PlanTypeVo> planTypes) {
}
