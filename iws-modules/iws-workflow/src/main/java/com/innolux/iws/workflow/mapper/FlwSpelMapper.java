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

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.workflow.domain.FlowSpel;
import com.innolux.iws.workflow.domain.vo.FlowSpelVo;

/**
 * 流程spel表达式定义Mapper接口
 *
 * @author YONGJIE.PENG
 * @date 2025-07-04
 */
public interface FlwSpelMapper extends BaseMapperPlus<FlowSpel, FlowSpelVo> {

}
