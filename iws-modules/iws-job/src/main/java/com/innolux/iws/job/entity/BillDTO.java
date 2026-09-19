/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.entity;

import java.math.BigDecimal;

/**
 * 账单数据传输对象。
 *
 * @param billId      账单 ID
 * @param billChannel 账单渠道
 * @param billDate    账单日期
 * @param billAmount  账单金额
 */
public record BillDTO(
    Long billId,
    String billChannel,
    String billDate,
    BigDecimal billAmount
) {
}
