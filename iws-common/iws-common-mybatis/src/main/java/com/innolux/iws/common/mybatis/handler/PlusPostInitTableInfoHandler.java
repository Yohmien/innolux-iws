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

package com.innolux.iws.common.mybatis.handler;

import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.handlers.PostInitTableInfoHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import org.apache.ibatis.session.Configuration;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.core.utils.reflect.ReflectUtils;

/**
 * 修改表信息初始化方式
 * 目前用于全局修改是否使用逻辑删除
 *
 * @author YONGJIE.PENG
 */
public class PlusPostInitTableInfoHandler implements PostInitTableInfoHandler {

    /**
     * 表信息初始化后统一调整逻辑删除开关。
     *
     * @param tableInfo     表信息
     * @param configuration MyBatis 配置
     * @return 调整后的表信息
     */
    @Override
    public TableInfo postTableInfo(TableInfo tableInfo, Configuration configuration) {
        String flag = SpringUtils.getProperty("mybatis-plus.enableLogicDelete", "true");
        // 只有关闭时 统一设置false 为true时mp自动判断不处理
        if (!Convert.toBool(flag)) {
            ReflectUtils.setFieldValue(tableInfo, "withLogicDelete", false);
        }
        return tableInfo;
    }

}
