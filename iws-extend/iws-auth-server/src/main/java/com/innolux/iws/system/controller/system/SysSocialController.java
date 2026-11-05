/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 认证中心复用个人设置页面所需的当前账号社交绑定查询接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.controller.system;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.domain.vo.SysSocialVo;
import com.innolux.iws.system.service.ISysSocialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证中心当前账号社交绑定查询接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/social")
public class SysSocialController {

    private final ISysSocialService socialUserService;

    /**
     * 查询当前登录用户的社交账号绑定列表。
     *
     * @return 绑定关系列表
     */
    @GetMapping("/list")
    public R<List<SysSocialVo>> list() {
        return R.ok(socialUserService.queryListByUserId(LoginHelper.getUserId()));
    }
}
