/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 库存业务类型与计划挂载只读目录入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.wms.catalog.WmsInventoryCatalogContract;
import com.innolux.iws.wms.catalog.domain.WmsInventoryCatalogVo;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存业务目录只读入口。
 *
 * @author YONGJIE.PENG
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/inventory")
public class WmsInventoryCatalogController {

    private final WmsInventoryCatalogService catalogService;

    /**
     * 查询业务类型与计划挂载目录。
     *
     * @return 只读目录
     */
    @SaCheckPermission(WmsInventoryCatalogContract.PERMISSION_QUERY)
    @GetMapping("/catalog")
    public R<WmsInventoryCatalogVo> catalog() {
        return R.ok(catalogService.getCatalog());
    }
}
