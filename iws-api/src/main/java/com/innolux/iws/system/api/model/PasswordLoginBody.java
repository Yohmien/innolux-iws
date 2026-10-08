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

package com.innolux.iws.system.api.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.innolux.iws.common.core.domain.model.LoginBody;
import org.hibernate.validator.constraints.Length;

/**
 * 密码登录对象
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PasswordLoginBody extends LoginBody {

    /**
     * 用户名
     */
    @NotBlank(message = "{user.username.not.blank}")
    @Length(min = 2, max = 30, message = "{user.username.length.valid}")
    private String username;

    /**
     * 用户密码
     */
    @NotBlank(message = "{user.password.not.blank}")
    @Length(min = 5, max = 30, message = "{user.password.length.valid}")
//    @Pattern(regexp = RegexConstants.PASSWORD, message = "{user.password.format.valid}")
    private String password;

    /**
     * 扫码枪快捷登录标识。
     */
    private Boolean scanLogin;

}
