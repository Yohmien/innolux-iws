/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 直接写入结构门禁
 * @Version      : V1.0
 * @Description  : 冻结公司和供应商直接写入所使用的登记键与目标结构指纹；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.schema;

/**
 * WMS 直接写入结构门禁合同。
 *
 * @author YONGJIE.PENG
 */
public final class WmsDirectWriteSchemaContract {

    public static final String COMPONENT_CODE = "WMS_DIRECT_WRITE";

    public static final String SCHEMA_VERSION = "1";

    public static final String RELEASE_ID = "INV-WMS-DIRECT-WRITE-1";

    public static final String TARGET_FINGERPRINT =
        "1b236346b990fbf9410aeea49bb323f174112e1a44c133998baccb2ddac06909";

    public static final String STATUS_VERIFIED = "VERIFIED";

    public static final String ERROR_SCHEMA_STATE_UNSUPPORTED = "SCHEMA_STATE_UNSUPPORTED";

    public static final String ERROR_DIRECT_WRITE_FROZEN = "DIRECT_WRITE_FROZEN";

    public static final String ERROR_DIRECT_WRITE_FENCE_CONFLICT = "DIRECT_WRITE_FENCE_CONFLICT";

    private WmsDirectWriteSchemaContract() {
    }
}
