/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 在 SnailJob 原生启动源之外装配 IWS 统一身份适配组件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Job 统一身份适配配置。
 */
@Configuration
@ComponentScan(basePackages = "com.innolux.iws.snailjob.iam")
public class IamJobConfiguration {
}
