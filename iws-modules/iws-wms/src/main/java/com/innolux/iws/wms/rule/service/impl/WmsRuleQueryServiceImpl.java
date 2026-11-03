/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 属性目录与生效配置读取实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.WmsLogicalScope;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeStorage;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeVersion;
import com.innolux.iws.wms.rule.domain.WmsRuleAttributeRow;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleEffectivePointer;
import com.innolux.iws.wms.rule.domain.WmsRulePackageMember;
import com.innolux.iws.wms.rule.domain.WmsRulePackageVersion;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.vo.WmsLogicalScopeVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageMemberVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeStorageMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleAttributeMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleDefinitionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleEffectivePointerMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageMemberMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleVersionMapper;
import com.innolux.iws.wms.rule.service.WmsRuleQueryService;
import com.innolux.iws.wms.rule.support.WmsRuleValidation;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.rule.support.WmsRuleViewSupport;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.innolux.iws.wms.rule.WmsRuleContract.PERMISSION_PUBLISH;
import static com.innolux.iws.wms.rule.WmsRuleContract.PERMISSION_QUERY;

/**
 * 规则只读查询实现。
 *
 * <p>生效读取不使用只读事务：它需要在指针行上取共享锁，与发布事务的排他锁形成线性化点。
 * 只读快照会读到发布提交前的旧指针，但无法阻止并发发布；共享锁能保证已开始的读取完成绑定后
 * 发布才切换。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsRuleQueryServiceImpl implements WmsRuleQueryService {

    private final WmsRuleAttributeMapper attributeMapper;

    private final WmsRuleBindingService bindingService;

    private final WmsRuleEffectivePointerMapper pointerMapper;

    private final WmsRulePackageVersionMapper packageVersionMapper;

    private final WmsRulePackageMemberMapper packageMemberMapper;

    private final WmsRuleVersionMapper ruleVersionMapper;

    private final WmsRuleDefinitionMapper definitionMapper;

    private final WmsLogicalScopeMapper logicalScopeMapper;

    private final WmsLogicalScopeVersionMapper logicalScopeVersionMapper;

    private final WmsLogicalScopeStorageMapper logicalScopeStorageMapper;

    private final WmsRuleScopeGuard scopeGuard;

    private final WmsCompanyAccessPolicy accessPolicy;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<WmsRuleAttributeVo> queryAttributes(PageQuery pageQuery) {
        accessPolicy.checkPermissionForShare(WmsRuleContract.PERMISSION_QUERY);
        PageQuery query = pageQuery == null ? new PageQuery() : pageQuery;
        IPage<WmsRuleAttributeRow> page = attributeMapper.selectActivePage(query.build());
        List<WmsRuleAttributeVo> rows = page.getRecords().stream()
            .map(WmsRuleViewSupport::toAttributeVo)
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WmsRuleEffectiveVo getEffective(String scopeType, Long scopeId) {
        accessPolicy.checkPermissionForShare(PERMISSION_QUERY);
        return readEffective(scopeType, scopeId, null);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WmsRuleEffectiveVo getEffectiveForPublish(String scopeType, Long scopeId) {
        return getEffectiveForPublish(scopeType, scopeId, null);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WmsRuleEffectiveVo getEffectiveForPublish(
        String scopeType,
        Long scopeId,
        Long actorId
    ) {
        if (actorId == null) {
            accessPolicy.checkPermissionForShare(PERMISSION_PUBLISH);
        } else {
            accessPolicy.checkPermissionForShare(PERMISSION_PUBLISH, actorId);
        }
        return readEffective(scopeType, scopeId, actorId);
    }

    private WmsRuleEffectiveVo readEffective(
        String scopeType,
        Long scopeId,
        Long actorId
    ) {
        String type = scopeGuard.requireScopeType(WmsRuleValidation.requireScopeType(scopeType));
        Long id = WmsRuleValidation.requireId(scopeId, "scopeId");
        WmsRuleEffectivePointer pointer = pointerMapper.selectByScopeForShare(type, id);
        if (pointer == null) {
            throw new ServiceException(
                WmsRuleContract.ERROR_EFFECTIVE_NOT_FOUND + ": 该作用域尚未发布规则包",
                HttpStatus.HTTP_NOT_FOUND
            );
        }
        return assemble(type, id, pointer, actorId);
    }

    /**
     * 按已读取的指针组装生效视图；发布事务在同一事务内复用本方法读取切换后的结果。
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @param pointer   生效指针
     * @return 生效配置
     */
    private WmsRuleEffectiveVo assemble(
        String scopeType,
        Long scopeId,
        WmsRuleEffectivePointer pointer,
        Long actorId
    ) {
        WmsRulePackageVersion packageVersion = packageVersionMapper.selectById(pointer.getPackageVersionId());
        if (packageVersion == null) {
            throw effectiveInconsistent("生效指针引用的规则包版本不存在");
        }
        List<WmsRulePackageMember> members = packageMemberMapper.selectList(
            new QueryWrapper<WmsRulePackageMember>()
                .eq("package_version_id", packageVersion.getPackageVersionId())
                .orderByAsc("purpose")
        );
        List<WmsRuleVersion> versions = members.stream()
            .map(member -> {
                WmsRuleVersion version = ruleVersionMapper.selectById(member.getRuleVersionId());
                if (version == null) {
                    throw effectiveInconsistent("生效成员引用的规则版本不存在");
                }
                return version;
            })
            .toList();
        lockDefinitions(scopeType, versions);
        List<WmsRulePackageMemberVo> memberVos = java.util.stream.IntStream.range(0, members.size())
            .mapToObj(index -> {
                WmsRulePackageMember member = members.get(index);
                return new WmsRulePackageMemberVo(
                    member.getPurpose(),
                    member.getRuleVersionId(),
                    member.getRevision(),
                    scopeType,
                    bindingService.toVersionVo(versions.get(index))
                );
            })
            .toList();
        WmsRulePackageVersionVo packageVo = WmsRuleViewSupport.toPackageVo(packageVersion, memberVos);
        WmsLogicalScopeVersionVo scopeVersion = currentScopeVersion(scopeType, scopeId);
        if (actorId == null) {
            scopeGuard.lockForRead(scopeType, scopeId, scopeVersion.storageIds());
        } else {
            scopeGuard.lockForRead(scopeType, scopeId, scopeVersion.storageIds(), actorId);
        }
        return new WmsRuleEffectiveVo(
            scopeType,
            scopeId,
            WmsRuleViewSupport.toPointerVo(pointer),
            packageVo,
            scopeVersion
        );
    }

    private WmsLogicalScopeVersionVo currentScopeVersion(String scopeType, Long scopeId) {
        String scopeCode = WmsRuleContract.scopeCode(scopeType, scopeId);
        WmsLogicalScope logicalScope = logicalScopeMapper.selectByCode(scopeCode);
        if (logicalScope == null || logicalScope.getCurrentVersionId() == null) {
            throw effectiveInconsistent("逻辑归属或当前版本缺失");
        }
        WmsLogicalScopeVersion scopeVersion =
            logicalScopeVersionMapper.selectById(logicalScope.getCurrentVersionId());
        if (scopeVersion == null) {
            throw effectiveInconsistent("逻辑归属当前版本不存在");
        }
        List<Long> storageIds = logicalScopeStorageMapper.selectList(
            new QueryWrapper<WmsLogicalScopeStorage>()
                .eq("scope_version_id", scopeVersion.getScopeVersionId())
                .orderByAsc("storage_id")
        ).stream().map(WmsLogicalScopeStorage::getStorageId).toList();
        return WmsRuleViewSupport.toScopeVersionVo(scopeVersion, scopeCode, storageIds);
    }

    private void lockDefinitions(String scopeType, List<WmsRuleVersion> versions) {
        List<Long> definitionIds = versions.stream()
            .map(WmsRuleVersion::getRuleDefinitionId)
            .distinct()
            .sorted()
            .toList();
        for (Long definitionId : definitionIds) {
            WmsRuleDefinition definition = definitionMapper.selectByIdForShare(definitionId);
            if (definition == null) {
                throw effectiveInconsistent("生效成员引用的规则定义不存在");
            }
            if (!scopeType.equals(definition.getScopeType())) {
                throw effectiveInconsistent("生效成员的规则定义作用域不一致");
            }
        }
    }

    private ServiceException effectiveInconsistent(String message) {
        return new ServiceException(
            WmsRuleContract.ERROR_EFFECTIVE_INCONSISTENT + ": " + message,
            HttpStatus.HTTP_CONFLICT
        );
    }
}
