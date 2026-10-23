/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统目录与系统地址关联接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemPageBo;
import com.innolux.iws.endpoint.domain.vo.EndpointChangeResultVo;
import com.innolux.iws.endpoint.domain.vo.EndpointSystemVo;
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
 * 系统目录与系统地址关联接口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/endpoint/systems")
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointSystemController {

    private final EndpointCatalogService catalogService;

    /**
     * 分页查询系统目录。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 系统分页
     */
    @SaCheckPermission("endpoint:system:list")
    @GetMapping("/page")
    public R<PageResult<EndpointSystemVo>> page(EndpointSystemPageBo query, PageQuery pageQuery) {
        return R.ok(catalogService.pageSystems(query, pageQuery));
    }

    /**
     * 查询系统目录详情。
     *
     * @param systemCode 系统编码
     * @return 系统详情
     */
    @SaCheckPermission("endpoint:system:query")
    @GetMapping("/{systemCode}")
    public R<EndpointSystemVo> detail(@PathVariable String systemCode) {
        return R.ok(catalogService.querySystem(systemCode));
    }

    /**
     * 新增系统目录。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:system:add")
    @Log(title = "系统接入配置-系统定义", businessType = BusinessType.INSERT)
    @PostMapping
    public R<EndpointChangeResultVo> add(@Validated @RequestBody EndpointSystemBo bo) {
        return R.ok(catalogService.createSystem(bo));
    }

    /**
     * 修改系统目录。
     *
     * @param systemCode 稳定系统编码
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:system:edit")
    @Log(title = "系统接入配置-系统定义", businessType = BusinessType.UPDATE)
    @PutMapping("/{systemCode}")
    public R<EndpointChangeResultVo> edit(@PathVariable String systemCode,
                                          @Validated @RequestBody EndpointSystemBo bo) {
        return R.ok(catalogService.updateSystem(systemCode, bo));
    }

    /**
     * 以完整集合保存系统地址关联。
     *
     * @param systemCode 系统编码
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission(value = {"endpoint:system:edit", "endpoint:address:query"}, mode = SaMode.AND)
    @Log(title = "系统接入配置-系统地址关联", businessType = BusinessType.UPDATE)
    @PutMapping("/{systemCode}/addresses")
    public R<EndpointChangeResultVo> replaceAddresses(@PathVariable String systemCode,
                                                      @Validated @RequestBody EndpointSystemAddressBo bo) {
        return R.ok(catalogService.replaceSystemAddresses(systemCode, bo));
    }
}
