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

package com.innolux.iws.system.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.system.domain.bo.SysLoginInfoBo;
import com.innolux.iws.system.domain.vo.SysLoginInfoVo;

import java.util.List;

/**
 * 系统访问日志情况信息 服务层
 *
 * @author YONGJIE.PENG
 */
public interface ISysLoginInfoService {

    /**
     * 分页查询登录日志列表
     *
     * @param loginInfo 查询条件
     * @param pageQuery 分页参数
     * @return 登录日志分页列表
     */
    PageResult<SysLoginInfoVo> selectPageLoginInfoList(SysLoginInfoBo loginInfo, PageQuery pageQuery);

    /**
     * 新增系统登录日志
     *
     * @param bo 访问日志对象
     */
    void insertLoginInfo(SysLoginInfoBo bo);

    /**
     * 查询系统登录日志集合
     *
     * @param loginInfo 访问日志对象
     * @return 登录记录集合
     */
    List<SysLoginInfoVo> selectLoginInfoList(SysLoginInfoBo loginInfo);

    /**
     * 批量删除系统登录日志
     *
     * @param infoIds 需要删除的登录日志ID
     * @return 结果
     */
    int deleteLoginInfoByIds(Long[] infoIds);

    /**
     * 清空系统登录日志
     */
    void cleanLoginInfo();
}
