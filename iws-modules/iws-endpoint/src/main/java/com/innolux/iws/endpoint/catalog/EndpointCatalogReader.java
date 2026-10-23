/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 供系统端点消费方使用的窄只读目录接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

/**
 * 端点目录窄只读接口。
 *
 * <p>本接口只读取配置，不执行业务动作、不启动任务，也不接受消费方服务注入。</p>
 */
public interface EndpointCatalogReader {

    /**
     * 读取当前目录快照，不建立共享门禁。
     *
     * <p>适用于展示和无写入的只读预览；需要在同库事务中保存定义时使用
     * {@link #lockSnapshot(String)}。</p>
     *
     * @return 当前目录不可变快照
     */
    EndpointCatalogSnapshot snapshot();

    /**
     * 在调用方现有事务中锁定目录控制行并比对版本。
     *
     * <p>MySQL 使用 {@code SELECT ... FOR SHARE}，阻止 endpoint 写事务在定义保存完成前递增目录版本。
     * 方法要求调用方已有事务；版本不一致时抛出 {@link EndpointCatalogConflictException}。
     * 成功返回后，消费方应继续按“目录控制 → 定义/策略”的统一顺序加锁。</p>
     *
     * @param expectedCatalogRevision 调用方预览时读取的目录版本
     * @return 共享锁保护下的目录不可变快照
     */
    EndpointCatalogSnapshot lockSnapshot(String expectedCatalogRevision);

    /**
     * 获取部署配置中的当前运行环境编码。
     *
     * @return 当前环境编码；未配置时返回 null
     */
    String currentEnvironmentCode();
}
