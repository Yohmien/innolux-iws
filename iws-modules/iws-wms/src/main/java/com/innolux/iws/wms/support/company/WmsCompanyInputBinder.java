/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司命令的严格字段白名单、类型转换和 Bean Validation 入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.company;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.ValidatorUtils;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.wms.domain.dto.WmsCompanyCreateDTO;
import com.innolux.iws.wms.domain.dto.WmsCompanyEnableCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyManagementScopeReplaceCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyUpdateDTO;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 公司命令严格输入绑定器。
 *
 * @author YONGJIE.PENG
 */
@Component
public class WmsCompanyInputBinder {

    private static final Set<String> CREATE_FIELDS =
        Set.of("companyCode", "companyName", "remark", "commandKey");

    private static final Set<String> UPDATE_FIELDS =
        Set.of("companyId", "companyName", "remark", "expectedRevision", "commandKey", "reason");

    private static final Set<String> ENABLE_FIELDS =
        Set.of("expectedRevision", "commandKey", "reason");

    private static final Set<String> SCOPE_FIELDS =
        Set.of("companyId", "roleIds", "expectedScopeRevision", "commandKey", "reason");

    private static final Set<String> COMPANY_CLOSED_FIELDS = Set.of(
        "companyCode", "enabled", "lifecycleStatus", "revision", "warehouseIds",
        "visibility", "retired", "deleted", "import"
    );

    /**
     * 绑定公司创建命令。
     *
     * @param input 原始 JSON 对象
     * @return 已校验命令
     */
    public WmsCompanyCreateDTO bindCreate(Map<String, Object> input) {
        Set<String> closed = new HashSet<>(COMPANY_CLOSED_FIELDS);
        closed.remove("companyCode");
        WmsCompanyCreateDTO dto = bind(input, CREATE_FIELDS, closed, WmsCompanyCreateDTO.class);
        dto.setCompanyCode(trim(dto.getCompanyCode()));
        dto.setCompanyName(trim(dto.getCompanyName()));
        dto.setRemark(trimToNull(dto.getRemark()));
        dto.setCommandKey(trim(dto.getCommandKey()));
        return dto;
    }

    /**
     * 绑定公司名称和备注修改命令。
     *
     * @param input 原始 JSON 对象
     * @return 已校验命令
     */
    public WmsCompanyUpdateDTO bindUpdate(Map<String, Object> input) {
        WmsCompanyUpdateDTO dto = bind(input, UPDATE_FIELDS, COMPANY_CLOSED_FIELDS, WmsCompanyUpdateDTO.class);
        dto.setCompanyName(trim(dto.getCompanyName()));
        dto.setRemark(trimToNull(dto.getRemark()));
        dto.setRemarkProvided(input.containsKey("remark"));
        dto.setCommandKey(trim(dto.getCommandKey()));
        dto.setReason(trim(dto.getReason()));
        return dto;
    }

    /**
     * 绑定公司启停命令。
     *
     * @param input 原始 JSON 对象
     * @return 已校验命令
     */
    public WmsCompanyEnableCommand bindEnable(Map<String, Object> input) {
        WmsCompanyEnableCommand dto =
            bind(input, ENABLE_FIELDS, COMPANY_CLOSED_FIELDS, WmsCompanyEnableCommand.class);
        dto.setCommandKey(trim(dto.getCommandKey()));
        dto.setReason(trim(dto.getReason()));
        return dto;
    }

    /**
     * 绑定公司管理范围全量替换命令。
     *
     * @param input 原始 JSON 对象
     * @return 已校验命令
     */
    public WmsCompanyManagementScopeReplaceCommand bindScopeReplace(Map<String, Object> input) {
        WmsCompanyManagementScopeReplaceCommand dto =
            bind(input, SCOPE_FIELDS, Set.of(), WmsCompanyManagementScopeReplaceCommand.class);
        dto.setCommandKey(trim(dto.getCommandKey()));
        dto.setReason(trim(dto.getReason()));
        return dto;
    }

    private <T> T bind(Map<String, Object> input, Set<String> allowed, Set<String> closed, Class<T> type) {
        if (input == null) {
            throw new ServiceException("请求体不能为空", HttpStatus.HTTP_BAD_REQUEST);
        }
        for (String field : input.keySet()) {
            if (closed.contains(field)) {
                throw new ServiceException(
                    "FEATURE_NOT_OPEN: 字段不允许通过公司基础命令修改: " + field,
                    HttpStatus.HTTP_FORBIDDEN
                );
            }
            if (!allowed.contains(field)) {
                throw new ServiceException(
                    "字段不在公司命令白名单中: " + field,
                    HttpStatus.HTTP_BAD_REQUEST
                );
            }
        }
        try {
            T value = JsonUtils.parseObject(JsonUtils.toJsonString(input), type);
            ValidatorUtils.validate(value);
            return value;
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServiceException("公司命令字段格式不正确", HttpStatus.HTTP_BAD_REQUEST);
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
