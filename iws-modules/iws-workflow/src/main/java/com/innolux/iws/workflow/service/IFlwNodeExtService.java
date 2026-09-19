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

import com.innolux.iws.workflow.domain.vo.NodeExtVo;

import java.util.Map;

/**
 * 流程节点扩展属性 服务层
 *
 * @author YONGJIE.PENG
 */
public interface IFlwNodeExtService {

    /**
     * 解析扩展属性 JSON 并构建 Node 扩展属性对象
     * <p>
     * 根据传入的 JSON 字符串，将扩展属性分为三类：
     * 1. ButtonPermissionEnum：解析为按钮权限列表，标记每个按钮是否勾选
     * 2. CopySettingEnum：解析为抄送对象 ID 集合
     * 3. VariablesEnum：解析为自定义参数 Map
     *
     * <p>示例 JSON：
     * [
     * {"code": "ButtonPermissionEnum", "value": "back,termination"},
     * {"code": "CopySettingEnum", "value": "1"},
     * {"code": "VariablesEnum", "value": "key1=value1,key2=value2"}
     * ]
     *
     * @param ext      扩展属性 JSON 字符串
     * @param variable 流程变量
     * @return NodeExtVo 对象，封装按钮权限列表、抄送对象集合和自定义参数 Map
     */
    NodeExtVo parseNodeExt(String ext, Map<String, Object> variable);

}
