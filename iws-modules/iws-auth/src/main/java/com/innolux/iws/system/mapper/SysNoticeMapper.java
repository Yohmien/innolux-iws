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

package com.innolux.iws.system.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.system.domain.SysNotice;
import com.innolux.iws.system.domain.vo.SysNoticeVo;

/**
 * 通知公告表 数据层
 *
 * @author YONGJIE.PENG
 */
public interface SysNoticeMapper extends BaseMapperPlus<SysNotice, SysNoticeVo> {

}
