/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 在原生人员 Controller 方法执行前校验 namespace、group 和资源归属；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.controller.GroupConfigController;
import com.aizuda.snailjob.server.web.controller.DashboardController;
import com.aizuda.snailjob.server.web.controller.JobBatchController;
import com.aizuda.snailjob.server.web.controller.JobController;
import com.aizuda.snailjob.server.web.controller.JobExecutorController;
import com.aizuda.snailjob.server.web.controller.JobTaskController;
import com.aizuda.snailjob.server.web.controller.NotifyConfigController;
import com.aizuda.snailjob.server.web.controller.NotifyRecipientController;
import com.aizuda.snailjob.server.web.controller.RetryController;
import com.aizuda.snailjob.server.web.controller.RetryDeadLetterController;
import com.aizuda.snailjob.server.web.controller.RetryTaskController;
import com.aizuda.snailjob.server.web.controller.SceneConfigController;
import com.aizuda.snailjob.server.web.controller.WorkflowBatchController;
import com.aizuda.snailjob.server.web.controller.WorkflowController;
import com.aizuda.snailjob.server.web.controller.WorkflowNodeController;
import com.aizuda.snailjob.server.web.model.request.BatchDeleteRetryDeadLetterVO;
import com.aizuda.snailjob.server.web.model.request.BatchDeleteRetryTaskVO;
import com.aizuda.snailjob.server.web.model.request.BatchRollBackRetryDeadLetterVO;
import com.aizuda.snailjob.server.web.model.request.BatchUpdateRetryStatusVO;
import com.aizuda.snailjob.server.web.model.request.ExportGroupVO;
import com.aizuda.snailjob.server.web.model.request.ExportJobVO;
import com.aizuda.snailjob.server.web.model.request.ExportNotifyRecipientVO;
import com.aizuda.snailjob.server.web.model.request.ExportSceneVO;
import com.aizuda.snailjob.server.web.model.request.ExportWorkflowVO;
import com.aizuda.snailjob.server.web.model.request.GenerateRetryIdempotentIdVO;
import com.aizuda.snailjob.server.web.model.request.JobBatchQueryVO;
import com.aizuda.snailjob.server.web.model.request.JobExecutorQueryVO;
import com.aizuda.snailjob.server.web.model.request.JobQueryVO;
import com.aizuda.snailjob.server.web.model.request.JobRequestWebVO;
import com.aizuda.snailjob.server.web.model.request.JobTaskQueryVO;
import com.aizuda.snailjob.server.web.model.request.JobTriggerVO;
import com.aizuda.snailjob.server.web.model.request.ManualTriggerTaskRequestVO;
import com.aizuda.snailjob.server.web.model.request.NotifyConfigQueryVO;
import com.aizuda.snailjob.server.web.model.request.NotifyConfigRequestVO;
import com.aizuda.snailjob.server.web.model.request.NotifyRecipientRequestVO;
import com.aizuda.snailjob.server.web.model.request.ParseLogsVO;
import com.aizuda.snailjob.server.web.model.request.RetryDeadLetterQueryVO;
import com.aizuda.snailjob.server.web.model.request.RetryQueryVO;
import com.aizuda.snailjob.server.web.model.request.RetrySaveRequestVO;
import com.aizuda.snailjob.server.web.model.request.RetryTaskQueryVO;
import com.aizuda.snailjob.server.web.model.request.RetryUpdateExecutorNameRequestVO;
import com.aizuda.snailjob.server.web.model.request.SceneConfigQueryVO;
import com.aizuda.snailjob.server.web.model.request.SceneConfigRequestVO;
import com.aizuda.snailjob.server.web.model.request.ServerNodeLabelsUpdateRequestVO;
import com.aizuda.snailjob.server.web.model.request.ServerNodeQueryVO;
import com.aizuda.snailjob.server.web.model.request.ServerNodeStatusUpdateRequestVO;
import com.aizuda.snailjob.server.web.model.request.StatusUpdateRequestWebVO;
import com.aizuda.snailjob.server.web.model.request.WorkflowBatchQueryVO;
import com.aizuda.snailjob.server.web.model.request.WorkflowQueryVO;
import com.aizuda.snailjob.server.web.model.request.WorkflowRequestVO;
import com.aizuda.snailjob.server.web.model.request.WorkflowTriggerVO;
import com.aizuda.snailjob.server.web.util.ImportUtils;
import com.aizuda.snailjob.template.datasource.persistence.po.NotifyConfig;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Job 人员接口的显式授权矩阵。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class IamJobGroupAuthorizationAspect {

    private final IamJobGroupAccessGuard accessGuard;

    /**
     * 在原生 Controller 方法进入事务或业务调用前完成授权。
     *
     * @param joinPoint 原生 Controller 调用
     * @return 原生返回值
     * @throws Throwable 原生调用异常
     */
    @Around("execution(public * com.aizuda.snailjob.server.web.controller..*(..))")
    public Object authorize(ProceedingJoinPoint joinPoint) throws Throwable {
        Class<?> controller = AopUtils.getTargetClass(joinPoint.getTarget());
        String method = joinPoint.getSignature().getName();
        Object[] arguments = joinPoint.getArgs();
        authorize(controller, method, arguments);
        Object result = joinPoint.proceed(arguments);
        return filterResults(controller, method, result);
    }

    private void authorize(
        Class<?> controller,
        String method,
        Object[] arguments
    ) throws IOException {
        if (controller == JobController.class) {
            authorizeJob(method, arguments);
        } else if (controller == JobBatchController.class) {
            authorizeJobBatch(method, arguments);
        } else if (controller == JobExecutorController.class) {
            authorizeJobExecutor(method, arguments);
        } else if (controller == JobTaskController.class) {
            authorizeJobTask(arguments[0]);
        } else if (controller == GroupConfigController.class) {
            authorizeGroup(method, arguments);
        } else if (controller == WorkflowController.class) {
            authorizeWorkflow(method, arguments);
        } else if (controller == WorkflowBatchController.class) {
            authorizeWorkflowBatch(method, arguments);
        } else if (controller == WorkflowNodeController.class) {
            authorizeWorkflowNode(arguments);
        } else if (controller == RetryController.class) {
            authorizeRetry(method, arguments);
        } else if (controller == RetryTaskController.class) {
            authorizeRetryTask(method, arguments);
        } else if (controller == RetryDeadLetterController.class) {
            authorizeRetryDeadLetter(method, arguments);
        } else if (controller == SceneConfigController.class) {
            authorizeScene(method, arguments);
        } else if (controller == NotifyConfigController.class) {
            authorizeNotifyConfig(method, arguments);
        } else if (controller == NotifyRecipientController.class) {
            authorizeNotifyRecipient(method, arguments);
        } else if (controller == DashboardController.class) {
            authorizeDashboard(method, arguments);
        }
    }

    private void authorizeJob(String method, Object[] arguments) throws IOException {
        switch (method) {
            case "checkBizIdExists" -> optionalResource(IamJobResourceType.JOB, arguments[1]);
            case "getJobPage" -> optionalGroup(((JobQueryVO) arguments[0]).getGroupName());
            case "getJobList" -> accessGuard.requireGroup((String) arguments[0]);
            case "getJobDetail" -> resource(IamJobResourceType.JOB, arguments[0]);
            case "saveJob" -> group(((JobRequestWebVO) arguments[0]).getGroupName());
            case "updateJob" -> {
                JobRequestWebVO request = (JobRequestWebVO) arguments[0];
                accessGuard.requireResource(
                    IamJobResourceType.JOB,
                    request.getId(),
                    request.getGroupName()
                );
            }
            case "updateJobStatus" -> resource(
                IamJobResourceType.JOB,
                ((StatusUpdateRequestWebVO) arguments[0]).getId()
            );
            case "deleteJobById" -> resources(IamJobResourceType.JOB, arguments[0]);
            case "getJobNameList" -> {
                optionalResource(IamJobResourceType.JOB, arguments[1]);
                optionalGroup((String) arguments[2]);
            }
            case "trigger" -> resource(
                IamJobResourceType.JOB,
                ((JobTriggerVO) arguments[0]).getJobId()
            );
            case "importScene" -> authorizeJobImport(arguments);
            case "exportGroup" -> authorizeJobExport((ExportJobVO) arguments[0]);
            default -> {
                // 本控制器其余方法不引用 namespace/group 资源。
            }
        }
    }

    private void authorizeJobBatch(String method, Object[] arguments) {
        switch (method) {
            case "getJobBatchPage" -> {
                JobBatchQueryVO query = (JobBatchQueryVO) arguments[0];
                optionalResource(IamJobResourceType.JOB, query.getJobId());
                optionalGroup(query.getGroupName());
            }
            case "getJobBatchDetail" -> resource(IamJobResourceType.JOB_BATCH, arguments[0]);
            case "stop", "retry" -> resource(IamJobResourceType.JOB_BATCH, arguments[0]);
            case "deleteJobBatchByIds" -> resources(IamJobResourceType.JOB_BATCH, arguments[0]);
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeJobExecutor(String method, Object[] arguments) {
        switch (method) {
            case "getJobPage", "getJobList" -> optionalGroup(
                ((JobExecutorQueryVO) arguments[0]).getGroupName()
            );
            case "getJobExecutorsDetail" -> resource(
                IamJobResourceType.JOB_EXECUTOR,
                arguments[0]
            );
            case "deleteJobExecutorsById" -> resources(
                IamJobResourceType.JOB_EXECUTOR,
                arguments[0]
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeJobTask(Object argument) {
        JobTaskQueryVO query = (JobTaskQueryVO) argument;
        boolean hasFilter = query.getJobId() != null
            || query.getTaskBatchId() != null
            || query.getParentId() != null;
        if (!hasFilter && !accessGuard.isAdministrator()) {
            throw new IamJobGroupAccessDeniedException(
                "普通用户查询 Job task 必须指定任务或批次"
            );
        }
        optionalResource(IamJobResourceType.JOB, query.getJobId());
        optionalResource(IamJobResourceType.JOB_BATCH, query.getTaskBatchId());
        optionalResource(IamJobResourceType.JOB_TASK, query.getParentId());
    }

    private void authorizeGroup(String method, Object[] arguments) {
        switch (method) {
            case "getGroupConfigForPage" -> optionalGroup(
                ((com.aizuda.snailjob.server.web.model.request.GroupConfigQueryVO)
                    arguments[0]).getGroupName()
            );
            case "getGroupConfigByGroupName", "getOnlinePods" ->
                accessGuard.requireGroup((String) arguments[0]);
            case "getAllGroupNameList" -> {
                if (arguments.length > 0) {
                    authorizeNamespaceList(arguments[0]);
                }
            }
            case "exportGroup" -> authorizeGroupExport((ExportGroupVO) arguments[0]);
            default -> {
                // 新增、修改、删除、导入和分区表接口由原生 ADMIN 权限约束。
            }
        }
    }

    private void authorizeWorkflow(String method, Object[] arguments) throws IOException {
        switch (method) {
            case "checkBizIdExists" -> optionalResource(
                IamJobResourceType.WORKFLOW,
                arguments[1]
            );
            case "saveWorkflow" -> group(((WorkflowRequestVO) arguments[0]).getGroupName());
            case "listPage" -> optionalGroup(((WorkflowQueryVO) arguments[0]).getGroupName());
            case "updateWorkflow" -> {
                WorkflowRequestVO request = (WorkflowRequestVO) arguments[0];
                accessGuard.requireResource(
                    IamJobResourceType.WORKFLOW,
                    request.getId(),
                    request.getGroupName()
                );
            }
            case "getWorkflowDetail" -> resource(
                IamJobResourceType.WORKFLOW,
                arguments[0]
            );
            case "updateStatus" -> resource(
                IamJobResourceType.WORKFLOW,
                ((StatusUpdateRequestWebVO) arguments[0]).getId()
            );
            case "deleteByIds" -> resources(
                IamJobResourceType.WORKFLOW,
                arguments[0]
            );
            case "trigger" -> resource(
                IamJobResourceType.WORKFLOW,
                ((WorkflowTriggerVO) arguments[0]).getWorkflowId()
            );
            case "getWorkflowNameList" -> {
                optionalResource(IamJobResourceType.WORKFLOW, arguments[1]);
                optionalGroup((String) arguments[2]);
            }
            case "importScene" -> authorizeWorkflowImport(arguments);
            case "export" -> authorizeWorkflowExport((ExportWorkflowVO) arguments[0]);
            default -> {
                // 表达式校验不引用持久化资源。
            }
        }
    }

    private void authorizeWorkflowBatch(String method, Object[] arguments) {
        switch (method) {
            case "listPage" -> {
                WorkflowBatchQueryVO query = (WorkflowBatchQueryVO) arguments[0];
                optionalResource(IamJobResourceType.WORKFLOW, query.getWorkflowId());
                optionalGroup(query.getGroupName());
            }
            case "getWorkflowBatchDetail" -> resource(
                IamJobResourceType.WORKFLOW_BATCH,
                arguments[0]
            );
            case "stop" -> resource(IamJobResourceType.WORKFLOW_BATCH, arguments[0]);
            case "deleteByIds" -> resources(
                IamJobResourceType.WORKFLOW_BATCH,
                arguments[0]
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeWorkflowNode(Object[] arguments) {
        resource(IamJobResourceType.WORKFLOW_NODE, arguments[0]);
        resource(IamJobResourceType.WORKFLOW_BATCH, arguments[1]);
    }

    private void authorizeRetry(String method, Object[] arguments) {
        switch (method) {
            case "getRetryTaskPage" -> optionalGroup(((RetryQueryVO) arguments[0]).getGroupName());
            case "getRetryTaskById" -> resource(IamJobResourceType.RETRY, arguments[0]);
            case "updateRetryTaskStatus" -> resource(
                IamJobResourceType.RETRY,
                ((StatusUpdateRequestWebVO) arguments[0]).getId()
            );
            case "saveRetryTask" -> group(
                ((RetrySaveRequestVO) arguments[0]).getGroupName()
            );
            case "idempotentIdGenerate" -> group(
                ((GenerateRetryIdempotentIdVO) arguments[0]).getGroupName()
            );
            case "updateRetryTaskExecutorName" -> authorizeRetryIds(
                (RetryUpdateExecutorNameRequestVO) arguments[0]
            );
            case "batchDeleteRetry" -> authorizeRetryIds(
                (BatchDeleteRetryTaskVO) arguments[0]
            );
            case "batchUpdateRetryStatus" -> group(
                ((BatchUpdateRetryStatusVO) arguments[0]).getGroupName()
            );
            case "parseLogs" -> group(((ParseLogsVO) arguments[0]).getGroupName());
            case "manualTriggerRetryTask" -> authorizeManualRetry(
                (ManualTriggerTaskRequestVO) arguments[0]
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeRetryTask(String method, Object[] arguments) {
        switch (method) {
            case "getRetryTaskPage" -> optionalGroup(
                ((RetryTaskQueryVO) arguments[0]).getGroupName()
            );
            case "getRetryTaskById", "stopById", "deleteById" ->
                resource(IamJobResourceType.RETRY_TASK, arguments[0]);
            case "batchDelete" -> resources(
                IamJobResourceType.RETRY_TASK,
                arguments[0]
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeRetryDeadLetter(String method, Object[] arguments) {
        switch (method) {
            case "getRetryDeadLetterPage" -> optionalGroup(
                ((RetryDeadLetterQueryVO) arguments[0]).getGroupName()
            );
            case "getRetryDeadLetterById" -> {
                accessGuard.requireGroup((String) arguments[0]);
                accessGuard.requireResource(
                    IamJobResourceType.RETRY_DEAD_LETTER,
                    (Long) arguments[1],
                    (String) arguments[0]
                );
            }
            case "rollback" -> resources(
                IamJobResourceType.RETRY_DEAD_LETTER,
                ((BatchRollBackRetryDeadLetterVO) arguments[0]).getIds()
            );
            case "batchDelete" -> resources(
                IamJobResourceType.RETRY_DEAD_LETTER,
                ((BatchDeleteRetryDeadLetterVO) arguments[0]).getIds()
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeScene(String method, Object[] arguments) throws IOException {
        switch (method) {
            case "getSceneConfigPageList" -> optionalGroup(
                ((SceneConfigQueryVO) arguments[0]).getGroupName()
            );
            case "getSceneConfigList" -> accessGuard.requireGroup((String) arguments[0]);
            case "getSceneConfigDetail", "updateStatus" ->
                resource(IamJobResourceType.SCENE, arguments[0]);
            case "saveSceneConfig", "updateSceneConfig" -> group(
                ((SceneConfigRequestVO) arguments[0]).getGroupName()
            );
            case "importScene" -> authorizeSceneImport(arguments);
            case "export" -> authorizeSceneExport((ExportSceneVO) arguments[0]);
            case "deleteByIds" -> resources(IamJobResourceType.SCENE, arguments[0]);
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeNotifyConfig(String method, Object[] arguments) {
        switch (method) {
            case "getNotifyConfigList" -> optionalGroup(
                ((NotifyConfigQueryVO) arguments[0]).getGroupName()
            );
            case "getNotifyConfigBySystemTaskTypeList" -> {
                // 返回值在 filterNotifyConfigs 中按 group 过滤。
            }
            case "getNotifyConfigDetail", "updateStatus" ->
                resource(IamJobResourceType.NOTIFY_CONFIG, arguments[0]);
            case "saveNotify" -> group(
                ((NotifyConfigRequestVO) arguments[0]).getGroupName()
            );
            case "updateNotify" -> authorizeNotifyUpdate(
                (NotifyConfigRequestVO) arguments[0]
            );
            case "batchDeleteNotify" -> resources(
                IamJobResourceType.NOTIFY_CONFIG,
                arguments[0]
            );
            default -> {
                // 无额外资源主键。
            }
        }
    }

    private void authorizeNotifyRecipient(String method, Object[] arguments) {
        switch (method) {
            case "updateNotifyRecipient" -> resource(
                IamJobResourceType.NOTIFY_RECIPIENT,
                ((NotifyRecipientRequestVO) arguments[0]).getId()
            );
            case "batchDeleteByIds" -> resources(
                IamJobResourceType.NOTIFY_RECIPIENT,
                arguments[0]
            );
            case "exportGroup" -> {
                Set<Long> ids = ((ExportNotifyRecipientVO) arguments[0])
                    .getNotifyRecipientIds();
                if (ids != null && !ids.isEmpty()) {
                    resources(IamJobResourceType.NOTIFY_RECIPIENT, ids);
                }
            }
            default -> {
                // 新增、列表和导入均由原生当前 namespace 约束。
            }
        }
    }

    private void authorizeDashboard(String method, Object[] arguments) {
        if ("pods".equals(method)) {
            String groupName = ((ServerNodeQueryVO) arguments[0]).getGroupName();
            if (accessGuard.isAdministrator()) {
                return;
            }
            if (groupName == null || groupName.isBlank()) {
                throw new IamJobGroupAccessDeniedException(
                    "普通用户查询在线节点必须指定已授权 Job group"
                );
            }
            accessGuard.requireGroup(groupName);
        } else if ("updatePodsStatus".equals(method)) {
            resource(
                IamJobResourceType.SERVER_NODE,
                ((ServerNodeStatusUpdateRequestVO) arguments[0]).getId()
            );
        } else if ("updatePodsLabels".equals(method)) {
            resource(
                IamJobResourceType.SERVER_NODE,
                ((ServerNodeLabelsUpdateRequestVO) arguments[0]).getId()
            );
        } else if ("retryLineList".equals(method)) {
            optionalGroup(
                ((com.aizuda.snailjob.server.web.model.request.LineQueryVO)
                    arguments[0]).getGroupName()
            );
        } else if ("jobLineList".equals(method)) {
            optionalGroup(
                ((com.aizuda.snailjob.server.web.model.request.JobLineQueryVO)
                    arguments[0]).getGroupName()
            );
        }
    }

    private void authorizeJobImport(Object[] arguments) throws IOException {
        MultipartFile file = (MultipartFile) arguments[0];
        byte[] content = file.getBytes();
        List<JobRequestWebVO> requests = ImportUtils.parseList(
            buffered(file, content),
            JobRequestWebVO.class
        );
        requests.forEach(request -> group(request.getGroupName()));
        arguments[0] = buffered(file, content);
    }

    private void authorizeWorkflowImport(Object[] arguments) throws IOException {
        MultipartFile file = (MultipartFile) arguments[0];
        byte[] content = file.getBytes();
        List<WorkflowRequestVO> requests = ImportUtils.parseList(
            buffered(file, content),
            WorkflowRequestVO.class
        );
        requests.forEach(request -> group(request.getGroupName()));
        arguments[0] = buffered(file, content);
    }

    private void authorizeSceneImport(Object[] arguments) throws IOException {
        MultipartFile file = (MultipartFile) arguments[0];
        byte[] content = file.getBytes();
        List<SceneConfigRequestVO> requests = ImportUtils.parseList(
            buffered(file, content),
            SceneConfigRequestVO.class
        );
        requests.forEach(request -> group(request.getGroupName()));
        arguments[0] = buffered(file, content);
    }

    private void authorizeJobExport(ExportJobVO export) {
        if (export.getJobIds() != null && !export.getJobIds().isEmpty()) {
            requireResourcesWithGroup(
                IamJobResourceType.JOB,
                export.getJobIds(),
                export.getGroupName()
            );
        } else {
            accessGuard.requireExportGroup(export.getGroupName());
        }
    }

    private void authorizeWorkflowExport(ExportWorkflowVO export) {
        if (export.getWorkflowIds() != null && !export.getWorkflowIds().isEmpty()) {
            requireResourcesWithGroup(
                IamJobResourceType.WORKFLOW,
                export.getWorkflowIds(),
                export.getGroupName()
            );
        } else {
            accessGuard.requireExportGroup(export.getGroupName());
        }
    }

    private void authorizeSceneExport(ExportSceneVO export) {
        if (export.getSceneIds() != null && !export.getSceneIds().isEmpty()) {
            requireResourcesWithGroup(
                IamJobResourceType.SCENE,
                export.getSceneIds(),
                export.getGroupName()
            );
        } else {
            accessGuard.requireExportGroup(export.getGroupName());
        }
    }

    private void authorizeGroupExport(ExportGroupVO export) {
        if (export.getGroupIds() != null && !export.getGroupIds().isEmpty()) {
            requireResourcesWithGroup(
                IamJobResourceType.GROUP_CONFIG,
                export.getGroupIds(),
                export.getGroupName()
            );
        } else {
            accessGuard.requireExportGroup(export.getGroupName());
        }
    }

    private void authorizeRetryIds(RetryUpdateExecutorNameRequestVO request) {
        accessGuard.requireGroup(request.getGroupName());
        requireResourcesWithGroup(
            IamJobResourceType.RETRY,
            request.getIds(),
            request.getGroupName()
        );
    }

    private void authorizeRetryIds(BatchDeleteRetryTaskVO request) {
        accessGuard.requireGroup(request.getGroupName());
        requireResourcesWithGroup(
            IamJobResourceType.RETRY,
            request.getIds(),
            request.getGroupName()
        );
    }

    private void authorizeManualRetry(ManualTriggerTaskRequestVO request) {
        accessGuard.requireGroup(request.getGroupName());
        requireResourcesWithGroup(
            IamJobResourceType.RETRY,
            request.getRetryIds(),
            request.getGroupName()
        );
    }

    private void authorizeNotifyUpdate(NotifyConfigRequestVO request) {
        group(request.getGroupName());
        accessGuard.requireResource(
            IamJobResourceType.NOTIFY_CONFIG,
            request.getId(),
            request.getGroupName()
        );
    }

    private void authorizeNamespaceList(Object value) {
        if (accessGuard.isAdministrator()) {
            return;
        }
        if (!(value instanceof List<?> namespaceIds) || namespaceIds.isEmpty()) {
            throw new IamJobGroupAccessDeniedException(
                "普通用户查询 group 配置必须指定当前 namespace"
            );
        }
        namespaceIds.forEach(namespaceId ->
            accessGuard.requireCurrentNamespace(String.valueOf(namespaceId))
        );
    }

    private Object filterResults(Class<?> controller, String method, Object result) {
        if (!(result instanceof List<?> list)) {
            return result;
        }
        if (controller == NotifyConfigController.class
            && "getNotifyConfigBySystemTaskTypeList".equals(method)) {
            if (accessGuard.isAdministrator()) {
                return result;
            }
            List<NotifyConfig> filtered = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof NotifyConfig notifyConfig
                    && accessGuard.isResourceAccessible(
                        IamJobResourceType.NOTIFY_CONFIG,
                        notifyConfig.getId()
                    )) {
                    filtered.add(notifyConfig);
                }
            }
            return filtered;
        }
        if (controller == GroupConfigController.class
            && "getAllGroupNameList".equals(method)
            && list.stream().anyMatch(
                com.aizuda.snailjob.server.web.model.response.GroupConfigResponseVO.class
                    ::isInstance
            )) {
            if (accessGuard.isAdministrator()) {
                return result;
            }
            return list.stream()
                .filter(item -> item instanceof com.aizuda.snailjob.server.web.model.response
                    .GroupConfigResponseVO group
                    && accessGuard.isGroupAccessible(group.getGroupName()))
                .toList();
        }
        return result;
    }

    private void group(String groupName) {
        accessGuard.requireGroup(groupName);
    }

    private void optionalGroup(String groupName) {
        if (groupName != null && !groupName.isBlank()) {
            accessGuard.requireGroup(groupName);
        }
    }

    private void resource(IamJobResourceType type, Object id) {
        accessGuard.requireResource(type, (Long) id);
    }

    private void optionalResource(IamJobResourceType type, Object id) {
        if (id instanceof Long value) {
            accessGuard.requireResource(type, value);
        }
    }

    private void resources(IamJobResourceType type, Object ids) {
        if (ids instanceof Set<?> values) {
            accessGuard.requireResources(type, values.stream().map(Long.class::cast).toList());
        } else if (ids instanceof List<?> values) {
            accessGuard.requireResources(type, values.stream().map(Long.class::cast).toList());
        } else {
            throw new IamJobGroupAccessDeniedException("Job 资源主键集合格式错误");
        }
    }

    private void requireResourcesWithGroup(
        IamJobResourceType type,
        Iterable<Long> ids,
        String expectedGroupName
    ) {
        for (Long id : ids) {
            accessGuard.requireResource(type, id, expectedGroupName);
        }
    }

    private MultipartFile buffered(MultipartFile source, byte[] content) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return source.getName();
            }

            @Override
            public String getOriginalFilename() {
                return source.getOriginalFilename();
            }

            @Override
            public String getContentType() {
                return source.getContentType();
            }

            @Override
            public boolean isEmpty() {
                return content.length == 0;
            }

            @Override
            public long getSize() {
                return content.length;
            }

            @Override
            public byte[] getBytes() {
                return content.clone();
            }

            @Override
            public InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(content);
            }

            @Override
            public void transferTo(File dest) throws IOException {
                Files.write(dest.toPath(), content);
            }
        };
    }
}
