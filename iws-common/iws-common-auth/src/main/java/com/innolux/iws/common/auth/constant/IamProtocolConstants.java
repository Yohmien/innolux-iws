/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一身份认证协议常量；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.constant;

/**
 * 统一身份认证协议常量。
 */
public final class IamProtocolConstants {

    public static final String ISSUER = "iws-auth-center";
    public static final String APPLICATION_HEADER = "X-IAM-Application";
    public static final String INSTANCE_HEADER = "X-IAM-Instance";
    public static final String TIMESTAMP_HEADER = "X-IAM-Timestamp";
    public static final String NONCE_HEADER = "X-IAM-Nonce";
    public static final String SIGNATURE_HEADER = "X-IAM-Signature";
    public static final String ROOT_SID_SESSION_KEY = "iamRootSid";
    public static final String APPLICATION_ID_SESSION_KEY = "iamApplicationId";
    public static final String INSTANCE_ID_SESSION_KEY = "iamInstanceId";
    public static final String APPLICATION_TYPE_SESSION_KEY = "iamApplicationType";
    public static final String FRONTEND_ID_SESSION_KEY = "iamFrontendId";
    public static final String APPLICATION_SESSION_ID_KEY = "iamApplicationSessionId";
    public static final String APPLICATION_SESSION_GENERATION_KEY = "iamApplicationSessionGeneration";
    public static final String TERMINAL_SESSION_KEY = "iamTerminal";
    public static final String SUBJECT_USER_ID_CLAIM = "iamSubjectUserId";
    public static final String CREDENTIAL_FINGERPRINT_KEY = "iamCredentialFingerprint";
    public static final String TICKET_KEY_PREFIX = "iam:ticket:";
    public static final String ROOT_SESSION_KEY_PREFIX = "iam:root:";
    public static final String ROOT_CHILDREN_KEY_PREFIX = "iam:root:children:";
    public static final String APPLICATION_SESSION_KEY_PREFIX = "iam:application-session:";
    public static final String APPLICATION_GENERATION_KEY_PREFIX = "iam:application-generation:";
    public static final String ACTIVATION_TRANSACTION_KEY_PREFIX = "iam:activation-transaction:";
    public static final String APPLICATION_PREPARATION_KEY_PREFIX = "iam:application-preparation:";
    public static final String TICKET_COMMIT_KEY_PREFIX = "iam:ticket-commit:";
    public static final String APPLICATION_SESSION_LOCK_PREFIX = "iam:application-session-lock:";
    public static final String NONCE_KEY_PREFIX = "iam:nonce:";
    public static final String TERMINAL_WEB = "WEB";
    public static final String TERMINAL_PDA = "PDA";
    public static final String SESSION_REPLACED_PREFIX = "IAM_SESSION_REPLACED:";
    public static final String VIEW_MAPPING_CONFIG_KEY = "iam.view.mapping.config";
    public static final String LOGOUT_LOCAL_VALID_HEADER = "X-IAM-Local-Credential-Valid";
    public static final String LOGOUT_CENTER_REVOKED_HEADER = "X-IAM-Center-Revoked";

    private IamProtocolConstants() {
    }
}
