/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 环境目录接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentBo;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentPageBo;
import com.innolux.iws.endpoint.domain.vo.EndpointChangeResultVo;
import com.innolux.iws.endpoint.domain.vo.EndpointEnvironmentVo;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境目录接口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/endpoint/environments")
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointEnvironmentController {

    private final EndpointCatalogService catalogService;

    /**
     * 分页查询环境目录。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 环境分页
     */
    @SaCheckPermission("endpoint:environment:list")
    @GetMapping("/page")
    public R<PageResult<EndpointEnvironmentVo>> page(EndpointEnvironmentPageBo query, PageQuery pageQuery) {
        return R.ok(catalogService.pageEnvironments(query, pageQuery));
    }

    /**
     * 查询环境目录详情。
     *
     * @param environmentCode 环境编码
     * @return 环境详情
     */
    @SaCheckPermission("endpoint:environment:query")
    @GetMapping("/{environmentCode}")
    public R<EndpointEnvironmentVo> detail(@PathVariable String environmentCode) {
        return R.ok(catalogService.queryEnvironment(environmentCode));
    }

    /**
     * 新增环境目录。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:environment:add")
    @Log(title = "系统接入配置-环境配置", businessType = BusinessType.INSERT)
    @PostMapping
    public R<EndpointChangeResultVo> add(@Validated @RequestBody EndpointEnvironmentBo bo) {
        return R.ok(catalogService.createEnvironment(bo));
    }

    /**
     * 修改环境目录。
     *
     * @param environmentCode 稳定环境编码
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:environment:edit")
    @Log(title = "系统接入配置-环境配置", businessType = BusinessType.UPDATE)
    @PutMapping("/{environmentCode}")
    public R<EndpointChangeResultVo> edit(@PathVariable String environmentCode,
                                          @Validated @RequestBody EndpointEnvironmentBo bo) {
        return R.ok(catalogService.updateEnvironment(environmentCode, bo));
    }
}
