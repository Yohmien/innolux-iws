/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包生效指针实体，对应 wms_rule_effective_pointer；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 规则包生效指针实体。
 *
 * <p>按 {@code (scopeType, scopeId)} 唯一；{@code revision} 是发布切换的 CAS 版本，读取与发布
 * 都通过本行建立线性化。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_rule_effective_pointer")
public class WmsRuleEffectivePointer extends BaseEntity {

    @TableId(value = "rule_effective_pointer_id", type = IdType.AUTO)
    private Long ruleEffectivePointerId;

    private String scopeType;

    private Long scopeId;

    private Long packageVersionId;

    private Integer revision;
}
