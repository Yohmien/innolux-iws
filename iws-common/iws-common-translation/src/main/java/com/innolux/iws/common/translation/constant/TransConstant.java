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

package com.innolux.iws.common.translation.constant;

/**
 * 翻译常量
 *
 * @author YONGJIE.PENG
 */
public interface TransConstant {

    /**
     * 用户id转账号
     */
    String USER_ID_TO_NAME = "user_id_to_name";

    /**
     * 用户id转用户昵称
     */
    String USER_ID_TO_NICKNAME = "user_id_to_nickname";

    /**
     * 部门id转名称
     */
    String DEPT_ID_TO_NAME = "dept_id_to_name";

    /**
     * 字典type转label
     */
    String DICT_TYPE_TO_LABEL = "dict_type_to_label";

    /**
     * ossId转url
     */
    String OSS_ID_TO_URL = "oss_id_to_url";

}
