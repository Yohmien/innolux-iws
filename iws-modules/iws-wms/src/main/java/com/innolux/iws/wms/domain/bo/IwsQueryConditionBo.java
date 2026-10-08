/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准查询条件 BO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标准查询条件 BO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsQueryConditionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String field;

    private String queryMode;

    private Object value;

    private Object beginValue;

    private Object endValue;
}
