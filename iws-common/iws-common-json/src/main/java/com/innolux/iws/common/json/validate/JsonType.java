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

package com.innolux.iws.common.json.validate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * JSON 类型枚举
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum JsonType {

    /**
     * JSON 对象，例如 {"a":1}
     */
    OBJECT,

    /**
     * JSON 数组，例如 [1,2,3]
     */
    ARRAY,

    /**
     * 任意 JSON 类型，对象或数组都可以
     */
    ANY

}
