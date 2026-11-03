/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : WMS 公司页面展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import com.innolux.iws.wms.domain.WmsCompany;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * WMS 公司页面展示对象。
 *
 * @author YONGJIE.PENG
 */
@Data
@AutoMapper(target = WmsCompany.class)
public class WmsCompanyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long companyId;

    private String companyCode;

    private String companyName;

    private String remark;

    private Boolean enabled;

    private String lifecycleStatus;

    private Long revision;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
