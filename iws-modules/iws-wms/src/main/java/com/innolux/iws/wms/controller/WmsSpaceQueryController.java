/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 仓库、库区和储位只读查询入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.controller;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.bo.WmsStorageLocateBo;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeStartResult;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeProcess;
import com.innolux.iws.wms.domain.vo.WmsStorageVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseZoneVo;
import com.innolux.iws.wms.service.IWmsSpaceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间主数据只读查询入口。
 *
 * <p>仓库、库区和储位查询分别使用三个粒度权限；权限与公司范围在服务层实时校验。</p>
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/inventory")
public class WmsSpaceQueryController {

    private final IWmsSpaceQueryService spaceQueryService;

    private final WmsSpaceChangeProcess spaceChangeProcess;

    /**
     * 分页查询仓库。
     *
     * @param queryBo 标准查询条件
     * @return 仓库分页
     */
    @PostMapping("/warehouses/page")
    public R<PageResult<WmsWarehouseVo>> warehouseList(@RequestBody IwsTableQueryBo queryBo) {
        return R.ok(spaceQueryService.selectWarehousePage(queryBo));
    }

    /**
     * 分页查询库区。
     *
     * @param queryBo 标准查询条件
     * @return 库区分页
     */
    @PostMapping("/zones/page")
    public R<PageResult<WmsWarehouseZoneVo>> zoneList(@RequestBody IwsTableQueryBo queryBo) {
        return R.ok(spaceQueryService.selectZonePage(queryBo));
    }

    /**
     * 分页查询储位。
     *
     * @param queryBo 标准查询条件
     * @return 储位分页
     */
    @PostMapping("/storages/page")
    public R<PageResult<WmsStorageVo>> storageList(@RequestBody IwsTableQueryBo queryBo) {
        return R.ok(spaceQueryService.selectStoragePage(queryBo));
    }

    /**
     * 按仓库和五项地址精确定位储位。
     *
     * @param locateBo 精确定位请求
     * @return 0 或 1 条储位
     */
    @PostMapping("/storage/locate")
    public R<PageResult<WmsStorageVo>> locateStorage(@RequestBody WmsStorageLocateBo locateBo) {
        return R.ok(spaceQueryService.locateStorage(locateBo));
    }

    /**
     * 受理空间创建、配置或退役命令并创建持久计划任务。
     *
     * @param command 空间变更命令
     * @return 持久单、任务和计划实例
     */
    @PostMapping("/space/changes")
    public R<WmsSpaceChangeStartResult> startSpaceChange(
        @RequestBody WmsSpaceChangeCommand command
    ) {
        return R.ok(spaceChangeProcess.start(command));
    }
}
