/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 固定 Job 人员资源到原生命名空间和 group 的映射；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

/**
 * 可被人员接口按主键引用的 SnailJob 原生资源。
 */
enum IamJobResourceType {

    JOB("sj_job", true),
    JOB_BATCH("sj_job_task_batch", true),
    JOB_EXECUTOR("sj_job_executor", true),
    JOB_TASK("sj_job_task", true),
    GROUP_CONFIG("sj_group_config", true),
    WORKFLOW("sj_workflow", true),
    WORKFLOW_BATCH("sj_workflow_task_batch", true),
    WORKFLOW_NODE("sj_workflow_node", true),
    RETRY("sj_retry", true),
    RETRY_TASK("sj_retry_task", true),
    RETRY_DEAD_LETTER("sj_retry_dead_letter", true),
    SCENE("sj_retry_scene_config", true),
    NOTIFY_CONFIG("sj_notify_config", true),
    SERVER_NODE("sj_server_node", true),
    NOTIFY_RECIPIENT("sj_notify_recipient", false);

    private final String tableName;
    private final boolean groupScoped;

    IamJobResourceType(String tableName, boolean groupScoped) {
        this.tableName = tableName;
        this.groupScoped = groupScoped;
    }

    String tableName() {
        return tableName;
    }

    boolean groupScoped() {
        return groupScoped;
    }
}
