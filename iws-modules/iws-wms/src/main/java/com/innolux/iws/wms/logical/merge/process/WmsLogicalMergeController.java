/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 受理逻辑合并父单和固定批次计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartResult;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 逻辑合并业务入口。 */
@RestController
@RequestMapping("/wms/inventory/logical-merges")
public class WmsLogicalMergeController {

    private final WmsLogicalMergeStartService startService;

    private final WmsCompanyAccessPolicy accessPolicy;

    public WmsLogicalMergeController(
        WmsLogicalMergeStartService startService,
        WmsCompanyAccessPolicy accessPolicy
    ) {
        this.startService = startService;
        this.accessPolicy = accessPolicy;
    }

    @PostMapping
    public R<WmsLogicalMergeStartResult> start(
        @RequestBody WmsLogicalMergeStartCommand command
    ) {
        accessPolicy.checkPermission(WmsLogicalMergeStartService.PERMISSION_MANAGE);
        return R.ok(startService.start(command));
    }
}
