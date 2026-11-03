/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 UNKNOWN 查证
 * @Version      : V1.0
 * @Description  : 只读暴露仍待外部查证的分配效果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.allocation.effect.investigation;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadContract;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * UNKNOWN 分配效果只读查证入口。
 *
 * <p>本入口不执行对账、释放或状态变更。</p>
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/inventory/allocation")
public class WmsAllocationEffectInvestigationController {

    private final WmsAllocationEffectInvestigationService investigationService;

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_ALLOCATION_INVESTIGATE)
    @GetMapping("/unknown")
    public R<List<WmsAllocationEffectUnknownView>> unknown(
        @RequestParam(required = false) String businessType,
        @RequestParam(required = false) Long businessId,
        @RequestParam(required = false) String taskKey,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return R.ok(investigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery(businessType, businessId, taskKey, limit)
        ));
    }
}
