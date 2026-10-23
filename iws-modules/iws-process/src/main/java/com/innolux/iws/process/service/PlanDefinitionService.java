/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本聚合服务契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEventPatchBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.api.EditCapabilitiesVo;
import com.innolux.iws.process.api.ExternalSystemPostingOptionsVO;
import com.innolux.iws.process.api.PostingImpactVO;
import com.innolux.iws.process.api.PostingModeVectorBo;
import com.innolux.iws.process.api.PostingPolicySaveResultVo;
import com.innolux.iws.process.api.SystemPolicyBo;

import java.util.List;

/**
 * 计划定义版本聚合服务。
 */
public interface PlanDefinitionService {

    /**
     * 计划版本分页。
     *
     * @param planCode 计划编码
     * @param status 状态
     * @param planRole 角色
     * @param pageQuery 分页
     * @return 分页结果
     */
    PageResult<DefinitionSummaryVo> page(String planCode, String status, String planRole, PageQuery pageQuery);

    /**
     * 完整版本详情。
     *
     * @param versionId 版本 ID
     * @return 详情
     */
    DefinitionDetailVo get(long versionId);

    /**
     * 创建初始草稿。
     *
     * @param draft 草稿头
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo createDraft(DefinitionDraftBo draft, String commandKey, String reason);

    /**
     * 完整草稿原子保存。
     *
     * @param versionId 版本 ID
     * @param draft 草稿
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo saveDraft(long versionId, DefinitionDraftBo draft, long expectedVersion,
                                 String commandKey, String reason);

    /**
     * 保存基本信息。
     *
     * @param versionId 版本 ID
     * @param draft 基本信息
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo saveBase(long versionId, DefinitionDraftBo draft, long expectedVersion,
                                String commandKey, String reason);

    /**
     * 保存单个状态。
     *
     * @param versionId 版本 ID
     * @param state 状态
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo saveState(long versionId, DefinitionStateBo state, long expectedVersion,
                                 String commandKey, String reason);

    /**
     * 保存单个事件及关联边。
     *
     * @param versionId 版本 ID
     * @param patch 事件补丁
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo saveEvent(long versionId, DefinitionEventPatchBo patch, String commandKey, String reason);

    /**
     * 删除单个最新草稿事件。
     *
     * @param versionId 版本 ID
     * @param eventCode 事件编码
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    DefinitionDetailVo deleteEvent(long versionId, String eventCode, long expectedVersion,
                                   String commandKey, String reason);

    /**
     * 原子保存完整系统模式向量。
     *
     * @param versionId 版本 ID
     * @param vector 模式向量
     * @return 详情
     */
    PostingPolicySaveResultVo saveSystemPolicies(long versionId, PostingModeVectorBo vector);

    /**
     * 完整图外部系统汇总与当前环境地址摘要。
     *
     * @param versionId 版本 ID
     * @return 展示与政策系统行
     */
    ExternalSystemPostingOptionsVO externalSystems(long versionId);

    /**
     * 完整模式向量影响预览，不探活、不发送。
     *
     * @param versionId 版本 ID
     * @param vector 完整模式向量
     * @return 影响报告
     */
    PostingImpactVO previewSystemPolicies(long versionId, PostingModeVectorBo vector);

    /**
     * 校验草稿。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    DefinitionValidationVo validate(long versionId);

    /**
     * 发布最新草稿。
     *
     * @param versionId 版本 ID
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 校验与发布结果
     */
    DefinitionValidationVo publish(long versionId, long expectedVersion, String commandKey, String reason);

    /**
     * 复制历史版本为新草稿。
     *
     * @param versionId 来源版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 新草稿
     */
    DefinitionDetailVo copy(long versionId, String commandKey, String reason);

    /**
     * 逻辑删除最新未引用草稿。
     *
     * @param versionId 版本 ID
     * @param expectedVersion 聚合版本
     * @param commandKey 命令键
     * @param reason 原因
     */
    void delete(long versionId, long expectedVersion, String commandKey, String reason);

    /**
     * 查询字段级编辑能力。
     *
     * @param versionId 版本 ID
     * @return 编辑能力
     */
    EditCapabilitiesVo editCapabilities(long versionId);

    /**
     * 查询系统模式。
     *
     * @param versionId 版本 ID
     * @return 系统模式
     */
    List<SystemPolicyBo> systemPolicies(long versionId);
}
