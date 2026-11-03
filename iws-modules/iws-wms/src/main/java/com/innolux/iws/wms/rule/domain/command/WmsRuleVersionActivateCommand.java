/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 激活已校验规则版本的命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.command;

import lombok.Data;

/**
 * 激活规则版本命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleVersionActivateCommand {

    private String commandKey;

    private Long ruleVersionId;

    private String expectedDigest;

    private String reason;
}
