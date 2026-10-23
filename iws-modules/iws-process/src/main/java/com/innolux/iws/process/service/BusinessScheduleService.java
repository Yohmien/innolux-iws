/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务统一调度配置服务契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.BusinessScheduleBo;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.PlanTypeBo;
import com.innolux.iws.process.api.PlanTypeVo;

import java.util.List;

/**
 * 业务调度配置服务。
 */
public interface BusinessScheduleService {

    /**
     * 分页查询业务调度。
     *
     * @param businessCode 业务编码
     * @param enabled 是否启用
     * @param pageQuery 分页
     * @return 分页结果
     */
    PageResult<BusinessScheduleVo> page(String businessCode, Boolean enabled, PageQuery pageQuery);

    /**
     * 查询详情。
     *
     * @param id 业务调度 ID
     * @return 详情
     */
    BusinessScheduleVo get(long id);

    /**
     * 新增业务调度。
     *
     * @param bo 参数
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    BusinessScheduleVo create(BusinessScheduleBo bo, String commandKey, String reason);

    /**
     * 修改业务调度。
     *
     * @param id 业务调度 ID
     * @param bo 参数
     * @param expectedRevision 版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    BusinessScheduleVo update(long id, BusinessScheduleBo bo, long expectedRevision, String commandKey,
                              String reason);

    /**
     * 逻辑删除业务调度。
     *
     * @param id 业务调度 ID
     * @param expectedRevision 版本
     * @param commandKey 命令键
     * @param reason 原因
     */
    void delete(long id, long expectedRevision, String commandKey, String reason);

    /**
     * 查询计划类型。
     *
     * @param id 业务调度 ID
     * @return 计划类型
     */
    List<PlanTypeVo> planTypes(long id);

    /**
     * 原子保存完整计划类型集合。
     *
     * @param id 业务调度 ID
     * @param planTypes 计划类型
     * @param expectedRevision 版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 最新类型集合
     */
    List<PlanTypeVo> savePlanTypes(long id, List<PlanTypeBo> planTypes, long expectedRevision,
                                   String commandKey, String reason);
}
