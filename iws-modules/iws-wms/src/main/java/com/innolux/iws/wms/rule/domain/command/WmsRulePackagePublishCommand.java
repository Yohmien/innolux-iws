/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 校验并发布规则包版本的命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.command;

import lombok.Data;

import java.util.List;

/**
 * 发布规则包命令。
 *
 * <p>{@code scopeType}/{@code scopeId} 定位唯一生效指针；{@code storageIds} 是同事务写入逻辑归属
 * 版本的储位快照集合。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRulePackagePublishCommand {

    private String commandKey;

    private Long packageVersionId;

    private String scopeType;

    private Long scopeId;

    private List<Long> storageIds;

    private String reason;
}
