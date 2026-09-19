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

package com.innolux.iws.demo.controller;

import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.redis.annotation.RateLimiter;
import com.innolux.iws.common.redis.enums.LimitType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 测试分布式限流样例
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@RestController
@RequestMapping("/demo/rateLimiter")
public class RedisRateLimiterController {

    /**
     * 测试全局限流
     * 全局影响
     */
    @RateLimiter(count = 2, time = 10)
    @GetMapping("/test")
    public R<String> test(String value) {
        return R.data(value);
    }

    /**
     * 测试请求IP限流
     * 同一IP请求受影响
     */
    @RateLimiter(count = 2, time = 10, limitType = LimitType.IP)
    @GetMapping("/testip")
    public R<String> testip(String value) {
        return R.data(value);
    }

    /**
     * 测试集群实例限流
     * 启动两个后端服务互不影响
     */
    @RateLimiter(count = 2, time = 10, limitType = LimitType.CLUSTER)
    @GetMapping("/testcluster")
    public R<String> testcluster(String value) {
        return R.data(value);
    }

    /**
     * 测试请求IP限流(key基于参数获取)
     * 同一IP请求受影响
     * <p>
     * 简单变量获取 #变量 复杂表达式 #{#变量 != 1 ? 1 : 0}
     */
    @RateLimiter(count = 2, time = 10, limitType = LimitType.IP, key = "#value")
    @GetMapping("/testObj")
    public R<String> testObj(String value) {
        return R.data(value);
    }

}
