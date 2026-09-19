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
import com.innolux.iws.common.core.utils.DateUtils;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.job.entity.BillDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * DAG工作流任务-模拟支付宝账单任务
 * <a href="https://juejin.cn/post/7487860254114644019"></a>
 *
 * @author YONGJIE.PENG
 */
@Component
@JobExecutor(name = "alipayBillTask")
public class AlipayBillTask {

    /**
     * 执行支付宝账单模拟任务，并将结果写入工作流上下文。
     *
     * @param jobArgs 任务执行参数
     * @return 执行结果
     * @throws InterruptedException 任务被中断时抛出
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) throws InterruptedException {
        // 设置清算日期
        String settlementDate = (String) jobArgs.getWfContext().get("settlementDate");
        if (StringUtils.equals(settlementDate, "sysdate")) {
            settlementDate = DateUtils.now();
        }
        BillDTO billDTO = new BillDTO(23456789L, "alipay", settlementDate, new BigDecimal("2345.67"));
        // 把billDTO对象放入上下文进行传递
        jobArgs.appendContext("alipay", JsonUtils.toJsonString(billDTO));
        SnailJobLog.REMOTE.info("上下文: {}", jobArgs.getWfContext());
        return ExecuteResult.success(billDTO);
    }

}
