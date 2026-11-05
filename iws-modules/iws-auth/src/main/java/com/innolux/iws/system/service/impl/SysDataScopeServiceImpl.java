/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.utils.StreamUtils;
import com.innolux.iws.system.service.ISysDataScopeService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据权限 实现
 * <p>
 * 注意: 此Service内不允许调用标注`数据权限`注解的方法
 * 例如: deptMapper.selectList 此 selectList 方法标注了`数据权限`注解 会出现循环解析的问题
 * 当前实现仅负责返回角色自定义部门范围以及部门树展开后的 id 串，供数据权限插件拼装 SQL 时使用。
 * 这两项是授权事实，不使用按服务前缀隔离的 Redis 缓存，确保中心修改后其他应用重登立即读取当前值。
 *
 * @author YONGJIE.PENG
 */
@RequiredArgsConstructor
@Service("sdss")
public class SysDataScopeServiceImpl implements ISysDataScopeService {

    private final IamIdentityFacts identityFacts;

    /**
     * 获取角色自定义权限
     *
     * @param roleId 角色Id
     * @return 逗号分隔的部门 id 字符串，未配置时返回 `-1`
     */
    @Override
    public String getRoleCustom(Long roleId) {
        if (ObjectUtil.isNull(roleId)) {
            return "-1";
        }
        List<Long> deptIds = identityFacts.roleCustomDeptIdsForShare(roleId);
        if (CollUtil.isNotEmpty(deptIds)) {
            return StreamUtils.join(deptIds, Convert::toStr);
        }
        return "-1";
    }

    /**
     * 获取部门及以下权限
     *
     * @param deptId 部门Id
     * @return 当前部门及其子部门的逗号分隔 id 字符串，未查询到时返回 `-1`
     */
    @Override
    public String getDeptAndChild(Long deptId) {
        if (ObjectUtil.isNull(deptId)) {
            return "-1";
        }
        List<Long> deptIds = identityFacts.deptAndChildIdsForShare(deptId);
        return CollUtil.isNotEmpty(deptIds) ? StreamUtils.join(deptIds, Convert::toStr) : "-1";
    }

}
