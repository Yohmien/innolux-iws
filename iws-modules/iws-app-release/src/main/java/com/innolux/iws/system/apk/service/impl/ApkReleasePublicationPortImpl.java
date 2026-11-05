/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 发布在同一计划事务内更新版本状态；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.enums.ApkReleaseStatus;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.process.ApkPublicationFact;
import com.innolux.iws.system.apk.process.ApkReleasePublicationPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * APK 发布业务端口实现。
 *
 * <p>该方法强制复用 process 本地动作的事务。状态更新使用条件更新完成幂等收敛，
 * 不获取发布版本行锁，也不会在动作内调用外部系统或对象存储。</p>
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApkReleasePublicationPortImpl implements ApkReleasePublicationPort {

    private final ApkReleaseMapper releaseMapper;

    /**
     * 在计划实例事务内发布版本。
     *
     * @param releaseId      发布版本 ID
     * @param planInstanceId 计划实例 ID
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void publish(long releaseId, long planInstanceId) {
        if (releaseId <= 0) {
            throw new ServiceException("APK 发布版本 ID 必须为正整数");
        }
        if (planInstanceId <= 0) {
            throw new ServiceException("APK 发布计划实例 ID 必须为正整数");
        }
        ApkRelease release = releaseMapper.selectById(releaseId);
        if (release == null) {
            throw new ServiceException("APK 发布版本不存在: " + releaseId);
        }
        if (isPublishedBy(release, planInstanceId)) {
            return;
        }
        ensurePublishable(release, planInstanceId);

        int updated = releaseMapper.update(null, new LambdaUpdateWrapper<ApkRelease>()
            .eq(ApkRelease::getReleaseId, releaseId)
            .eq(ApkRelease::getStatus, ApkReleaseStatus.UPLOADED.name())
            .set(ApkRelease::getStatus, ApkReleaseStatus.PUBLISHED.name())
            .set(ApkRelease::getProcessInstanceId, planInstanceId));
        if (updated == 1) {
            log.info("APK 版本发布完成: releaseId={}, planInstanceId={}", releaseId, planInstanceId);
            return;
        }

        ApkRelease latest = releaseMapper.selectById(releaseId);
        if (isPublishedBy(latest, planInstanceId)) {
            return;
        }
        throw new ServiceException("APK 版本状态已变化，不能由当前计划实例发布: " + releaseId);
    }

    /**
     * 按计划实例读取业务库中的 APK 发布事实。
     *
     * @param planInstanceId process 计划实例 ID
     * @return 发布事实
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ApkPublicationFact> findByPlanInstanceId(long planInstanceId) {
        if (planInstanceId <= 0) {
            throw new ServiceException("APK 发布计划实例 ID 必须为正整数");
        }
        Page<ApkRelease> page = releaseMapper.selectPage(new Page<>(1, 2, false),
            Wrappers.<ApkRelease>lambdaQuery()
                .eq(ApkRelease::getProcessInstanceId, planInstanceId)
                .orderByAsc(ApkRelease::getReleaseId));
        if (page.getRecords().isEmpty()) {
            return Optional.empty();
        }
        if (page.getRecords().size() > 1) {
            throw new ServiceException("同一 APK 发布计划实例关联了多个版本: " + planInstanceId);
        }
        ApkRelease release = page.getRecords().getFirst();
        return Optional.of(new ApkPublicationFact(release.getReleaseId(), planInstanceId,
            ApkReleaseStatus.isPublished(release.getStatus())));
    }

    private static boolean isPublishedBy(ApkRelease release, long planInstanceId) {
        return release != null
            && ApkReleaseStatus.isPublished(release.getStatus())
            && Objects.equals(release.getProcessInstanceId(), planInstanceId);
    }

    private static void ensurePublishable(ApkRelease release, long planInstanceId) {
        if (ApkReleaseStatus.isPublished(release.getStatus())) {
            throw new ServiceException("APK 版本已由其他计划实例发布: "
                + release.getProcessInstanceId() + ", 当前实例: " + planInstanceId);
        }
        if (!ApkReleaseStatus.UPLOADED.name().equals(release.getStatus())) {
            throw new ServiceException("APK 版本状态不允许发布: " + release.getStatus());
        }
    }
}
