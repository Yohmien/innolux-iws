/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包草稿与发布服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service;

import com.innolux.iws.wms.rule.domain.command.WmsRulePackageCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.WmsRulePublicationValidation;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.process.WmsExecutionActor;

/**
 * 规则包草稿与发布服务。
 *
 * <p>正式入口由持久计划按 {@code VALIDATE_RULESET → ACTIVATE_RULESET} 推进。
 * 两个动作各自在平台 SAME_TX 事务中调用本服务，只在该动作事务内完成校验或原子发布；
 * 任一失败整体回滚，不能由 Controller 串联调用。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsRulePublishService {

    /**
     * 创建规则包草稿版本。
     *
     * @param command 创建命令
     * @return 规则包版本
     */
    WmsRulePackageVersionVo createPackageVersion(WmsRulePackageCreateCommand command);

    /**
     * 校验并发布规则包版本，切换生效指针并生成逻辑归属版本。
     *
     * @param command 发布命令
     * @return 切换后的生效配置
     */
    WmsRuleEffectiveVo publish(WmsRulePackagePublishCommand command);

    /**
     * 在计划动作事务内只校验发布输入，不激活包或切换生效指针。
     *
     * @param command 发布命令
     * @return 冻结的校验结果
     */
    WmsRulePublicationValidation validateWithinTransaction(
        WmsRulePackagePublishCommand command
    );

    /**
     * 以冻结发起人身份校验发布，不读取当前 HTTP 会话。
     *
     * @param command 发布命令
     * @param actor 冻结发起人
     * @return 发布校验结果
     */
    WmsRulePublicationValidation validateWithinTransaction(
        WmsRulePackagePublishCommand command,
        WmsExecutionActor actor
    );

    /**
     * 在计划动作事务内执行原子发布。
     *
     * @param command 发布命令
     * @return 切换后的生效配置
     */
    WmsRuleEffectiveVo publishWithinTransaction(WmsRulePackagePublishCommand command);

    /**
     * 以冻结发起人身份原子发布，不读取当前 HTTP 会话。
     *
     * @param command 发布命令
     * @param actor 冻结发起人
     * @return 生效事实
     */
    WmsRuleEffectiveVo publishWithinTransaction(
        WmsRulePackagePublishCommand command,
        WmsExecutionActor actor
    );
}
