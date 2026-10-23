/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录写命令公共信封；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 端点目录写命令公共信封。
 */
@Data
public class EndpointWriteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 客户端命令键，用于幂等重投和异参冲突识别。
     */
    @NotBlank(message = "命令键不能为空")
    @Size(max = 128, message = "命令键不能超过 128 个字符")
    private String commandKey;

    /**
     * 期望版本；创建时为 ABSENT，修改时为当前对象 revision。
     */
    @NotBlank(message = "expectedVersion 不能为空")
    @Size(max = 20, message = "expectedVersion 不能超过 20 个字符")
    private String expectedVersion;

    /**
     * 修改原因。
     */
    @NotBlank(message = "修改原因不能为空")
    @Size(max = 500, message = "修改原因不能超过 500 个字符")
    private String reason;
}
