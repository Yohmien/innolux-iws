/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布业务端口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import java.util.Optional;

/**
 * APK 版本发布业务端口。
 *
 * <p>实现必须加入 process 本地动作所在的同一事务：成功时更新发布状态并回写
 * {@code planInstanceId}，任何异常都回滚。实现还必须以 {@code releaseId} 为幂等键，
 * 使同一版本在证据恢复或重复执行时不会产生第二次可见发布。</p>
 */
public interface ApkReleasePublicationPort {

    /**
     * 在调用方事务内发布一个已上传的 APK 版本。
     *
     * @param releaseId 业务版本 ID
     * @param planInstanceId process 计划实例 ID
     */
    void publish(long releaseId, long planInstanceId);

    /**
     * 按计划实例读取已持久化的发布事实。
     *
     * <p>只读当前业务库，不发送 HTTP。动作和状态校验都使用该事实确认发布状态，
     * 不以参数格式或平台成功码替代业务事实。</p>
     *
     * @param planInstanceId process 计划实例 ID
     * @return 发布事实；未关联时为空
     */
    Optional<ApkPublicationFact> findByPlanInstanceId(long planInstanceId);
}
