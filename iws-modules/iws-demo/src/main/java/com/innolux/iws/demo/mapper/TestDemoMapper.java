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

package com.innolux.iws.demo.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.demo.domain.TestDemo;
import com.innolux.iws.demo.domain.vo.TestDemoVo;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 测试单表Mapper接口
 *
 * @author YONGJIE.PENG
 * @date 2021-07-26
 */
public interface TestDemoMapper extends BaseMapperPlus<TestDemo, TestDemoVo> {

    /**
     * 自定义分页查询演示数据。
     *
     * @param page    分页对象
     * @param wrapper 查询条件
     * @return 分页结果
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "user_id")
    })
    Page<TestDemoVo> customPageList(@Param("page") Page<TestDemo> page, @Param("ew") Wrapper<TestDemo> wrapper);

    /**
     * 分页查询演示 VO 列表并应用数据权限。
     *
     * @param page    分页对象
     * @param wrapper 查询条件
     * @return 分页结果
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "user_id")
    })
    default <P extends IPage<TestDemoVo>> P selectVoPage(IPage<TestDemo> page, Wrapper<TestDemo> wrapper) {
        return selectVoPage(page, wrapper, this.currentVoClass());
    }

    /**
     * 查询演示 VO 列表并应用数据权限。
     *
     * @param wrapper 查询条件
     * @return 演示 VO 列表
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "user_id")
    })
    default List<TestDemoVo> selectVoList(Wrapper<TestDemo> wrapper) {
        return selectVoList(wrapper, this.currentVoClass());
    }

    /**
     * 按主键集合查询演示数据并应用数据权限。
     *
     * @param idList 主键集合
     * @return 演示数据列表
     */
    @Override
    @DataPermission(value = {
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "user_id")
    }, joinStr = "AND")
    List<TestDemo> selectByIds(@Param(Constants.COLL) Collection<? extends Serializable> idList);

    /**
     * 按主键更新演示数据并应用数据权限。
     *
     * @param entity 演示数据实体
     * @return 更新行数
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "user_id")
    })
    int updateById(@Param(Constants.ENTITY) TestDemo entity);

}
