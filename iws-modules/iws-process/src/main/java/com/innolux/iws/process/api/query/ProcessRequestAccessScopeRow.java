/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 请求关联对象授权目标投影；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import lombok.Data;

/**
 * 请求关联对象授权目标投影。
 */
@Data
public class ProcessRequestAccessScopeRow {

    private Long instanceId;

    private Long eventId;

    private Long scheduleId;

    private String businessType;

    private String businessId;

    private String taskKey;

    private String scheduleScope;
}
