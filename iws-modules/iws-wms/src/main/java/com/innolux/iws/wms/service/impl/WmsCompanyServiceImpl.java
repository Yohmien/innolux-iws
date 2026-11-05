/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司最小闭环查询、CAS 写入、范围替换与审计编排；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.domain.WmsCompany;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import com.innolux.iws.wms.domain.WmsCompanyManagementGrant;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import com.innolux.iws.wms.domain.bo.IwsBulkConditionBo;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsSortConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.dto.IwsCrudPermissionDto;
import com.innolux.iws.wms.domain.dto.WmsCompanyCreateDTO;
import com.innolux.iws.wms.domain.dto.WmsCompanyEnableCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyManagementScopeReplaceCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyUpdateDTO;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.domain.vo.WmsCompanyManagementScopeVo;
import com.innolux.iws.wms.domain.vo.WmsCompanyRoleVo;
import com.innolux.iws.wms.mapper.WmsCompanyManagementGrantMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementScopeMapper;
import com.innolux.iws.wms.mapper.WmsCompanyMapper;
import com.innolux.iws.wms.service.IWmsCompanyService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.support.company.WmsCompanyAuditService;
import com.innolux.iws.wms.support.company.WmsCompanyContract;
import com.innolux.iws.wms.support.meta.IwsCrudMetaRegistry;
import com.innolux.iws.wms.support.query.IwsCrudConditionSupport;
import com.innolux.iws.wms.support.schema.WmsDirectWriteSchemaGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公司最小闭环服务实现。
 *
 * @author YONGJIE.PENG
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WmsCompanyServiceImpl implements IWmsCompanyService {

    private final WmsCompanyMapper companyMapper;

    private final WmsCompanyManagementScopeMapper scopeMapper;

    private final WmsCompanyManagementGrantMapper grantMapper;

    private final IamIdentityFacts identityFacts;

    private final WmsCompanyAccessPolicy accessPolicy;

    private final WmsCompanyAuditService auditService;

    private final IwsCrudMetaRegistry metaRegistry;

    private final PlatformTransactionManager transactionManager;

    private final WmsDirectWriteSchemaGate directWriteSchemaGate;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> selectPage(IwsTableQueryBo queryBo) {
        accessPolicy.checkPermission(WmsCompanyContract.PERMISSION_QUERY);
        QueryWrapper<WmsCompany> wrapper = buildQueryWrapper(queryBo);
        accessPolicy.applyCompanyScope(wrapper);
        Page<WmsCompany> page = companyMapper.selectPage(
            new Page<>(queryBo.getPageNum(), queryBo.getPageSize()),
            wrapper
        );
        List<Map<String, Object>> rows = page.getRecords().stream()
            .map(this::toRow)
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public void create(WmsCompanyCreateDTO dto) {
        assertOutsideCommandTransaction();
        LocalDateTime occurredAt = LocalDateTime.now();
        String digest = auditService.digest(dto);
        boolean[] completed = {false};
        try {
            commandTransaction(Isolation.REPEATABLE_READ).executeWithoutResult(status -> {
                directWriteSchemaGate.acquire();
                doCreate(dto, digest, occurredAt);
                completed[0] = true;
            });
        } catch (RuntimeException error) {
            if (WmsDirectWriteSchemaGate.isPreAcceptanceRejection(error)) {
                throw error;
            }
            if (handleCommandFailure(
                WmsCompanyContract.ACTION_CREATE,
                null,
                dto.getCommandKey(),
                dto,
                digest,
                error,
                occurredAt,
                completed[0]
            )) {
                return;
            }
            throw error;
        }
    }

    private void doCreate(WmsCompanyCreateDTO dto, String digest, LocalDateTime occurredAt) {
        Long firstCompanyId = companyMapper.selectFirstCompanyIdForUpdate();
        boolean firstCompany = firstCompanyId == null;
        accessPolicy.checkCreateAccess(firstCompany);
        if (!auditService.assertReusable(dto.getCommandKey(), digest, WmsCompanyContract.ACTION_CREATE)) {
            return;
        }

        long duplicate = companyMapper.selectCount(
            new LambdaQueryWrapper<WmsCompany>()
                .eq(WmsCompany::getCompanyCode, dto.getCompanyCode())
        );
        if (duplicate > 0) {
            throw new ServiceException("公司编码已存在", HttpStatus.HTTP_CONFLICT);
        }

        WmsCompany company = new WmsCompany();
        company.setCompanyCode(dto.getCompanyCode());
        company.setCompanyName(dto.getCompanyName());
        company.setRemark(dto.getRemark());
        company.setEnabled(Boolean.FALSE);
        company.setLifecycleStatus(WmsCompanyContract.LIFECYCLE_ACTIVE);
        company.setRevision(1L);
        companyMapper.insert(company);

        WmsCompanyManagementScope scope = new WmsCompanyManagementScope();
        scope.setCompanyId(company.getCompanyId());
        scope.setScopeRevision(0L);
        scope.setUpdatedBy(LoginHelper.getUserId());
        scope.setUpdatedAt(occurredAt);
        scopeMapper.insert(scope);

        auditService.recordSuccess(
            WmsCompanyContract.ACTION_CREATE,
            company.getCompanyId(),
            dto.getCommandKey(),
            dto,
            null,
            companySnapshot(company),
            "创建公司，保持默认禁用",
            occurredAt
        );
    }

    @Override
    public void update(WmsCompanyUpdateDTO dto) {
        assertOutsideCommandTransaction();
        LocalDateTime occurredAt = LocalDateTime.now();
        String digest = auditService.digest(dto);
        boolean[] completed = {false};
        try {
            commandTransaction(Isolation.READ_COMMITTED).executeWithoutResult(status -> {
                directWriteSchemaGate.acquire();
                doUpdate(dto, digest, occurredAt);
                completed[0] = true;
            });
        } catch (RuntimeException error) {
            if (WmsDirectWriteSchemaGate.isPreAcceptanceRejection(error)) {
                throw error;
            }
            if (handleCommandFailure(
                WmsCompanyContract.ACTION_UPDATE,
                dto.getCompanyId(),
                dto.getCommandKey(),
                dto,
                digest,
                error,
                occurredAt,
                completed[0]
            )) {
                return;
            }
            throw error;
        }
    }

    private void doUpdate(WmsCompanyUpdateDTO dto, String digest, LocalDateTime occurredAt) {
        WmsCompany company = lockCompany(dto.getCompanyId());
        accessPolicy.lockAndCheckCompany(dto.getCompanyId());
        accessPolicy.checkPermissionForShare(WmsCompanyContract.PERMISSION_UPDATE);
        if (!auditService.assertReusable(dto.getCommandKey(), digest, WmsCompanyContract.ACTION_UPDATE)) {
            return;
        }
        assertCompanyRevision(company, dto.getExpectedRevision());

        String oldName = company.getCompanyName();
        String oldRemark = company.getRemark();
        boolean nameChanged = !StrUtil.equals(oldName, dto.getCompanyName());
        boolean remarkChanged = dto.isRemarkProvided()
            && !StrUtil.equals(StrUtil.nullToEmpty(oldRemark), StrUtil.nullToEmpty(dto.getRemark()));
        if (!nameChanged && !remarkChanged) {
            throw new ServiceException("NO_CHANGE: 公司名称和备注均未变化", HttpStatus.HTTP_BAD_REQUEST);
        }

        long nextRevision = dto.getExpectedRevision() + 1;
        var update = Wrappers.<WmsCompany>lambdaUpdate()
            .eq(WmsCompany::getCompanyId, dto.getCompanyId())
            .eq(WmsCompany::getRevision, dto.getExpectedRevision())
            .set(WmsCompany::getRevision, nextRevision)
            .set(WmsCompany::getUpdateBy, LoginHelper.getUserId())
            .set(WmsCompany::getUpdateTime, occurredAt);
        if (nameChanged) {
            update.set(WmsCompany::getCompanyName, dto.getCompanyName());
        }
        if (remarkChanged) {
            update.set(WmsCompany::getRemark, dto.getRemark());
        }
        int updated = companyMapper.update(null, update);
        if (updated != 1) {
            throw versionConflict("VERSION_CONFLICT: 公司修订已变化");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("revision", dto.getExpectedRevision());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("revision", nextRevision);
        if (nameChanged) {
            before.put("companyName", oldName);
            after.put("companyName", dto.getCompanyName());
        }
        if (remarkChanged) {
            before.put("remark", oldRemark);
            after.put("remark", dto.getRemark());
        }

        auditService.recordSuccess(
            WmsCompanyContract.ACTION_UPDATE,
            dto.getCompanyId(),
            dto.getCommandKey(),
            dto,
            before,
            after,
            dto.getReason(),
            occurredAt
        );
    }

    @Override
    public void setEnabled(Long companyId, WmsCompanyEnableCommand command, boolean enabled) {
        assertOutsideCommandTransaction();
        LocalDateTime occurredAt = LocalDateTime.now();
        String action = enabled ? WmsCompanyContract.ACTION_ENABLE : WmsCompanyContract.ACTION_DISABLE;
        Map<String, Object> commandInput = Map.of("companyId", companyId, "command", command);
        String digest = auditService.digest(commandInput);
        boolean[] completed = {false};
        try {
            commandTransaction(Isolation.READ_COMMITTED).executeWithoutResult(status -> {
                directWriteSchemaGate.acquire();
                doSetEnabled(companyId, command, enabled, action, commandInput, digest, occurredAt);
                completed[0] = true;
            });
        } catch (RuntimeException error) {
            if (WmsDirectWriteSchemaGate.isPreAcceptanceRejection(error)) {
                throw error;
            }
            if (handleCommandFailure(
                action,
                companyId,
                command.getCommandKey(),
                commandInput,
                digest,
                error,
                occurredAt,
                completed[0]
            )) {
                return;
            }
            throw error;
        }
    }

    private void doSetEnabled(Long companyId, WmsCompanyEnableCommand command, boolean enabled,
                              String action, Map<String, Object> commandInput, String digest,
                              LocalDateTime occurredAt) {
        WmsCompany company = lockCompany(companyId);
        accessPolicy.lockAndCheckCompany(companyId);
        accessPolicy.checkPermissionForShare(enabled
            ? WmsCompanyContract.PERMISSION_ENABLE
            : WmsCompanyContract.PERMISSION_DISABLE);
        if (!auditService.assertReusable(command.getCommandKey(), digest, action)) {
            return;
        }
        assertCompanyRevision(company, command.getExpectedRevision());
        if (!WmsCompanyContract.LIFECYCLE_ACTIVE.equals(company.getLifecycleStatus())) {
            throw new ServiceException("只有 ACTIVE 公司可以启停", HttpStatus.HTTP_CONFLICT);
        }

        long nextRevision = command.getExpectedRevision() + 1;
        int updated = companyMapper.update(
            null,
            Wrappers.<WmsCompany>lambdaUpdate()
                .eq(WmsCompany::getCompanyId, companyId)
                .eq(WmsCompany::getRevision, command.getExpectedRevision())
                .set(WmsCompany::getEnabled, enabled)
                .set(WmsCompany::getRevision, nextRevision)
                .set(WmsCompany::getUpdateBy, LoginHelper.getUserId())
                .set(WmsCompany::getUpdateTime, occurredAt)
        );
        if (updated != 1) {
            throw versionConflict("VERSION_CONFLICT: 公司修订已变化");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("enabled", company.getEnabled());
        before.put("revision", command.getExpectedRevision());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", enabled);
        after.put("revision", nextRevision);
        auditService.recordSuccess(
            action,
            companyId,
            command.getCommandKey(),
            commandInput,
            before,
            after,
            command.getReason(),
            occurredAt
        );
    }

    @Override
    public WmsCompanyManagementScopeVo replaceManagementScope(
        Long companyId,
        WmsCompanyManagementScopeReplaceCommand command
    ) {
        assertOutsideCommandTransaction();
        LocalDateTime occurredAt = LocalDateTime.now();
        String digest = auditService.digest(command);
        boolean[] completed = {false};
        try {
            return commandTransaction(Isolation.READ_COMMITTED).execute(status -> {
                directWriteSchemaGate.acquire();
                WmsCompanyManagementScopeVo result =
                    doReplaceManagementScope(companyId, command, digest, occurredAt);
                completed[0] = true;
                return result;
            });
        } catch (RuntimeException error) {
            if (WmsDirectWriteSchemaGate.isPreAcceptanceRejection(error)) {
                throw error;
            }
            if (handleCommandFailure(
                WmsCompanyContract.ACTION_REPLACE_MANAGEMENT_SCOPE,
                companyId,
                command.getCommandKey(),
                command,
                digest,
                error,
                occurredAt,
                completed[0]
            )) {
                return committedManagementScope(companyId, error);
            }
            throw error;
        }
    }

    private WmsCompanyManagementScopeVo doReplaceManagementScope(
        Long companyId,
        WmsCompanyManagementScopeReplaceCommand command,
        String digest,
        LocalDateTime occurredAt
    ) {
        if (!companyId.equals(command.getCompanyId())) {
            throw new ServiceException("路径公司ID与请求体不一致", HttpStatus.HTTP_BAD_REQUEST);
        }
        lockCompany(companyId);
        WmsCompanyManagementScope scope = accessPolicy.lockAndCheckScopeManage(companyId);
        accessPolicy.checkScopeManageForShare();
        if (!auditService.assertReusable(
            command.getCommandKey(), digest, WmsCompanyContract.ACTION_REPLACE_MANAGEMENT_SCOPE
        )) {
            return buildManagementScope(companyId, scope, true);
        }
        if (!ObjectUtil.equals(scope.getScopeRevision(), command.getExpectedScopeRevision())) {
            throw new ServiceException(
                "SCOPE_VERSION_CONFLICT: 范围修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }

        Set<Long> targetRoleIds = new LinkedHashSet<>(command.getRoleIds());
        validateRoles(targetRoleIds);
        List<Long> beforeRoleIds = grantMapper.selectList(
            new LambdaQueryWrapper<WmsCompanyManagementGrant>()
                .eq(WmsCompanyManagementGrant::getCompanyId, companyId)
                .orderByAsc(WmsCompanyManagementGrant::getRoleId)
                .last("FOR UPDATE")
        ).stream().map(WmsCompanyManagementGrant::getRoleId).toList();

        grantMapper.delete(
            new LambdaQueryWrapper<WmsCompanyManagementGrant>()
                .eq(WmsCompanyManagementGrant::getCompanyId, companyId)
        );
        if (!targetRoleIds.isEmpty()) {
            List<WmsCompanyManagementGrant> grants = new ArrayList<>();
            for (Long roleId : targetRoleIds) {
                WmsCompanyManagementGrant grant = new WmsCompanyManagementGrant();
                grant.setCompanyId(companyId);
                grant.setRoleId(roleId);
                grant.setCreatedBy(LoginHelper.getUserId());
                grant.setCreatedAt(occurredAt);
                grants.add(grant);
            }
            grantMapper.insertBatch(grants);
        }

        long nextScopeRevision = command.getExpectedScopeRevision() + 1;
        int updated = scopeMapper.update(
            null,
            Wrappers.<WmsCompanyManagementScope>lambdaUpdate()
                .eq(WmsCompanyManagementScope::getCompanyId, companyId)
                .eq(WmsCompanyManagementScope::getScopeRevision, command.getExpectedScopeRevision())
                .set(WmsCompanyManagementScope::getScopeRevision, nextScopeRevision)
                .set(WmsCompanyManagementScope::getUpdatedBy, LoginHelper.getUserId())
                .set(WmsCompanyManagementScope::getUpdatedAt, occurredAt)
        );
        if (updated != 1) {
            throw new ServiceException(
                "SCOPE_VERSION_CONFLICT: 范围修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("roleIds", beforeRoleIds);
        before.put("scopeRevision", command.getExpectedScopeRevision());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleIds", targetRoleIds);
        after.put("scopeRevision", nextScopeRevision);
        auditService.recordSuccess(
            WmsCompanyContract.ACTION_REPLACE_MANAGEMENT_SCOPE,
            companyId,
            command.getCommandKey(),
            command,
            before,
            after,
            command.getReason(),
            occurredAt
        );
        WmsCompanyManagementScope updatedScope = new WmsCompanyManagementScope();
        updatedScope.setCompanyId(companyId);
        updatedScope.setScopeRevision(nextScopeRevision);
        updatedScope.setUpdatedBy(LoginHelper.getUserId());
        updatedScope.setUpdatedAt(occurredAt);
        return buildManagementScope(companyId, updatedScope, true);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsCompanyManagementScopeVo queryManagementScope(Long companyId) {
        WmsCompanyManagementScope scope = accessPolicy.checkScopeManage(companyId);
        return buildManagementScope(companyId, scope, false);
    }

    @Override
    public IwsCrudTableMetaVo decorateMeta(IwsCrudTableMetaVo source) {
        if (!WmsCompanyContract.TABLE_CODE.equals(source.getTableCode())) {
            return source;
        }
        accessPolicy.checkPermission(WmsCompanyContract.PERMISSION_QUERY);
        IwsCrudTableMetaVo meta = BeanUtil.copyProperties(source, IwsCrudTableMetaVo.class);
        Map<String, Boolean> actions = new LinkedHashMap<>();
        actions.put("query", accessPolicy.hasPermission(WmsCompanyContract.PERMISSION_QUERY));
        actions.put("create", accessPolicy.hasPermission(WmsCompanyContract.PERMISSION_CREATE)
            && accessPolicy.hasScopeAll());
        actions.put("update", accessPolicy.hasPermission(WmsCompanyContract.PERMISSION_UPDATE));
        actions.put("enable", accessPolicy.hasPermission(WmsCompanyContract.PERMISSION_ENABLE));
        actions.put("disable", accessPolicy.hasPermission(WmsCompanyContract.PERMISSION_DISABLE));
        actions.put("managementGrant", accessPolicy.hasScopeManage() && accessPolicy.hasScopeAll());
        actions.put("warehouseRelation", Boolean.FALSE);
        actions.put("visibility", Boolean.FALSE);
        actions.put("rootPermission", Boolean.FALSE);
        actions.put("retire", Boolean.FALSE);
        actions.put("delete", Boolean.FALSE);
        actions.put("import", Boolean.FALSE);
        actions.put("export", Boolean.FALSE);
        actions.put("template", Boolean.FALSE);
        actions.put("column", Boolean.FALSE);
        actions.put("crossCompany", Boolean.FALSE);
        meta.setAllowedActions(actions);
        return meta;
    }

    private WmsCompany lockCompany(Long companyId) {
        WmsCompany company = companyMapper.selectByIdForUpdate(companyId);
        if (company == null) {
            throw new ServiceException("公司不存在", HttpStatus.HTTP_NOT_FOUND);
        }
        return company;
    }

    private void assertCompanyRevision(WmsCompany company, Long expectedRevision) {
        if (!ObjectUtil.equals(company.getRevision(), expectedRevision)) {
            throw versionConflict("VERSION_CONFLICT: 公司修订已变化");
        }
    }

    private ServiceException versionConflict(String message) {
        return new ServiceException(message, HttpStatus.HTTP_CONFLICT);
    }

    private void validateRoles(Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        List<Long> existingRoleIds = identityFacts.existingRoleIdsForShare(roleIds);
        Set<Long> existing = new LinkedHashSet<>(existingRoleIds);
        if (existing.size() != roleIds.size()) {
            Set<Long> missing = new LinkedHashSet<>(roleIds);
            missing.removeAll(existing);
            throw new ServiceException("目标角色不存在: " + missing, HttpStatus.HTTP_BAD_REQUEST);
        }
        Map<Long, String> roleNames = identityFacts.roleNames(roleIds);
        if (roleNames == null || roleNames.size() != roleIds.size()) {
            throw new ServiceException("目标角色名称读取不完整", HttpStatus.HTTP_CONFLICT);
        }
    }

    private WmsCompanyManagementScopeVo buildManagementScope(
        Long companyId,
        WmsCompanyManagementScope scope,
        boolean currentRead
    ) {
        LambdaQueryWrapper<WmsCompanyManagementGrant> grantCondition =
            new LambdaQueryWrapper<WmsCompanyManagementGrant>()
                .eq(WmsCompanyManagementGrant::getCompanyId, companyId)
                .orderByAsc(WmsCompanyManagementGrant::getRoleId);
        if (currentRead) {
            grantCondition.last("FOR UPDATE");
        }
        List<WmsCompanyManagementGrant> grantQuery = grantMapper.selectList(grantCondition);
        List<Long> roleIds = grantQuery.stream()
            .map(WmsCompanyManagementGrant::getRoleId)
            .toList();
        Map<Long, String> roleNames = roleIds.isEmpty()
            ? Map.of()
            : identityFacts.roleNames(roleIds);
        List<WmsCompanyRoleVo> roles = roleIds.stream()
            .map(roleId -> new WmsCompanyRoleVo(roleId, roleNames.get(roleId)))
            .toList();

        WmsCompanyManagementScopeVo result = new WmsCompanyManagementScopeVo();
        result.setCompanyId(companyId);
        result.setScopeRevision(scope.getScopeRevision());
        result.setRoleIds(roleIds);
        result.setRoles(roles);
        result.setUpdatedBy(scope.getUpdatedBy());
        result.setUpdatedAt(scope.getUpdatedAt());
        return result;
    }

    private QueryWrapper<WmsCompany> buildQueryWrapper(IwsTableQueryBo queryBo) {
        Map<String, IwsCrudColumnMetaDto> columns = columnMap();
        QueryWrapper<WmsCompany> wrapper = new QueryWrapper<>();
        for (IwsQueryConditionBo condition : queryBo.getConditions()) {
            IwsCrudColumnMetaDto column = columns.get(condition.getField());
            if (column == null) {
                continue;
            }
            IwsCrudConditionSupport.apply(wrapper, column, condition);
        }
        for (IwsBulkConditionBo condition : queryBo.getBulkConditions()) {
            IwsCrudColumnMetaDto column = columns.get(condition.getField());
            if (column != null && condition.getValues() != null && !condition.getValues().isEmpty()) {
                wrapper.in(column.getDbColumn(), condition.getValues());
            }
        }
        applySort(wrapper, columns, queryBo.getSorts());
        return wrapper;
    }

    private void applySort(QueryWrapper<WmsCompany> wrapper,
                           Map<String, IwsCrudColumnMetaDto> columns,
                           List<IwsSortConditionBo> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            wrapper.orderByDesc("update_time").orderByDesc("company_id");
            return;
        }
        for (IwsSortConditionBo sort : sorts) {
            IwsCrudColumnMetaDto column = columns.get(sort.getField());
            if (column != null) {
                wrapper.orderBy(true, "asc".equalsIgnoreCase(sort.getOrder()), column.getDbColumn());
            }
        }
    }

    private Map<String, IwsCrudColumnMetaDto> columnMap() {
        IwsCrudTableMetaVo meta = metaRegistry.findByTableCode(WmsCompanyContract.TABLE_CODE)
            .orElseThrow(() -> new IllegalStateException("未维护表元数据: " + WmsCompanyContract.TABLE_CODE));
        return meta.getColumns().stream()
            .collect(Collectors.toMap(IwsCrudColumnMetaDto::getField, Function.identity()));
    }

    private Map<String, Object> toRow(WmsCompany company) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("companyId", company.getCompanyId());
        row.put("companyCode", company.getCompanyCode());
        row.put("companyName", company.getCompanyName());
        row.put("remark", company.getRemark());
        row.put("enabled", company.getEnabled());
        row.put("lifecycleStatus", company.getLifecycleStatus());
        row.put("revision", company.getRevision());
        row.put("createTime", company.getCreateTime());
        row.put("updateTime", company.getUpdateTime());
        return row;
    }

    private Map<String, Object> companySnapshot(WmsCompany company) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("companyId", company.getCompanyId());
        snapshot.put("companyCode", company.getCompanyCode());
        snapshot.put("companyName", company.getCompanyName());
        snapshot.put("remark", company.getRemark());
        snapshot.put("enabled", company.getEnabled());
        snapshot.put("lifecycleStatus", company.getLifecycleStatus());
        snapshot.put("revision", company.getRevision());
        return snapshot;
    }

    private TransactionTemplate commandTransaction(Isolation isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 创建保留 REPEATABLE READ 以串行化首家公司引导；更新/启停/范围替换使用 READ COMMITTED，
        // 避免授权明细空范围 gap lock 与并发插入互相等待；互斥由公司/范围头行锁和授权明细排他锁提供。
        template.setIsolationLevel(isolation.value());
        return template;
    }

    /**
     * 命令入口必须自行拥有完整事务边界，禁止加入调用方已有事务。
     *
     * <p>REQUIRED 加入外层事务时，最终提交发生在服务方法返回之后，服务无法覆盖外层提交异常。
     * 因此命令入口显式拒绝已有事务；控制器调用均无外层事务。</p>
     */
    private void assertOutsideCommandTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "WMS公司命令禁止加入已有事务，必须在事务边界之外调用"
            );
        }
    }

    /**
     * 处理命令失败；返回 true 表示提交阶段异常经安全复核确认命令已生效。
     *
     * <p>确定回滚证据只有两类：方法体异常经事务模板真实回滚，或 UnexpectedRollbackException。
     * 提交阶段异常本身不证明回滚；查不到 attempt 也只能判定结果未知，不能记确定失败。
     * 只有 attempt 的命令键、动作和输入摘要全部匹配且为 APPLIED 时才认定成功。</p>
     */
    private boolean handleCommandFailure(String action, Long objectId, String commandKey, Object input,
                                         String inputDigest, RuntimeException error,
                                         LocalDateTime occurredAt, boolean bodyCompleted) {
        if (!bodyCompleted || error instanceof UnexpectedRollbackException) {
            auditService.recordFailure(action, objectId, commandKey, input, error, occurredAt);
            return false;
        }
        WmsCommandAttempt attempt;
        try {
            attempt = auditService.findAttempt(commandKey);
        } catch (RuntimeException lookupError) {
            log.error("公司命令提交结果无法复核: commandKey={}", commandKey, lookupError);
            throw new ServiceException(
                "COMMIT_OUTCOME_UNKNOWN: 提交结果未知，请使用相同命令键重试",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (attempt == null) {
            log.error("公司命令提交结果未知，复核未找到对应 attempt: commandKey={}", commandKey, error);
            throw new ServiceException(
                "COMMIT_OUTCOME_UNKNOWN: 提交结果未知，请使用相同命令键重试",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (!StrUtil.equals(attempt.getAction(), auditService.attemptAction(action))
            || !StrUtil.equals(attempt.getInputDigest(), inputDigest)) {
            throw new ServiceException(
                "COMMIT_KEY_CONFLICT: 命令键已被不同命令占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (WmsCompanyContract.ATTEMPT_APPLIED.equals(attempt.getStatus())) {
            log.warn("公司命令提交响应异常但已确认生效: commandKey={}", commandKey, error);
            return true;
        }
        log.warn("公司命令提交响应异常且复核为已记录失败: commandKey={}", commandKey, error);
        return false;
    }

    private WmsCompanyManagementScopeVo committedManagementScope(Long companyId, RuntimeException error) {
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setIsolationLevel(Isolation.REPEATABLE_READ.value());
        WmsCompanyManagementScopeVo result = read.execute(status -> {
            WmsCompanyManagementScope scope = accessPolicy.checkScopeManage(companyId);
            return buildManagementScope(companyId, scope, false);
        });
        if (result == null) {
            throw error;
        }
        return result;
    }
}
