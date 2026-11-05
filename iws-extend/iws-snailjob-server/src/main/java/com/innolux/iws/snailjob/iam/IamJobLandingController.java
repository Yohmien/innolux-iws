/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 保留 Job 原生 /admin 入口并转发到 IWS 调度中心页面；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Job 原生入口兼容。
 */
@Controller
public class IamJobLandingController {

    /**
     * 兼容 SnailJob 原生 /admin 入口。
     *
     * @return 调度中心静态页
     */
    @GetMapping({"/admin", "/admin/"})
    public String adminEntry() {
        return "forward:/admin/index.html";
    }
}
