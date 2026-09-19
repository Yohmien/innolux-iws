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

package com.innolux.iws.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 按钮权限视图对象。
 *
 * @author YONGJIE.PENG
 * @date 2025-02-28
 */
@Data
public class ButtonPermissionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 唯一编码
     */
    private String code;

    /**
     * 选项值
     */
    private String value;

    /**
     * 是否显示
     */
    private Boolean show;

    /**
     * 无参构造方法。
     */
    public ButtonPermissionVo() {
    }

    /**
     * 使用按钮编码和显示状态构造按钮权限对象。
     *
     * @param code 按钮编码
     * @param show 是否显示
     */
    public ButtonPermissionVo(String code, Boolean show) {
        this.code = code;
        this.show = show;
    }

}
