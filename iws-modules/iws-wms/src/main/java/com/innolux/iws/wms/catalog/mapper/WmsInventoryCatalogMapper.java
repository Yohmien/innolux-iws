/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 业务类型、计划挂载与现有计划事件调度的只读适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.mapper;

import com.innolux.iws.wms.catalog.domain.WmsBusinessPlanMountRow;
import com.innolux.iws.wms.catalog.domain.WmsBusinessTypeRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存业务目录只读 Mapper。
 *
 * <p>计划类型和调度编码直接读取现有计划事件表，不建立第二套全局类型引擎。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsInventoryCatalogMapper {

    @Select("""
        select business_type_code, business_type_name, status, contract_version,
               object_policy_key, revision
        from wms_business_type
        order by business_type_code
        """)
    List<WmsBusinessTypeRow> selectBusinessTypes();

    @Select("""
        select m.mount_id, m.business_type_code, m.plan_type_code, m.schedule_code,
               m.contract_version, m.enabled, m.revision,
               s.id as schedule_id, s.enabled as schedule_enabled, s.del_flag as schedule_del_flag,
               pt.id as plan_type_id, pt.plan_type_name, pt.enabled as plan_type_enabled
        from wms_business_plan_mount m
        left join iws_business_schedule s
          on cast(s.business_code as binary) = cast(m.schedule_code as binary)
        left join iws_business_schedule_plan_type pt
          on pt.schedule_id = s.id
         and cast(pt.plan_type as binary) = cast(m.plan_type_code as binary)
        order by m.business_type_code, m.plan_type_code, m.schedule_code, m.mount_id
        """)
    List<WmsBusinessPlanMountRow> selectMounts();

    @Select("""
        select business_type_code, business_type_name, status, contract_version,
               object_policy_key, revision
        from wms_business_type
        where cast(business_type_code as binary) = cast(#{businessTypeCode} as binary)
        """)
    WmsBusinessTypeRow selectBusinessType(@Param("businessTypeCode") String businessTypeCode);

    @Select("""
        select m.mount_id, m.business_type_code, m.plan_type_code, m.schedule_code,
               m.contract_version, m.enabled, m.revision,
               s.id as schedule_id, s.enabled as schedule_enabled, s.del_flag as schedule_del_flag,
               pt.id as plan_type_id, pt.plan_type_name, pt.enabled as plan_type_enabled
        from wms_business_plan_mount m
        left join iws_business_schedule s
          on cast(s.business_code as binary) = cast(m.schedule_code as binary)
        left join iws_business_schedule_plan_type pt
          on pt.schedule_id = s.id
         and cast(pt.plan_type as binary) = cast(m.plan_type_code as binary)
        where cast(m.business_type_code as binary) = cast(#{businessTypeCode} as binary)
          and cast(m.plan_type_code as binary) = cast(#{planTypeCode} as binary)
          and cast(m.schedule_code as binary) = cast(#{scheduleCode} as binary)
        """)
    WmsBusinessPlanMountRow selectMount(@Param("businessTypeCode") String businessTypeCode,
                                        @Param("planTypeCode") String planTypeCode,
                                        @Param("scheduleCode") String scheduleCode);
}
