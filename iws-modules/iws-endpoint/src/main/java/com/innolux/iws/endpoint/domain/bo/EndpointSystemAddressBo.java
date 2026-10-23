/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统地址完整关联写请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 系统地址完整关联写请求。
 *
 * <p>载荷表示保存后应处于启用状态的全部地址；未出现的旧关联将停用。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EndpointSystemAddressBo extends EndpointWriteBo {

    /**
     * 完整有效地址主键集合，使用字符串避免前端精度丢失。
     */
    @NotNull(message = "地址集合不能为空；清空关联请传空数组")
    private List<String> addressIds;
}
