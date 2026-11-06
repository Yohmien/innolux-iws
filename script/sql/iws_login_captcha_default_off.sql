-- 登录验证码统一由认证中心维护，默认关闭。
INSERT INTO `sys_config` (
    `config_id`,
    `config_name`,
    `config_key`,
    `config_value`,
    `config_type`,
    `create_dept`,
    `create_by`,
    `create_time`,
    `remark`
)
SELECT
    1761700000000000004,
    '登录安全-是否开启验证码',
    'sys.login.captchaEnabled',
    'false',
    'Y',
    1761000000000000103,
    1761100000000000001,
    NOW(),
    'true开启登录验证码，false关闭；默认关闭'
WHERE NOT EXISTS (
    SELECT 1
    FROM `sys_config`
    WHERE `config_key` = 'sys.login.captchaEnabled'
);

UPDATE `sys_config`
SET `config_value` = 'false',
    `update_time` = NOW(),
    `remark` = 'true开启登录验证码，false关闭；默认关闭'
WHERE `config_key` = 'sys.login.captchaEnabled';

SELECT `config_key`, `config_value`
FROM `sys_config`
WHERE `config_key` = 'sys.login.captchaEnabled';
