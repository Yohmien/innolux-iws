/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则定义 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 规则定义 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleDefinitionMapper extends BaseMapperPlus<WmsRuleDefinition, WmsRuleDefinition> {

    /**
     * 排他锁定并读取规则定义，用于状态修改与版本号串行分配。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @return 规则定义，不存在时为 null
     */
    @Select("select * from wms_rule_definition where rule_definition_id = #{ruleDefinitionId} for update")
    WmsRuleDefinition selectByIdForUpdate(@Param("ruleDefinitionId") Long ruleDefinitionId);

    /**
     * 共享锁定并读取规则定义，用于发布校验时与停用操作隔离。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @return 规则定义，不存在时为 null
     */
    @Select("select * from wms_rule_definition where rule_definition_id = #{ruleDefinitionId} for share")
    WmsRuleDefinition selectByIdForShare(@Param("ruleDefinitionId") Long ruleDefinitionId);
}
