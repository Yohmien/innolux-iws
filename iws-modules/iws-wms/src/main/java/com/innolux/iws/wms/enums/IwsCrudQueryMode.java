/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准查询模式；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.enums;

/**
 * 标准查询模式。
 *
 * @author YONGJIE.PENG
 */
public enum IwsCrudQueryMode {
    EQ,
    LIKE_RIGHT,
    CONTAINS,
    BETWEEN,
    IN
}
