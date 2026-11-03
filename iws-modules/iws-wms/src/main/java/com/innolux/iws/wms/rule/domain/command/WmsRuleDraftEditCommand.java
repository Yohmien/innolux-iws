/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 编辑 DRAFT 规则版本的命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.command;

import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * 编辑草稿命令。
 *
 * <p>{@code expectedDigest} 是读取草稿时得到的规范化摘要，用作无 revision 列草稿表的乐观并发令牌。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleDraftEditCommand {

    private String commandKey;

    private Long ruleVersionId;

    private String expectedDigest;

    private JsonNode condition;

    private JsonNode result;

    private String reason;
}
