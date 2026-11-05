/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 将 APK 版本元数据写入独立短事务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * APK 版本元数据短事务写入器。
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class ApkReleaseMetadataWriter {

    private final ApkReleaseMapper releaseMapper;

    /**
     * 在独立事务中写入版本，返回前完成提交或回滚。
     *
     * @param release 发布版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void insert(ApkRelease release) {
        if (releaseMapper.insert(release) != 1) {
            throw new ServiceException("APK 版本元数据写入失败");
        }
    }
}
