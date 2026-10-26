/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 发布版本 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationRow;
import org.apache.ibatis.annotations.Param;

/**
 * APK 发布版本 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface ApkReleaseMapper extends BaseMapperPlus<ApkRelease, ApkRelease> {

    /**
     * 按安装包分页查询应用汇总。
     *
     * @param page        分页参数
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @return 应用汇总分页
     */
    Page<ApkApplicationRow> selectApplicationPage(Page<ApkApplicationRow> page,
                                                  @Param("packageName") String packageName,
                                                  @Param("appName") String appName);

    /**
     * 统计应用名称对应且满足状态的安装包数量。
     *
     * @param appName 应用名称
     * @param status  发布状态，空表示全部
     * @return 不同安装包数量
     */
    Long countDistinctPackagesByAppName(@Param("appName") String appName,
                                        @Param("status") String status);
}
