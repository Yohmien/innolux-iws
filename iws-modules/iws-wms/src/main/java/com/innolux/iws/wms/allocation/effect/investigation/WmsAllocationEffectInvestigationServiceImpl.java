/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果查证
 * @Version      : V1.0
 * @Description  : 只读合并活动UNKNOWN锁、通道锁及最近审计证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.investigation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.effect.investigation.mapper.WmsAllocationEffectUnknownMapper;
import com.innolux.iws.wms.allocation.effect.investigation.mapper.WmsAllocationEffectUnknownRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScopeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** UNKNOWN 分配效果查证只读实现。 */
@Service
public class WmsAllocationEffectInvestigationServiceImpl
    implements WmsAllocationEffectInvestigationService {

    private static final int MAX_LIMIT = 200;

    private final WmsAllocationEffectUnknownMapper unknownMapper;

    private final WmsInventoryReadScopeResolver readScopeResolver;

    public WmsAllocationEffectInvestigationServiceImpl(
        WmsAllocationEffectUnknownMapper unknownMapper,
        WmsInventoryReadScopeResolver readScopeResolver
    ) {
        this.unknownMapper = unknownMapper;
        this.readScopeResolver = readScopeResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WmsAllocationEffectUnknownView> findUnknown(
        WmsAllocationEffectUnknownQuery query
    ) {
        validate(query);
        return unknownMapper.selectUnknown(
            query.businessType(),
            query.businessId(),
            query.taskKey(),
            query.limit(),
            readScopeResolver.current()
        ).stream().map(this::view).toList();
    }

    private WmsAllocationEffectUnknownView view(WmsAllocationEffectUnknownRow row) {
        return new WmsAllocationEffectUnknownView(
            id(row.getAllocationLockId()),
            id(row.getChannelLockId()),
            id(row.getChannelId()),
            id(row.getStorageId()),
            row.getBusinessType(),
            row.getBusinessName(),
            id(row.getBusinessId()),
            row.getBusinessNo(),
            id(row.getBusinessLineId()),
            id(row.getAllocationUnitId()),
            row.getRoundNo(),
            row.getTaskKey(),
            id(row.getPlanInstanceId()),
            row.getChannelStatus(),
            row.getChannelEffectResult(),
            "LOCKED".equals(row.getChannelStatus())
                && "UNKNOWN".equals(row.getChannelEffectResult()),
            id(row.getLatestUnknownAuditId()),
            row.getLatestUnknownEvidenceJson(),
            row.getUnknownOccurredAt(),
            row.getLockUpdatedAt()
        );
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }

    private void validate(WmsAllocationEffectUnknownQuery query) {
        if (query == null) {
            throw new ServiceException("UNKNOWN分配效果查询条件不能为空");
        }
        if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
            throw new ServiceException("UNKNOWN分配效果查询上限必须为1到" + MAX_LIMIT);
        }
        if (query.businessType() != null && query.businessType().length() > 64) {
            throw new ServiceException("父业务类型长度不能超过64");
        }
        if (query.businessId() != null && query.businessId() <= 0) {
            throw new ServiceException("父业务ID必须为正整数");
        }
        if (query.taskKey() != null && query.taskKey().length() > 96) {
            throw new ServiceException("分配锁任务键长度不能超过96");
        }
    }

}
