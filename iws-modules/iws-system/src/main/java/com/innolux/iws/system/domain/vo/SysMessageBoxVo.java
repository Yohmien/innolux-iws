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

package com.innolux.iws.system.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息盒子视图对象
 *
 * @author YONGJIE.PENG
 */
@Data
public class SysMessageBoxVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 系统消息
     */
    private List<SysMessageVo> systemList = new ArrayList<>();

    /**
     * 通知公告消息
     */
    private List<SysMessageVo> noticeList = new ArrayList<>();

    /**
     * 工作流消息
     */
    private List<SysMessageVo> workflowList = new ArrayList<>();
}
