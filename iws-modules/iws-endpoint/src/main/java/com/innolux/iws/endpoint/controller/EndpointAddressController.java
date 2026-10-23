/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 共享环境地址接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.domain.bo.EndpointAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointAddressPageBo;
import com.innolux.iws.endpoint.domain.vo.EndpointAddressVo;
import com.innolux.iws.endpoint.domain.vo.EndpointChangeResultVo;
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
 * 共享环境地址接口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/endpoint/addresses")
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointAddressController {

    private final EndpointCatalogService catalogService;

    /**
     * 分页查询共享地址。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 地址分页
     */
    @SaCheckPermission("endpoint:address:list")
    @GetMapping("/page")
    public R<PageResult<EndpointAddressVo>> page(EndpointAddressPageBo query, PageQuery pageQuery) {
        return R.ok(catalogService.pageAddresses(query, pageQuery));
    }

    /**
     * 查询共享地址详情。
     *
     * @param addressId 字符串地址主键
     * @return 地址详情
     */
    @SaCheckPermission("endpoint:address:query")
    @GetMapping("/{addressId}")
    public R<EndpointAddressVo> detail(@PathVariable String addressId) {
        return R.ok(catalogService.queryAddress(addressId));
    }

    /**
     * 新增共享地址。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:address:add")
    @Log(title = "系统接入配置-系统地址", businessType = BusinessType.INSERT)
    @PostMapping
    public R<EndpointChangeResultVo> add(@Validated @RequestBody EndpointAddressBo bo) {
        return R.ok(catalogService.createAddress(bo));
    }

    /**
     * 修改共享地址。
     *
     * @param addressId 字符串地址主键
     * @param bo 写请求
     * @return 写结果
     */
    @SaCheckPermission("endpoint:address:edit")
    @Log(title = "系统接入配置-系统地址", businessType = BusinessType.UPDATE)
    @PutMapping("/{addressId}")
    public R<EndpointChangeResultVo> edit(@PathVariable String addressId,
                                          @Validated @RequestBody EndpointAddressBo bo) {
        return R.ok(catalogService.updateAddress(addressId, bo));
    }
}
