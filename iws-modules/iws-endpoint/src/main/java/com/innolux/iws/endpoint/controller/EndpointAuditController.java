/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点配置独立审计接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.domain.bo.EndpointAuditPageBo;
import com.innolux.iws.endpoint.domain.vo.EndpointAuditVo;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端点配置独立审计接口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/endpoint/audit")
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointAuditController {

    private final EndpointCatalogService catalogService;

    /**
     * 分页查询配置审计。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 审计分页
     */
    @SaCheckPermission("endpoint:audit:query")
    @GetMapping("/page")
    public R<PageResult<EndpointAuditVo>> page(EndpointAuditPageBo query, PageQuery pageQuery) {
        return R.ok(catalogService.pageAudits(query, pageQuery));
    }
}
