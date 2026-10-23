/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件类型能力提供者；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import java.util.List;

/**
 * 业务模块通过该接口发布事件类型元数据。
 */
public interface EventTypeProvider {

    /**
     * 描述本模块提供的事件类型。
     *
     * @return 类型描述
     */
    List<EventTypeDescriptor> describe();
}
