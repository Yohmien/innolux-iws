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

package com.innolux.iws.workflow.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import com.innolux.iws.workflow.domain.vo.FlowInstanceVo;

/**
 * 实例信息Mapper接口
 *
 * @author YONGJIE.PENG
 * @date 2024-03-02
 */
public interface FlwInstanceMapper extends MPJBaseMapper<FlowInstance> {

    /**
     * 流程实例信息
     *
     * @param page         分页
     * @param queryWrapper 条件
     * @return 结果
     */
    default Page<FlowInstanceVo> selectInstanceList(Page<FlowInstanceVo> page, MPJLambdaWrapper<FlowInstance> queryWrapper) {
        return this.selectJoinPage(page, FlowInstanceVo.class, queryWrapper);
    }

}
