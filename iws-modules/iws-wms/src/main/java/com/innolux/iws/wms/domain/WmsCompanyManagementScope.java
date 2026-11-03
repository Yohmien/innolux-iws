/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : WMS 公司管理角色范围头实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WMS 公司管理角色范围头实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@TableName("wms_company_management_scope")
public class WmsCompanyManagementScope {

    @TableId(value = "company_id", type = IdType.INPUT)
    private Long companyId;

    private Long scopeRevision;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
