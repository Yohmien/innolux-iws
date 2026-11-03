/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 创建规则包草稿版本的命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.command;

import lombok.Data;

import java.util.List;

/**
 * 创建规则包草稿命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRulePackageCreateCommand {

    private String commandKey;

    private String packageCode;

    private List<Member> members;

    private String reason;

    /**
     * 规则包成员输入。
     *
     * @author YONGJIE.PENG
     */
    @Data
    public static class Member {

        private String purpose;

        private Long ruleVersionId;
    }
}
