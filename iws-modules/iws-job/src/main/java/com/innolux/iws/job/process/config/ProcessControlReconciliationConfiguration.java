package com.innolux.iws.job.process.config;

import com.innolux.iws.job.process.control.ProcessControlReconciler;
import com.innolux.iws.process.api.ProcessControlIntentPort;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅协调持久平台控制意图，使首次任务无需依赖其自身先被触发。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "iws.process.scheduler", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "snail-job", name = "enabled", havingValue = "true")
public class ProcessControlReconciliationConfiguration {
    @Bean
    ProcessControlReconciler processControlReconciler(ProcessControlIntentPort intents,
                                                     SchedulerPlatformPort platform,
            @Value("${iws.process.scheduler.control-batch-limit}") int batchLimit) {
        return new ProcessControlReconciler(intents, platform, batchLimit);
    }

    @Bean
    ControlTick processControlTick(ProcessControlReconciler reconciler) {
        return new ControlTick(reconciler);
    }

    static final class ControlTick {
        private final ProcessControlReconciler reconciler;

        ControlTick(ProcessControlReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(fixedDelayString = "${iws.process.scheduler.control-poll-interval-ms}")
        public void reconcile() {
            reconciler.reconcile();
        }
    }
}
