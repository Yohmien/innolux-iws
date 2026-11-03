/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包成员实体，对应 wms_rule_package_member；
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
 * 规则包成员实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_rule_package_member")
public class WmsRulePackageMember extends BaseEntity {

    @TableId(value = "package_member_id", type = IdType.AUTO)
    private Long packageMemberId;

    private Long packageVersionId;

    private String purpose;

    private Long ruleVersionId;

    private Integer revision;
}
