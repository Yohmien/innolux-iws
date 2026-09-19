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

package com.innolux.iws.common.liteflow.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.liteflow.core.FailMessageProvider;

/**
 * LiteFlow 失败节点，用于显式表达链路分支不可继续执行。
 *
 * @author YONGJIE.PENG
 */
@LiteflowComponent("fail")
public class FailComponent extends NodeComponent {

    private static final String DEFAULT_MESSAGE = "LiteFlow 链路执行失败";

    @Override
    public void process() {
        throw new ServiceException(getFailMessage());
    }

    /**
     * 优先从上下文读取业务失败提示，未提供时使用默认提示。
     *
     * @return 失败提示
     */
    private String getFailMessage() {
        Object context = getFirstContextBean();
        if (context instanceof FailMessageProvider provider) {
            String message = provider.getFailMessage();
            if (StringUtils.isNotBlank(message)) {
                return message;
            }
        }
        return DEFAULT_MESSAGE;
    }

}
