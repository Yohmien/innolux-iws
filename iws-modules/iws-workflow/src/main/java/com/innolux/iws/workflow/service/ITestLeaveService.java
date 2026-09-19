/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.workflow.domain.bo.TestLeaveBo;
import com.innolux.iws.workflow.domain.vo.TestLeaveVo;

import java.util.Collection;
import java.util.List;

/**
 * 请假示例服务接口，定义请假单查询、维护与流程发起能力。
 *
 * @author YONGJIE.PENG
 * @date 2023-07-21
 */
public interface ITestLeaveService {

    /**
     * 根据主键查询请假单。
     *
     * @param id 主键
     * @return 请假详情
     */
    TestLeaveVo queryById(Long id);

    /**
     * 分页查询请假列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 请假分页数据
     */
    PageResult<TestLeaveVo> queryPageList(TestLeaveBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的请假列表。
     *
     * @param bo 查询条件
     * @return 请假列表
     */
    List<TestLeaveVo> queryList(TestLeaveBo bo);

    /**
     * 新增请假单。
     *
     * @param bo 请假信息
     * @return 新增结果
     */
    TestLeaveVo insertByBo(TestLeaveBo bo);

    /**
     * 提交请假单并发起审批流程。
     *
     * @param bo 请假信息
     * @return 提交结果
     */
    TestLeaveVo submitAndFlowStart(TestLeaveBo bo);

    /**
     * 修改请假单。
     *
     * @param bo 请假信息
     * @return 修改结果
     */
    TestLeaveVo updateByBo(TestLeaveBo bo);

    /**
     * 校验并批量删除请假信息。
     *
     * @param ids 主键集合
     * @return 删除结果
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);
}
