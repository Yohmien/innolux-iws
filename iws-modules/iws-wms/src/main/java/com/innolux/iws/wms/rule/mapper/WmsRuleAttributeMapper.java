/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 属性目录只读 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.innolux.iws.wms.rule.domain.WmsRuleAttributeRow;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * 属性目录只读 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleAttributeMapper {

    /**
     * 分页读取启用状态的属性目录。
     *
     * @param page 分页参数
     * @return 属性目录分页
     */
    @Select("""
        select attribute_key, source_type, value_type, cardinality, operators_json, unit, scale,
               groupable, status, contract_version, revision
        from wms_attribute_catalog
        where status = 'ACTIVE'
        order by attribute_key
        """)
    IPage<WmsRuleAttributeRow> selectActivePage(IPage<WmsRuleAttributeRow> page);

    /** 精确属性键锁定读；调用方须按键升序逐项加锁。 */
    @Select("""
        select attribute_key, source_type, value_type, cardinality, operators_json, unit, scale,
               groupable, status, contract_version, revision
        from wms_attribute_catalog where attribute_key = #{attributeKey} for share
        """)
    WmsRuleAttributeRow selectExactForShare(@Param("attributeKey") String attributeKey);
}
