/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 独立统一身份认证中心启动入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.launch.IwsConfigLocationSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * 统一身份认证中心应用。
 */
@EnableConfigurationProperties(IamProperties.class)
@SpringBootApplication
@ComponentScan(basePackages = "com.innolux.iws")
public class AuthServerApplication {

    /**
     * 应用启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AuthServerApplication.class);
        IwsConfigLocationSupport.configure(application, AuthServerApplication.class, args, "auth-center");
        application.run(args);
    }
}
