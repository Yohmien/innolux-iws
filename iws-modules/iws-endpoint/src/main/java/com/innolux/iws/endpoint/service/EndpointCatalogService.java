/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 独立系统端点目录读写服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.service;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.domain.bo.EndpointAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointAddressPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointAuditPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentBo;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemAddressBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemPageBo;
import com.innolux.iws.endpoint.domain.vo.EndpointAddressVo;
import com.innolux.iws.endpoint.domain.vo.EndpointAssociatedAddressVo;
import com.innolux.iws.endpoint.domain.vo.EndpointAuditVo;
import com.innolux.iws.endpoint.domain.vo.EndpointChangeResultVo;
import com.innolux.iws.endpoint.domain.vo.EndpointEnvironmentVo;
import com.innolux.iws.endpoint.domain.vo.EndpointSystemVo;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.AddressRow;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.AuditEntry;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.AuditRow;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.EnvironmentRow;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.RelationRow;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository.SystemRow;
import com.innolux.iws.endpoint.util.EndpointValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 独立系统端点目录读写服务。
 *
 * <p>每次写命令先独占目录控制行，再校验命令幂等、对象 CAS、关联集合，
 * 最后在同一事务内追加审计并递增目录 revision。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointCatalogService {

    private static final String SYSTEM = "SYSTEM";
    private static final String ENVIRONMENT = "ENVIRONMENT";
    private static final String ADDRESS = "ADDRESS";
    private static final String SYSTEM_ADDRESS = "SYSTEM_ADDRESS";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final long INITIAL_REVISION = 1L;

    private final EndpointCatalogRepository repository;
    private final EndpointProperties properties;

    /**
     * 分页查询系统目录。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 系统分页
     */
    @Transactional(readOnly = true)
    public PageResult<EndpointSystemVo> pageSystems(EndpointSystemPageBo query, PageQuery pageQuery) {
        PageBounds bounds = bounds(pageQuery);
        List<EndpointSystemVo> rows = repository.pageSystems(query, bounds.offset(), bounds.limit()).stream()
            .map(row -> toSystemVo(row, false))
            .toList();
        return PageResult.build(rows, repository.countSystems(query));
    }

    /**
     * 查询系统目录详情及有效地址关联。
     *
     * @param systemCode 系统编码
     * @return 系统详情
     */
    @Transactional(readOnly = true)
    public EndpointSystemVo querySystem(String systemCode) {
        String normalized = EndpointValidator.code(systemCode, "系统编码");
        SystemRow row = repository.findSystem(normalized)
            .orElseThrow(() -> new ServiceException("系统目录不存在：" + normalized));
        return toSystemVo(row, true);
    }

    /**
     * 新增系统目录。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo createSystem(EndpointSystemBo bo) {
        String systemCode = EndpointValidator.code(bo.getSystemCode(), "系统编码");
        String displayName = EndpointValidator.displayName(bo.getDisplayName(), "系统名称");
        String description = EndpointValidator.optionalText(bo.getDescription(), "系统说明", 500);
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        EndpointValidator.requireAbsentVersion(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(SYSTEM, systemCode, displayName, description, enabled,
            reason, actor.id());

        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        if (repository.findSystem(systemCode).isPresent()) {
            throw new ServiceException("系统编码已存在：" + systemCode);
        }

        SystemRow row = new SystemRow(systemCode, displayName, description, enabled, INITIAL_REVISION, null);
        repository.insertSystem(row, actor.id());
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        EndpointChangeResultVo result = result(SYSTEM, systemCode, INITIAL_REVISION, catalogRevision, commandKey);
        audit(commandKey, fingerprint, SYSTEM, systemCode, null, CREATE, null, systemSummary(row), actor,
            reason, catalogRevision, result);
        return result;
    }

    /**
     * 修改系统目录。
     *
     * @param systemCode 稳定系统编码
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo updateSystem(String systemCode, EndpointSystemBo bo) {
        String stableCode = EndpointValidator.code(systemCode, "系统编码");
        if (EndpointValidator.optional(bo.getSystemCode()) != null) {
            String bodyCode = EndpointValidator.code(bo.getSystemCode(), "系统编码");
            if (!stableCode.equals(bodyCode)) {
                throw new ServiceException("系统编码为稳定键，不允许通过修改请求改码");
            }
        }
        String displayName = EndpointValidator.displayName(bo.getDisplayName(), "系统名称");
        String description = EndpointValidator.optionalText(bo.getDescription(), "系统说明", 500);
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        long expectedRevision = EndpointValidator.revision(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(SYSTEM, stableCode, displayName, description, enabled,
            expectedRevision, reason, actor.id());

        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        SystemRow current = repository.lockSystem(stableCode)
            .orElseThrow(() -> new ServiceException("系统目录不存在：" + stableCode));
        requireRevision(current.revision(), expectedRevision, "系统目录");
        String before = systemSummary(current);
        long nextRevision = current.revision() + 1;
        SystemRow changed = new SystemRow(stableCode, displayName, description, enabled, nextRevision, null);
        if (!repository.updateSystem(changed, expectedRevision, actor.id())) {
            throw new ServiceException("系统目录版本已变化，请重新读取后重试");
        }
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        EndpointChangeResultVo result = result(SYSTEM, stableCode, nextRevision, catalogRevision, commandKey);
        audit(commandKey, fingerprint, SYSTEM, stableCode, null, UPDATE, before, systemSummary(changed), actor,
            reason, catalogRevision, result);
        return result;
    }

    /**
     * 分页查询环境目录。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 环境分页
     */
    @Transactional(readOnly = true)
    public PageResult<EndpointEnvironmentVo> pageEnvironments(EndpointEnvironmentPageBo query, PageQuery pageQuery) {
        PageBounds bounds = bounds(pageQuery);
        List<EndpointEnvironmentVo> rows = repository.pageEnvironments(query, bounds.offset(), bounds.limit()).stream()
            .map(this::toEnvironmentVo)
            .toList();
        return PageResult.build(rows, repository.countEnvironments(query));
    }

    /**
     * 查询环境目录详情。
     *
     * @param environmentCode 环境编码
     * @return 环境详情
     */
    @Transactional(readOnly = true)
    public EndpointEnvironmentVo queryEnvironment(String environmentCode) {
        String normalized = EndpointValidator.code(environmentCode, "环境编码");
        EnvironmentRow row = repository.findEnvironment(normalized)
            .orElseThrow(() -> new ServiceException("环境目录不存在：" + normalized));
        return toEnvironmentVo(row);
    }

    /**
     * 新增环境目录。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo createEnvironment(EndpointEnvironmentBo bo) {
        String environmentCode = EndpointValidator.code(bo.getEnvironmentCode(), "环境编码");
        String displayName = EndpointValidator.displayName(bo.getDisplayName(), "环境名称");
        String description = EndpointValidator.optionalText(bo.getDescription(), "环境说明", 500);
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        EndpointValidator.requireAbsentVersion(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(ENVIRONMENT, environmentCode, displayName,
            description, enabled, reason, actor.id());

        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        if (repository.findEnvironment(environmentCode).isPresent()) {
            throw new ServiceException("环境编码已存在：" + environmentCode);
        }

        EnvironmentRow row = new EnvironmentRow(environmentCode, displayName, description, enabled,
            INITIAL_REVISION, null);
        repository.insertEnvironment(row, actor.id());
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        EndpointChangeResultVo result = result(ENVIRONMENT, environmentCode, INITIAL_REVISION, catalogRevision, commandKey);
        audit(commandKey, fingerprint, ENVIRONMENT, environmentCode, environmentCode, CREATE, null,
            environmentSummary(row), actor, reason, catalogRevision, result);
        return result;
    }

    /**
     * 修改环境目录。
     *
     * @param environmentCode 稳定环境编码
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo updateEnvironment(String environmentCode, EndpointEnvironmentBo bo) {
        String stableCode = EndpointValidator.code(environmentCode, "环境编码");
        if (EndpointValidator.optional(bo.getEnvironmentCode()) != null) {
            String bodyCode = EndpointValidator.code(bo.getEnvironmentCode(), "环境编码");
            if (!stableCode.equals(bodyCode)) {
                throw new ServiceException("环境编码为稳定键，不允许通过修改请求改码");
            }
        }
        String displayName = EndpointValidator.displayName(bo.getDisplayName(), "环境名称");
        String description = EndpointValidator.optionalText(bo.getDescription(), "环境说明", 500);
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        long expectedRevision = EndpointValidator.revision(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(ENVIRONMENT, stableCode, displayName, description,
            enabled, expectedRevision, reason, actor.id());

        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        EnvironmentRow current = repository.lockEnvironment(stableCode)
            .orElseThrow(() -> new ServiceException("环境目录不存在：" + stableCode));
        requireRevision(current.revision(), expectedRevision, "环境目录");
        String before = environmentSummary(current);
        long nextRevision = current.revision() + 1;
        EnvironmentRow changed = new EnvironmentRow(stableCode, displayName, description, enabled, nextRevision, null);
        if (!repository.updateEnvironment(changed, expectedRevision, actor.id())) {
            throw new ServiceException("环境目录版本已变化，请重新读取后重试");
        }
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        EndpointChangeResultVo result = result(ENVIRONMENT, stableCode, nextRevision, catalogRevision, commandKey);
        audit(commandKey, fingerprint, ENVIRONMENT, stableCode, stableCode, UPDATE, before,
            environmentSummary(changed), actor, reason, catalogRevision, result);
        return result;
    }

    /**
     * 分页查询共享地址。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 地址分页
     */
    @Transactional(readOnly = true)
    public PageResult<EndpointAddressVo> pageAddresses(EndpointAddressPageBo query, PageQuery pageQuery) {
        PageBounds bounds = bounds(pageQuery);
        List<EndpointAddressVo> rows = repository.pageAddresses(query, bounds.offset(), bounds.limit()).stream()
            .map(this::toAddressVo)
            .toList();
        return PageResult.build(rows, repository.countAddresses(query));
    }

    /**
     * 查询共享地址详情。
     *
     * @param addressId 字符串地址主键
     * @return 地址详情
     */
    @Transactional(readOnly = true)
    public EndpointAddressVo queryAddress(String addressId) {
        long id = EndpointValidator.id(addressId, "addressId");
        AddressRow row = repository.findAddress(id)
            .orElseThrow(() -> new ServiceException("端点地址不存在：" + id));
        return toAddressVo(row);
    }

    /**
     * 新增共享地址，可同事务关联已登记系统。
     *
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo createAddress(EndpointAddressBo bo) {
        String environmentCode = EndpointValidator.code(bo.getEnvironmentCode(), "环境编码");
        String endpointRef = EndpointValidator.endpointReference(bo.getEndpointRef());
        String protocol = EndpointValidator.protocol(bo.getProtocol());
        String host = EndpointValidator.host(bo.getHost());
        String basePath = EndpointValidator.basePath(bo.getBasePath());
        String credentialRef = EndpointValidator.credentialReference(bo.getCredentialRef());
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        EndpointValidator.requireAbsentVersion(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Integer port = EndpointValidator.port(bo.getPort());
        List<String> systemCodes = normalizeSystemCodes(bo.getSystemCodes());
        if (!systemCodes.isEmpty()) {
            StpUtil.checkPermission("endpoint:system:edit");
        }
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(ADDRESS, environmentCode, endpointRef, protocol, host,
            port, basePath, credentialRef, enabled, systemCodes, reason, actor.id());

        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        if (repository.findEnvironment(environmentCode).isEmpty()) {
            throw new ServiceException("环境目录不存在：" + environmentCode);
        }
        if (repository.findAddress(environmentCode, endpointRef).isPresent()) {
            throw new ServiceException("同一环境的地址引用已存在：" + endpointRef);
        }
        validateSystemsExist(systemCodes);
        for (String systemCode : systemCodes) {
            SystemRow system = repository.lockSystem(systemCode)
                .orElseThrow(() -> new ServiceException("系统目录不存在：" + systemCode));
            SystemRow changedSystem = new SystemRow(system.systemCode(), system.displayName(),
                system.description(), system.enabled(), system.revision() + 1, null);
            if (!repository.updateSystem(changedSystem, system.revision(), actor.id())) {
                throw new ServiceException("系统目录版本已变化，请重新读取后重试");
            }
        }

        AddressRow row = new AddressRow(0L, environmentCode, endpointRef, protocol, host, port, basePath,
            credentialRef, enabled, INITIAL_REVISION, null);
        long addressId = repository.insertAddress(row, actor.id());
        for (String systemCode : systemCodes) {
            repository.insertRelation(systemCode, addressId, actor.id());
        }
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        String objectKey = Long.toString(addressId);
        EndpointChangeResultVo result = result(ADDRESS, objectKey, INITIAL_REVISION, catalogRevision, commandKey);
        AddressRow stored = new AddressRow(addressId, environmentCode, endpointRef, protocol, host, port, basePath,
            credentialRef, enabled, INITIAL_REVISION, null);
        audit(commandKey, fingerprint, ADDRESS, objectKey, environmentCode, CREATE, null,
            addressSummary(stored, systemCodes), actor, reason, catalogRevision, result);
        return result;
    }

    /**
     * 修改共享地址；环境编码和地址引用保持稳定。
     *
     * @param addressId 字符串地址主键
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo updateAddress(String addressId, EndpointAddressBo bo) {
        long id = EndpointValidator.id(addressId, "addressId");
        String protocol = EndpointValidator.protocol(bo.getProtocol());
        String host = EndpointValidator.host(bo.getHost());
        String basePath = EndpointValidator.basePath(bo.getBasePath());
        String credentialRef = EndpointValidator.credentialReference(bo.getCredentialRef());
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        long expectedRevision = EndpointValidator.revision(bo.getExpectedVersion());
        boolean enabled = EndpointValidator.requiredFlag(bo.getEnabled());
        Integer port = EndpointValidator.port(bo.getPort());
        if (bo.getSystemCodes() != null && !bo.getSystemCodes().isEmpty()) {
            throw new ServiceException("系统地址关联请使用系统关联接口保存，地址修改不会隐式增删关联");
        }
        Actor actor = currentActor();

        AddressRow requested = new AddressRow(id, EndpointValidator.optional(bo.getEnvironmentCode()),
            EndpointValidator.optional(bo.getEndpointRef()), protocol, host, port, basePath, credentialRef,
            enabled, 0L, null);
        String fingerprint = EndpointValidator.fingerprint(ADDRESS, id, requested.environmentCode(),
            requested.endpointRef(), protocol, host, port, basePath, credentialRef, enabled, expectedRevision,
            reason, actor.id());
        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }
        AddressRow current = repository.lockAddress(id)
            .orElseThrow(() -> new ServiceException("端点地址不存在：" + id));
        if (requested.environmentCode() != null && !requested.environmentCode().equals(current.environmentCode())) {
            throw new ServiceException("环境编码为稳定键，不允许通过修改请求改码");
        }
        if (requested.endpointRef() != null && !requested.endpointRef().equals(current.endpointRef())) {
            throw new ServiceException("地址引用为稳定键，不允许通过修改请求改码");
        }
        requireRevision(current.revision(), expectedRevision, "端点地址");
        List<String> systemCodes = repository.activeSystemCodesForAddress(id);
        String before = addressSummary(current, systemCodes);
        long nextRevision = current.revision() + 1;
        AddressRow changed = new AddressRow(id, current.environmentCode(), current.endpointRef(), protocol, host,
            port, basePath, credentialRef, enabled, nextRevision, null);
        if (!repository.updateAddress(changed, expectedRevision, actor.id())) {
            throw new ServiceException("端点地址版本已变化，请重新读取后重试");
        }
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        EndpointChangeResultVo result = result(ADDRESS, Long.toString(id), nextRevision, catalogRevision, commandKey);
        audit(commandKey, fingerprint, ADDRESS, Long.toString(id), current.environmentCode(), UPDATE, before,
            addressSummary(changed, systemCodes), actor, reason, catalogRevision, result);
        return result;
    }

    /**
     * 以完整集合保存系统地址关联；未出现的旧关联只停用。
     *
     * @param systemCode 系统编码
     * @param bo 写请求
     * @return 写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EndpointChangeResultVo replaceSystemAddresses(String systemCode, EndpointSystemAddressBo bo) {
        String stableCode = EndpointValidator.code(systemCode, "系统编码");
        String commandKey = EndpointValidator.commandKey(bo.getCommandKey());
        String reason = EndpointValidator.reason(bo.getReason());
        long expectedRevision = EndpointValidator.revision(bo.getExpectedVersion());
        Set<Long> requestedIds = normalizeAddressIds(bo.getAddressIds());
        Actor actor = currentActor();
        String fingerprint = EndpointValidator.fingerprint(SYSTEM_ADDRESS, stableCode, requestedIds,
            expectedRevision, reason, actor.id());
        CommandState command = lockCommand(commandKey, fingerprint);
        if (command.replay() != null) {
            return command.replay();
        }

        SystemRow current = repository.lockSystem(stableCode)
            .orElseThrow(() -> new ServiceException("系统目录不存在：" + stableCode));
        requireRevision(current.revision(), expectedRevision, "系统目录");
        for (Long addressId : requestedIds) {
            if (repository.findAddress(addressId).isEmpty()) {
                throw new ServiceException("端点地址不存在：" + addressId);
            }
        }

        List<RelationRow> existing = repository.relationsForSystem(stableCode);
        Map<Long, RelationRow> existingByAddress = new LinkedHashMap<>();
        existing.forEach(relation -> existingByAddress.put(relation.addressId(), relation));
        List<String> before = existing.stream().filter(RelationRow::enabled).map(RelationRow::addressId)
            .map(String::valueOf).sorted().toList();

        for (Long addressId : requestedIds) {
            RelationRow relation = existingByAddress.get(addressId);
            if (relation == null) {
                repository.insertRelation(stableCode, addressId, actor.id());
            } else if (!relation.enabled()) {
                if (!repository.updateRelation(relation.relationId(), true, relation.revision() + 1,
                    relation.revision(), actor.id())) {
                    throw new ServiceException("系统地址关联版本已变化，请重试");
                }
            }
        }
        for (RelationRow relation : existing) {
            if (relation.enabled() && !requestedIds.contains(relation.addressId())) {
                if (!repository.updateRelation(relation.relationId(), false, relation.revision() + 1,
                    relation.revision(), actor.id())) {
                    throw new ServiceException("系统地址关联版本已变化，请重试");
                }
            }
        }

        long systemRevision = current.revision() + 1;
        SystemRow changedSystem = new SystemRow(current.systemCode(), current.displayName(), current.description(),
            current.enabled(), systemRevision, null);
        if (!repository.updateSystem(changedSystem, expectedRevision, actor.id())) {
            throw new ServiceException("系统目录版本已变化，请重新读取后重试");
        }
        long catalogRevision = repository.advanceControl(command.catalogRevision());
        List<String> after = requestedIds.stream().map(String::valueOf).sorted().toList();
        EndpointChangeResultVo result = result(SYSTEM_ADDRESS, stableCode, systemRevision, catalogRevision, commandKey);
        audit(commandKey, fingerprint, SYSTEM_ADDRESS, stableCode, null, UPDATE,
            relationSummary(before), relationSummary(after), actor, reason, catalogRevision, result);
        return result;
    }

    /**
     * 分页查询端点配置审计。
     *
     * @param query 筛选条件
     * @param pageQuery 分页参数
     * @return 审计分页
     */
    @Transactional(readOnly = true)
    public PageResult<EndpointAuditVo> pageAudits(EndpointAuditPageBo query, PageQuery pageQuery) {
        PageBounds bounds = bounds(pageQuery);
        List<EndpointAuditVo> rows = repository.pageAudits(query, bounds.offset(), bounds.limit()).stream()
            .map(this::toAuditVo)
            .toList();
        return PageResult.build(rows, repository.countAudits(query));
    }

    private CommandState lockCommand(String commandKey, String fingerprint) {
        long catalogRevision = repository.lockControl();
        Optional<AuditRow> existing = repository.findCommand(commandKey);
        if (existing.isPresent()) {
            AuditRow audit = existing.get();
            if (!Objects.equals(audit.requestHash(), fingerprint)) {
                throw new ServiceException("命令键已被不同请求使用，请使用新的命令键重新提交");
            }
            return new CommandState(catalogRevision, new EndpointChangeResultVo(audit.objectType(),
                audit.resultObjectKey(), Long.toString(audit.resultRevision()),
                Long.toString(audit.catalogRevision()), audit.commandKey()));
        }
        return new CommandState(catalogRevision, null);
    }

    private void audit(String commandKey, String fingerprint, String objectType, String objectKey,
                       String environmentCode, String action, String beforeSummary, String afterSummary,
                       Actor actor, String reason, long catalogRevision, EndpointChangeResultVo result) {
        repository.insertAudit(new AuditEntry(objectType, objectKey, environmentCode, action, beforeSummary,
            afterSummary, actor.id(), actor.name(), commandKey, fingerprint, reason, catalogRevision,
            Long.parseLong(result.revision()), result.objectKey()));
    }

    private EndpointChangeResultVo result(String objectType, String objectKey, long revision,
                                          long catalogRevision, String commandKey) {
        return new EndpointChangeResultVo(objectType, objectKey, Long.toString(revision),
            Long.toString(catalogRevision), commandKey);
    }

    private void requireRevision(long current, long expected, String objectName) {
        if (current != expected) {
            throw new ServiceException(objectName + "版本冲突，expectedVersion=" + expected
                + "，currentVersion=" + current + "；请重新读取后重试");
        }
    }

    private List<String> normalizeSystemCodes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(EndpointValidator.code(value, "系统编码"));
        }
        return normalized.stream().sorted().toList();
    }

    private Set<Long> normalizeAddressIds(List<String> values) {
        if (values == null) {
            throw new ServiceException("地址集合不能为空；清空关联请传空数组");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : values) {
            ids.add(EndpointValidator.id(value, "addressId"));
        }
        return ids;
    }

    private void validateSystemsExist(List<String> systemCodes) {
        for (String systemCode : systemCodes) {
            if (repository.findSystem(systemCode).isEmpty()) {
                throw new ServiceException("系统目录不存在：" + systemCode);
            }
        }
    }

    private EndpointSystemVo toSystemVo(SystemRow row, boolean includeAddresses) {
        List<RelationRow> relations = repository.activeRelationsForSystem(row.systemCode());
        boolean addressQueryAllowed = hasAddressQueryPermission();
        List<EndpointAssociatedAddressVo> addresses = includeAddresses
            ? relations.stream().map(RelationRow::addressId).map(repository::findAddress)
                .flatMap(Optional::stream).map(address -> toAssociatedAddress(address, addressQueryAllowed)).toList()
            : List.of();
        EndpointSystemVo vo = new EndpointSystemVo();
        vo.setSystemCode(row.systemCode());
        vo.setDisplayName(row.displayName());
        vo.setDescription(row.description());
        vo.setEnabled(row.enabled());
        vo.setRevision(Long.toString(row.revision()));
        vo.setAddressCount(relations.size());
        vo.setAddresses(addresses);
        vo.setUpdateTime(row.updateTime());
        return vo;
    }

    private EndpointAssociatedAddressVo toAssociatedAddress(AddressRow row, boolean addressQueryAllowed) {
        EndpointAssociatedAddressVo vo = new EndpointAssociatedAddressVo();
        vo.setAddressId(Long.toString(row.addressId()));
        vo.setEnvironmentCode(row.environmentCode());
        vo.setEndpointRef(row.endpointRef());
        vo.setEnabled(row.enabled());
        vo.setRevision(Long.toString(row.revision()));
        vo.setHasCredential(row.credentialRef() != null && !row.credentialRef().isBlank());
        if (addressQueryAllowed) {
            vo.setProtocol(row.protocol());
            vo.setHost(row.host());
            vo.setPort(row.port());
            vo.setBasePath(row.basePath());
            vo.setBaseUri(baseUri(row));
        }
        return vo;
    }

    private EndpointEnvironmentVo toEnvironmentVo(EnvironmentRow row) {
        EndpointEnvironmentVo vo = new EndpointEnvironmentVo();
        vo.setEnvironmentCode(row.environmentCode());
        vo.setDisplayName(row.displayName());
        vo.setDescription(row.description());
        vo.setEnabled(row.enabled());
        vo.setRevision(Long.toString(row.revision()));
        vo.setCurrent(row.environmentCode().equals(properties.getCurrentEnvironment()));
        vo.setUpdateTime(row.updateTime());
        return vo;
    }

    private EndpointAddressVo toAddressVo(AddressRow row) {
        EndpointAddressVo vo = new EndpointAddressVo();
        vo.setAddressId(Long.toString(row.addressId()));
        vo.setEnvironmentCode(row.environmentCode());
        vo.setEndpointRef(row.endpointRef());
        vo.setProtocol(row.protocol());
        vo.setHost(row.host());
        vo.setPort(row.port());
        vo.setBasePath(row.basePath());
        vo.setBaseUri(baseUri(row));
        vo.setEnabled(row.enabled());
        vo.setRevision(Long.toString(row.revision()));
        vo.setCredentialRef(row.credentialRef());
        vo.setHasCredential(row.credentialRef() != null && !row.credentialRef().isBlank());
        vo.setSystemCodes(repository.activeSystemCodesForAddress(row.addressId()));
        vo.setUpdateTime(row.updateTime());
        return vo;
    }

    private EndpointAuditVo toAuditVo(AuditRow row) {
        EndpointAuditVo vo = new EndpointAuditVo();
        vo.setAuditId(Long.toString(row.auditId()));
        vo.setObjectType(row.objectType());
        vo.setObjectKey(row.objectKey());
        vo.setEnvironmentCode(row.environmentCode());
        vo.setAction(row.action());
        if (ADDRESS.equals(row.objectType()) && !hasAddressQueryPermission()) {
            vo.setBeforeSummary(row.beforeSummary() == null ? null : redactedAddressSummary());
            vo.setAfterSummary(row.afterSummary() == null ? null : redactedAddressSummary());
        } else {
            vo.setBeforeSummary(row.beforeSummary());
            vo.setAfterSummary(row.afterSummary());
        }
        vo.setOperatorId(row.operatorId() == null ? null : Long.toString(row.operatorId()));
        vo.setOperatorName(row.operatorName());
        vo.setCommandKey(row.commandKey());
        vo.setReason(row.reason());
        vo.setCatalogRevision(Long.toString(row.catalogRevision()));
        vo.setResultRevision(Long.toString(row.resultRevision()));
        vo.setCreateTime(row.createTime());
        return vo;
    }

    private Actor currentActor() {
        Long userId = LoginHelper.getUserId();
        String username = LoginHelper.getUsername();
        return new Actor(userId, username == null || username.isBlank() ? "system" : username);
    }

    private boolean hasAddressQueryPermission() {
        try {
            return LoginHelper.isLogin() && StpUtil.hasPermission("endpoint:address:query");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PageBounds bounds(PageQuery pageQuery) {
        int pageNum = pageQuery.getPageNum() == null || pageQuery.getPageNum() <= 0 ? 1 : pageQuery.getPageNum();
        int pageSize = pageQuery.getPageSize() == null || pageQuery.getPageSize() <= 0
            ? PageQuery.DEFAULT_PAGE_SIZE : pageQuery.getPageSize();
        return new PageBounds((long) (pageNum - 1) * pageSize, pageSize);
    }

    private String baseUri(AddressRow row) {
        String authority = row.host().contains(":") ? "[" + row.host() + "]" : row.host();
        String port = row.port() == null ? "" : ":" + row.port();
        return row.protocol() + "://" + authority + port + (row.basePath() == null ? "" : row.basePath());
    }

    private String systemSummary(SystemRow row) {
        return "{\"systemCode\":" + json(row.systemCode()) + ",\"displayName\":" + json(row.displayName())
            + ",\"description\":" + json(row.description()) + ",\"enabled\":" + row.enabled()
            + ",\"revision\":" + row.revision() + "}";
    }

    private String environmentSummary(EnvironmentRow row) {
        return "{\"environmentCode\":" + json(row.environmentCode()) + ",\"displayName\":" + json(row.displayName())
            + ",\"description\":" + json(row.description()) + ",\"enabled\":" + row.enabled()
            + ",\"revision\":" + row.revision() + "}";
    }

    private String addressSummary(AddressRow row, List<String> systemCodes) {
        return "{\"addressId\":" + json(Long.toString(row.addressId())) + ",\"environmentCode\":"
            + json(row.environmentCode()) + ",\"endpointRef\":" + json(row.endpointRef()) + ",\"protocol\":"
            + json(row.protocol()) + ",\"hasCredential\":"
            + (row.credentialRef() != null && !row.credentialRef().isBlank())
            + ",\"enabled\":" + row.enabled() + ",\"revision\":" + row.revision()
            + ",\"systemCodes\":" + jsonList(systemCodes) + "}";
    }

    private String redactedAddressSummary() {
        return "{\"redacted\":true,\"reason\":\"地址详情需要 endpoint:address:query 权限\"}";
    }

    private String relationSummary(List<String> addressIds) {
        return "{\"enabledAddressIds\":" + jsonList(addressIds) + "}";
    }

    private String jsonList(List<String> values) {
        return "[" + values.stream().map(this::json).reduce((left, right) -> left + "," + right).orElse("")
            + "]";
    }

    private String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }

    private record Actor(Long id, String name) {
    }

    private record CommandState(long catalogRevision, EndpointChangeResultVo replay) {
    }

    private record PageBounds(long offset, long limit) {
    }
}
