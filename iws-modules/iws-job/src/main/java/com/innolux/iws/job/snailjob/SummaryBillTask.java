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

package com.innolux.iws.job.snailjob;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.job.entity.BillDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * DAG工作流任务-模拟汇总账单任务
 * <a href="https://juejin.cn/post/7487860254114644019"></a>
 *
 * @author YONGJIE.PENG
 */
@Component
@JobExecutor(name = "summaryBillTask")
public class SummaryBillTask {

    /**
     * 汇总工作流上下文中的微信与支付宝账单金额。
     *
     * @param jobArgs 任务执行参数
     * @return 汇总执行结果
     * @throws InterruptedException 任务被中断时抛出
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) throws InterruptedException {
        // 获得微信账单
        BigDecimal wechatAmount = BigDecimal.valueOf(0);
        String wechat = (String) jobArgs.getWfContext("wechat");
        if (StringUtils.isNotBlank(wechat)) {
            BillDTO wechatBillDTO = JsonUtils.parseObject(wechat, BillDTO.class);
            wechatAmount = wechatBillDTO.billAmount();
        }
        // 获得支付宝账单
        BigDecimal alipayAmount = BigDecimal.valueOf(0);
        String alipay = (String) jobArgs.getWfContext("alipay");
        if (StringUtils.isNotBlank(alipay)) {
            BillDTO alipayBillDTO = JsonUtils.parseObject(alipay, BillDTO.class);
            alipayAmount = alipayBillDTO.billAmount();
        }
        // 汇总账单
        BigDecimal totalAmount = wechatAmount.add(alipayAmount);
        SnailJobLog.REMOTE.info("总金额: {}", totalAmount);
        return ExecuteResult.success(totalAmount);
    }

}
