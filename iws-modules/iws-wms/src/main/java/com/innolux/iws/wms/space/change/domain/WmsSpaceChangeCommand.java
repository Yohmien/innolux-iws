/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 冻结仓库、库区和储位创建、配置及退役请求；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.domain;

/**
 * 空间变更命令。
 *
 * <p>更新命令提交完整目标配置；未提供字段不从前端猜测。创建和退役使用同一持久身份，
 * 实际对象 ID 由 PLAN 校验动作生成或读取。</p>
 */
public class WmsSpaceChangeCommand {

    private String commandKey;

    private String changeType;

    private String objectType;

    private Long objectId;

    private Integer expectedRevision;

    private Long companyId;

    private Long warehouseId;

    private Long zoneId;

    private Long parentZoneId;

    private String code;

    private String name;

    private String warehouseTypeCode;

    private Long typeConfigVersionId;

    private String zoneKind;

    private Integer zoneType;

    private Boolean allowInbound;

    private Boolean allowOutbound;

    private Boolean enabled;

    private Integer status;

    private Integer disableStatus;

    private Integer storageType;

    private Integer sortNo;

    private String aisleText;

    private String rowText;

    private String levelText;

    private String columnText;

    private String depthText;

    private String portCode;

    private String wcsPointCode;

    private String remark;

    private String reason;

    public String getCommandKey() {
        return commandKey;
    }

    public void setCommandKey(String commandKey) {
        this.commandKey = commandKey;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public void setObjectId(Long objectId) {
        this.objectId = objectId;
    }

    public Integer getExpectedRevision() {
        return expectedRevision;
    }

    public void setExpectedRevision(Integer expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(Long zoneId) {
        this.zoneId = zoneId;
    }

    public Long getParentZoneId() {
        return parentZoneId;
    }

    public void setParentZoneId(Long parentZoneId) {
        this.parentZoneId = parentZoneId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWarehouseTypeCode() {
        return warehouseTypeCode;
    }

    public void setWarehouseTypeCode(String warehouseTypeCode) {
        this.warehouseTypeCode = warehouseTypeCode;
    }

    public Long getTypeConfigVersionId() {
        return typeConfigVersionId;
    }

    public void setTypeConfigVersionId(Long typeConfigVersionId) {
        this.typeConfigVersionId = typeConfigVersionId;
    }

    public String getZoneKind() {
        return zoneKind;
    }

    public void setZoneKind(String zoneKind) {
        this.zoneKind = zoneKind;
    }

    public Integer getZoneType() {
        return zoneType;
    }

    public void setZoneType(Integer zoneType) {
        this.zoneType = zoneType;
    }

    public Boolean getAllowInbound() {
        return allowInbound;
    }

    public void setAllowInbound(Boolean allowInbound) {
        this.allowInbound = allowInbound;
    }

    public Boolean getAllowOutbound() {
        return allowOutbound;
    }

    public void setAllowOutbound(Boolean allowOutbound) {
        this.allowOutbound = allowOutbound;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDisableStatus() {
        return disableStatus;
    }

    public void setDisableStatus(Integer disableStatus) {
        this.disableStatus = disableStatus;
    }

    public Integer getStorageType() {
        return storageType;
    }

    public void setStorageType(Integer storageType) {
        this.storageType = storageType;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getAisleText() {
        return aisleText;
    }

    public void setAisleText(String aisleText) {
        this.aisleText = aisleText;
    }

    public String getRowText() {
        return rowText;
    }

    public void setRowText(String rowText) {
        this.rowText = rowText;
    }

    public String getLevelText() {
        return levelText;
    }

    public void setLevelText(String levelText) {
        this.levelText = levelText;
    }

    public String getColumnText() {
        return columnText;
    }

    public void setColumnText(String columnText) {
        this.columnText = columnText;
    }

    public String getDepthText() {
        return depthText;
    }

    public void setDepthText(String depthText) {
        this.depthText = depthText;
    }

    public String getPortCode() {
        return portCode;
    }

    public void setPortCode(String portCode) {
        this.portCode = portCode;
    }

    public String getWcsPointCode() {
        return wcsPointCode;
    }

    public void setWcsPointCode(String wcsPointCode) {
        this.wcsPointCode = wcsPointCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
