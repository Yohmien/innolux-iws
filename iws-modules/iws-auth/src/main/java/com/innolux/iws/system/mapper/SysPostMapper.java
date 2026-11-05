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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.common.mybatis.core.query.QueryBuilder;
import com.innolux.iws.system.domain.SysPost;
import com.innolux.iws.system.domain.SysUserPost;
import com.innolux.iws.system.domain.vo.SysPostVo;

import java.util.Collection;
import java.util.List;

/**
 * 岗位信息 数据层
 *
 * @author YONGJIE.PENG
 */
public interface SysPostMapper extends BaseMapperPlus<SysPost, SysPostVo>, MPJBaseMapper<SysPost> {

    /**
     * 分页查询岗位列表
     *
     * @param page         分页对象
     * @param queryWrapper 查询条件
     * @return 包含岗位信息的分页结果
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default Page<SysPostVo> selectPagePostList(Page<SysPost> page, Wrapper<SysPost> queryWrapper) {
        return this.selectVoPage(page, queryWrapper);
    }

    /**
     * 查询岗位列表
     *
     * @param queryWrapper 查询条件
     * @return 岗位信息列表
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default List<SysPostVo> selectPostList(Wrapper<SysPost> queryWrapper) {
        return this.selectVoList(queryWrapper);
    }

    /**
     * 根据岗位ID集合查询岗位数量
     *
     * @param postIds 岗位ID列表
     * @return 匹配的岗位数量
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default long selectPostCount(Collection<Long> postIds) {
        return this.lambda().in(SysPost::getPostId, postIds).count();
    }

    /**
     * 根据用户ID查询其关联的岗位列表
     *
     * @param userId 用户ID
     * @return 岗位信息列表
     */
    default List<SysPostVo> selectPostsByUserId(Long userId) {
        return this.selectJoinList(SysPostVo.class, QueryBuilder.lambdaJoin("p", SysPost.class)
            .selectAll(SysPost.class)
            .leftJoin(SysUserPost.class, "sup", SysUserPost::getPostId, SysPost::getPostId)
            .eq("sup", SysUserPost::getUserId, userId)
            .build());
    }

}
