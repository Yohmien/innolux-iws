/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SnailJob BizId 请求字段映射测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.client.common.config.SnailJobProperties;
import com.aizuda.snailjob.client.job.core.enums.AllocationAlgorithmEnum;
import com.aizuda.snailjob.client.job.core.handler.add.ClusterAddBizIdHandler;
import com.aizuda.snailjob.client.job.core.openapi.SnailJobBizIdOpenApi;
import com.aizuda.snailjob.common.core.context.SnailSpringContext;
import com.aizuda.snailjob.common.core.enums.JobArgsTypeEnum;
import com.aizuda.snailjob.model.request.JobBizIdApiRequest;
import com.innolux.iws.process.api.JobSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只构造 SDK 请求，不调用 SnailJob 平台。
 */
@Tag("dev")
class SnailJobBizIdOpenApiClientTest {

    @Test
    void mapsFrozenJobSpecIntoBizIdRequest() {
        SnailJobProperties properties = new SnailJobProperties();
        properties.setGroup("iws_group");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SnailJobProperties.class, () -> properties);
            context.registerBean(SnailSpringContext.class);
            context.refresh();

            JobSpec spec = new JobSpec("businessScheduleExecutor", "计划监督－入库－队列－越南",
                "process:supervisor:42", "CRON", "0 */1 * * * ?", null, 2501L, 2,
                "{\"supervisorInstanceId\":42,\"batchLimit\":5,\"maxDurationMs\":5000}", "ROUND");
            ClusterAddBizIdHandler handler = SnailJobBizIdOpenApi.addClusterJob(spec.bizId());
            SnailJobBizIdOpenApiClient.configure(handler, SnailJobJobDefinition.from(spec));
            JobBizIdApiRequest request = handler.getReqDTO();

            assertThat(request.getGroupName()).isEqualTo("iws_group");
            assertThat(request.getJobName()).isEqualTo(spec.jobName());
            assertThat(request.getExecutorInfo()).isEqualTo("businessScheduleExecutor");
            assertThat(request.getTriggerInterval()).isEqualTo("0 */1 * * * ?");
            assertThat(request.getExecutorTimeout()).isEqualTo(3);
            assertThat(request.getMaxRetryTimes()).isEqualTo(2);
            assertThat(request.getArgsStr()).isEqualTo(spec.paramsJson());
            assertThat(request.getArgsType()).isEqualTo(JobArgsTypeEnum.JSON.getArgsType());
            assertThat(request.getRouteKey()).isEqualTo(AllocationAlgorithmEnum.ROUND.getType());
        }
    }
}
