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

package com.innolux.iws.snailai;

import com.innolux.iws.common.launch.IwsConfigLocationSupport;
import com.innolux.iws.snailai.iam.IamAiConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;
import java.util.List;

/**
 * Snail AI Server 启动程序
 *
 * @author YONGJIE.PENG
 * @date 2026-05-26
 */
@SpringBootApplication
public class SnailAiServerApplication {

    /**
     * Snail AI 服务启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication application = new SpringApplication(
            com.aizuda.snail.ai.starter.SnailAiApplication.class
        );
        application.addPrimarySources(List.of(IamAiConfiguration.class));
        IwsConfigLocationSupport.configure(application, SnailAiServerApplication.class, args, "ai");
        application.run(args);
    }

}
