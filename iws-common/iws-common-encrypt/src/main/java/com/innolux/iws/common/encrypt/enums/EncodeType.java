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

package com.innolux.iws.common.encrypt.enums;

/**
 * 编码类型
 *
 * @author YONGJIE.PENG
 * @version 4.6.0
 */
public enum EncodeType {

    /**
     * 默认使用yml配置
     */
    DEFAULT,

    /**
     * base64编码
     */
    BASE64,

    /**
     * 16进制编码
     */
    HEX

}
