/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 查询内部实例行，附带数据库计算的候选位次；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import com.innolux.iws.process.domain.ProcessInstance;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询内部实例行，附带数据库计算的候选位次；不直接返回前端。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProcessInstanceQueryRow extends ProcessInstance {

    /**
     * 同一监督候选队列中的位次。
     */
    private Integer queueRank;
}
