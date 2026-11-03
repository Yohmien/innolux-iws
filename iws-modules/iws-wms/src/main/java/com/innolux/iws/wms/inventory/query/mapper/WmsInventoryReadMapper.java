/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : WMS 规则、物料、业务任务和追踪的只读查询 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScope;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.AttributeRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.BusinessTaskRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.LoadNodeRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.LocateRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.MaterialDetailRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.MaterialSummaryRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.ProductionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RuleDefinitionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RulePackageMemberRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RulePackageRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RuleVersionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.StorageLocationRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.TraceSummaryRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P7 只读 Mapper。
 *
 * <p>所有动态条件均在 XML 中按显式白名单拼装，不接收前端任意字段名。</p>
 */
public interface WmsInventoryReadMapper {

    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<MaterialSummaryRow> selectMaterialSummaries(
        Page<MaterialSummaryRow> page,
        @Param("query") MaterialQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<MaterialDetailRow> selectMaterialDetails(
        Page<MaterialDetailRow> page,
        @Param("query") MaterialQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    MaterialDetailRow selectMaterialDetail(
        @Param("inventoryId") long inventoryId,
        @Param("scope") WmsInventoryReadScope scope
    );

    ProductionRow selectProduction(@Param("inventoryId") long inventoryId);

    List<AttributeRow> selectProductionAttributes(
        @Param("productionInfoId") long productionInfoId
    );

    List<LoadNodeRow> selectLoadAncestry(@Param("inventoryId") long inventoryId);

    List<LoadNodeRow> selectLoadSubtree(@Param("rootNodeId") long rootNodeId);

    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<Long> selectVisibleInventoryIds(
        @Param("inventoryIds") List<Long> inventoryIds,
        @Param("scope") WmsInventoryReadScope scope
    );

    StorageLocationRow selectStorageLocation(@Param("rootNodeId") long rootNodeId);

    @DataPermission({
        @DataColumn(key = "deptName", value = "history.create_dept"),
        @DataColumn(key = "userName", value = "history.create_by")
    })
    List<TraceSummaryRow> selectTraceSummaries(
        Page<TraceSummaryRow> page,
        @Param("query") TraceQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "history.create_dept"),
        @DataColumn(key = "userName", value = "history.create_by")
    })
    TraceSummaryRow selectTraceSummaryById(
        @Param("traceId") long traceId,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "task.create_dept"),
        @DataColumn(key = "userName", value = "task.create_by")
    })
    List<BusinessTaskRow> selectBusinessTasks(
        Page<BusinessTaskRow> page,
        @Param("query") BusinessTaskQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "definition.create_dept"),
        @DataColumn(key = "userName", value = "definition.create_by")
    })
    List<RuleDefinitionRow> selectRuleDefinitions(
        Page<RuleDefinitionRow> page,
        @Param("query") RuleDefinitionQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "definition.create_dept"),
        @DataColumn(key = "userName", value = "definition.create_by")
    })
    RuleDefinitionRow selectRuleDefinition(
        @Param("definitionId") long definitionId,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "version.create_dept"),
        @DataColumn(key = "userName", value = "version.create_by")
    })
    List<RuleVersionRow> selectRuleVersions(
        @Param("definitionId") long definitionId,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "package_version.create_dept"),
        @DataColumn(key = "userName", value = "package_version.create_by")
    })
    List<RulePackageRow> selectRulePackages(
        Page<RulePackageRow> page,
        @Param("query") RulePackageQuery query,
        @Param("scope") WmsInventoryReadScope scope
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "package_version.create_dept"),
        @DataColumn(key = "userName", value = "package_version.create_by")
    })
    RulePackageRow selectRulePackage(
        @Param("packageVersionId") long packageVersionId,
        @Param("scope") WmsInventoryReadScope scope
    );

    List<RulePackageMemberRow> selectRulePackageMembers(
        @Param("packageVersionId") long packageVersionId
    );

    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<LocateRow> locateMaterials(
        @Param("code") String code,
        @Param("limit") int limit,
        @Param("scope") WmsInventoryReadScope scope
    );
}
