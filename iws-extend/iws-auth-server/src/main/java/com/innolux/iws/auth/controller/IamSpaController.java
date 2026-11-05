/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 将中心前端 History 路由回退到独立构建首页；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 中心前端路由回退。
 */
@SaIgnore
@Controller
public class IamSpaController {

    /**
     * 返回中心前端入口。
     *
     * @return 前端首页转发地址
     */
    @GetMapping({
        "/iam",
        "/iam/",
        "/iam/login",
        "/iam/sso/**",
        "/iam/system/**",
        "/iam/user/**"
    })
    public String index() {
        return "forward:/iam/index.html";
    }
}
