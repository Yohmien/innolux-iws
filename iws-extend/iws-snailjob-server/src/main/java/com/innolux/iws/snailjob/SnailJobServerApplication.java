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

package com.innolux.iws.snailjob;

import com.innolux.iws.common.launch.IwsConfigLocationSupport;
import com.innolux.iws.snailjob.iam.IamJobConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;

/**
 * SnailJob Server 启动程序
 *
 * @author YONGJIE.PENG
 * @date 2024-05-17
 */
@SpringBootApplication
public class SnailJobServerApplication {

    /**
     * SnailJob 服务启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(
            com.aizuda.snailjob.server.SnailJobServerApplication.class
        );
        application.addPrimarySources(List.of(IamJobConfiguration.class));
        IwsConfigLocationSupport.configure(application, SnailJobServerApplication.class, args, "job");
        application.run(args);
    }

}
