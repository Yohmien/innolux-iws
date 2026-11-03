/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公共审计
 * @Version      : V1.0
 * @Description  : WMS 受控命令尝试 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WMS 受控命令尝试 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsCommandAttemptMapper
    extends BaseMapperPlus<WmsCommandAttempt, WmsCommandAttempt> {

    /**
     * 按命令键读取已持久化尝试。
     *
     * @param commandKey 命令键
     * @return 尝试记录，不存在时为 null
     */
    @Select("select * from wms_command_attempt where command_key = #{commandKey} limit 1")
    WmsCommandAttempt selectByCommandKey(@Param("commandKey") String commandKey);
}
