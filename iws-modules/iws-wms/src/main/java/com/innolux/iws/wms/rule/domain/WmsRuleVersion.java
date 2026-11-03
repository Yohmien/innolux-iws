/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则内容版本实体，对应 wms_rule_version；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 规则内容版本实体。
 *
 * <p>条件与结果以 JSON 文本原样持久化；{@code canonicalDigest} 始终对应当前存储内容。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_rule_version")
public class WmsRuleVersion extends BaseEntity {

    @TableId(value = "rule_version_id", type = IdType.AUTO)
    private Long ruleVersionId;

    private Long ruleDefinitionId;

    private Integer versionNo;

    private String conditionJson;

    private String resultJson;

    /** 服务端生成；历史版本允许为空。 */
    private String attributeBindingsJson;

    private String canonicalDigest;

    private String status;

    private LocalDateTime publishedAt;

    private Long publishedBy;
}
