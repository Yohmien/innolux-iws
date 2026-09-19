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

import cn.hutool.core.thread.ThreadUtil;
import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.lock.executor.RedissonLockExecutor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;


/**
 * 测试分布式锁的样例
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@RestController
@RequestMapping("/demo/redisLock")
public class RedisLockController {

    @Autowired
    private LockTemplate lockTemplate;

    /**
     * 测试lock4j 注解
     */
    @Lock4j(keys = {"#key"})
    @GetMapping("/testLock4j")
    public R<String> testLock4j(String key, String value) {
        System.out.println("start:" + key + ",time:" + LocalTime.now());
        ThreadUtil.sleep(10000);
        System.out.println("end :" + key + ",time:" + LocalTime.now());
        return R.data(value);
    }

    /**
     * 测试lock4j 工具
     */
    @GetMapping("/testLock4jLockTemplate")
    public R<String> testLock4jLockTemplate(String key, String value) {
        final LockInfo lockInfo = lockTemplate.lock(key, 30000L, 5000L, RedissonLockExecutor.class);
        if (null == lockInfo) {
            throw new RuntimeException("业务处理中,请稍后再试");
        }
        // 获取锁成功，处理业务
        try {
            ThreadUtil.sleep(8000);
            System.out.println("执行简单方法1 , 当前线程:" + Thread.currentThread().getName());
        } finally {
            //释放锁
            lockTemplate.releaseLock(lockInfo);
        }
        //结束
        return R.data(value);
    }

}
