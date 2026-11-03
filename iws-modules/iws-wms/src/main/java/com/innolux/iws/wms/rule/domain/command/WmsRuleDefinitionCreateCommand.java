/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 创建规则定义并生成首个 DRAFT 版本的命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.command;

import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * 创建规则定义命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleDefinitionCreateCommand {

    private String commandKey;

    private String ruleKey;

    private String purpose;

    private String ruleName;

    private String scopeType;

    private JsonNode condition;

    private JsonNode result;

    private String reason;
}
