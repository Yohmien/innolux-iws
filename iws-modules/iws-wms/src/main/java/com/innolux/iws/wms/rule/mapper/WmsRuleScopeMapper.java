/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则发布目标范围结构与父链读取；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeRow;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeTargetRow;
import com.innolux.iws.wms.rule.domain.WmsRuleScopeZoneRow;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 规则发布目标范围结构、候选及锁后重读。
 *
 * <p>发布事务按 target company、warehouse ID、zone ID、storage ID 顺序调用共享锁方法。
 * 锁后方法只按已收集 ID 读取，不得在取得储位锁后追加新父链。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleScopeMapper {

    /**
     * 读取目标 schema 中规则范围所需表字段。
     *
     * @return 表名列名组合
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<String> selectRequiredColumns();

    /**
     * 无锁读取指定类型的目标解析候选。
     *
     * @param scopeType 已由 Guard 封闭校验的类型
     * @param scopeId 目标 ID
     * @return 候选行
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeTargetRow> selectTargetCandidate(@Param("scopeType") String scopeType,
                                                      @Param("scopeId") Long scopeId);

    /**
     * 无锁读取储位及其当前设备区候选。
     *
     * @param storageIds 命令或持久化快照储位
     * @return 储位和当前设备区投影
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeRow> selectStorageParents(
        @Param("storageIds") Collection<Long> storageIds);

    /**
     * 按公司 ID 升序共享锁定公司。
     *
     * @param companyIds 非空公司 ID
     * @return 公司行
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeRow> lockCompaniesForShare(@Param("companyIds") Collection<Long> companyIds);

    /**
     * 按仓库 ID 升序共享锁定仓库。
     *
     * @param warehouseIds 非空仓库 ID
     * @return 仓库行
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeRow> lockWarehousesForShare(
        @Param("warehouseIds") Collection<Long> warehouseIds);

    /**
     * 按库区 ID 升序共享锁定库区。
     *
     * @param zoneIds 非空库区 ID
     * @return 库区行
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeZoneRow> lockZonesForShare(@Param("zoneIds") Collection<Long> zoneIds);

    /**
     * 按储位 ID 升序共享锁定储位。
     *
     * @param storageIds 非空储位 ID
     * @return 储位行
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    List<WmsRuleScopeRow> lockStoragesForShare(@Param("storageIds") Collection<Long> storageIds);

}
