/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 储位只读 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.domain.WmsStorage;
import com.innolux.iws.wms.domain.vo.WmsStorageVo;

/**
 * 储位只读 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsStorageMapper extends BaseMapperPlus<WmsStorage, WmsStorageVo> {
}
