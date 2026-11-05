/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 认证中心复用原账号页面所需的系统配置只读接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.controller.system;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.system.service.ISysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证中心系统配置只读接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/config")
public class SysConfigController {

    private final ISysConfigService configService;

    /**
     * 根据参数键名查询参数值。
     *
     * @param configKey 参数键名
     * @return 参数值
     */
    @GetMapping("/configKey/{configKey}")
    public R<String> getConfigKey(@PathVariable String configKey) {
        return R.data(configService.selectConfigByKey(configKey));
    }
}
