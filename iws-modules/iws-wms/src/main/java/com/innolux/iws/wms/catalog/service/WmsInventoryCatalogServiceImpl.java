/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 动态读取业务目录并解析现有调度计划类型；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.catalog.WmsInventoryCatalogContract;
import com.innolux.iws.wms.catalog.domain.WmsBusinessPlanMountCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsBusinessPlanMountRow;
import com.innolux.iws.wms.catalog.domain.WmsBusinessTypeCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsBusinessTypeRow;
import com.innolux.iws.wms.catalog.domain.WmsInventoryCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsInventoryMountValidationVo;
import com.innolux.iws.wms.catalog.mapper.WmsInventoryCatalogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存业务目录只读查询与新挂载准入校验实现。
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCatalogServiceImpl implements WmsInventoryCatalogService {

    private final WmsInventoryCatalogMapper catalogMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsInventoryCatalogVo getCatalog() {
        StpUtil.checkPermission(WmsInventoryCatalogContract.PERMISSION_QUERY);
        List<WmsBusinessTypeRow> types = catalogMapper.selectBusinessTypes();
        Map<String, WmsBusinessTypeRow> typeByCode = types.stream()
            .collect(Collectors.toMap(WmsBusinessTypeRow::getBusinessTypeCode, Function.identity()));
        List<WmsBusinessPlanMountCatalogVo> mounts = catalogMapper.selectMounts().stream()
            .map(row -> toMountVo(row, typeByCode.get(row.getBusinessTypeCode())))
            .toList();
        return new WmsInventoryCatalogVo(
            WmsInventoryCatalogContract.CONTRACT_VERSION,
            types.stream().map(this::toTypeVo).toList(),
            mounts
        );
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsInventoryMountValidationVo requireMountAllowed(String businessTypeCode, String planTypeCode,
                                                             String scheduleCode, String expectedContractVersion) {
        if (StrUtil.isBlank(businessTypeCode)) {
            throw parentTypeNotRegistered();
        }
        WmsBusinessTypeRow type = catalogMapper.selectBusinessType(businessTypeCode);
        if (!isActiveType(type)) {
            throw parentTypeNotRegistered();
        }
        if (StrUtil.isBlank(planTypeCode) || StrUtil.isBlank(scheduleCode)
            || !WmsInventoryCatalogContract.CONTRACT_VERSION.equals(expectedContractVersion)) {
            throw mountNotAllowed();
        }
        WmsBusinessPlanMountRow mount = catalogMapper.selectMount(businessTypeCode, planTypeCode, scheduleCode);
        if (!isMountAllowed(mount, type)) {
            throw mountNotAllowed();
        }
        return new WmsInventoryMountValidationVo(
            businessTypeCode,
            planTypeCode,
            mount.getScheduleCode(),
            mount.getContractVersion()
        );
    }

    private WmsBusinessTypeCatalogVo toTypeVo(WmsBusinessTypeRow row) {
        return new WmsBusinessTypeCatalogVo(
            row.getBusinessTypeCode(),
            row.getBusinessTypeName(),
            row.getStatus(),
            row.getContractVersion(),
            row.getObjectPolicyKey(),
            row.getRevision() == null ? null : row.getRevision().longValue()
        );
    }

    private WmsBusinessPlanMountCatalogVo toMountVo(WmsBusinessPlanMountRow row, WmsBusinessTypeRow type) {
        return new WmsBusinessPlanMountCatalogVo(
            row.getMountId(),
            row.getBusinessTypeCode(),
            row.getPlanTypeCode(),
            row.getPlanTypeName(),
            row.getScheduleCode(),
            row.getContractVersion(),
            row.getEnabled(),
            row.getRevision() == null ? null : row.getRevision().longValue(),
            isMountAllowed(row, type)
        );
    }

    private boolean isActiveType(WmsBusinessTypeRow type) {
        return type != null
            && WmsInventoryCatalogContract.STATUS_ACTIVE.equals(type.getStatus())
            && WmsInventoryCatalogContract.CONTRACT_VERSION.equals(type.getContractVersion());
    }

    private boolean isMountAllowed(WmsBusinessPlanMountRow mount, WmsBusinessTypeRow type) {
        return mount != null
            && isActiveType(type)
            && Boolean.TRUE.equals(mount.getEnabled())
            && WmsInventoryCatalogContract.CONTRACT_VERSION.equals(mount.getContractVersion())
            && mount.getScheduleId() != null
            && Boolean.TRUE.equals(mount.getScheduleEnabled())
            && "0".equals(mount.getScheduleDelFlag())
            && mount.getPlanTypeId() != null
            && Boolean.TRUE.equals(mount.getPlanTypeEnabled());
    }

    private ServiceException parentTypeNotRegistered() {
        return new ServiceException(
            WmsInventoryCatalogContract.ERROR_PARENT_TYPE_NOT_REGISTERED
                + ": 业务类型未登记、已停用或合同版本不可用",
            HttpStatus.HTTP_CONFLICT
        );
    }

    private ServiceException mountNotAllowed() {
        return new ServiceException(
            WmsInventoryCatalogContract.ERROR_MOUNT_NOT_ALLOWED
                + ": 计划挂载未登记、已停用、调度不匹配或合同版本不可用",
            HttpStatus.HTTP_CONFLICT
        );
    }
}
