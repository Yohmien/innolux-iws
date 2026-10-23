/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : ProcessState 持久化入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.process.domain.ProcessState;

/**
 * ProcessState Mapper，运行实体直接作为内部查询投影。
 */
public interface ProcessStateMapper extends BaseMapperPlus<ProcessState, ProcessState> {
}
