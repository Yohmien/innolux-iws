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

package com.innolux.iws.workflow.config;

import com.innolux.iws.workflow.common.ConditionalOnEnable;
import org.springframework.context.annotation.Configuration;

/**
 * WarmFlow 工作流配置入口，在工作流开关开启时注册相关组件。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@Configuration
public class WarmFlowConfig {

}

