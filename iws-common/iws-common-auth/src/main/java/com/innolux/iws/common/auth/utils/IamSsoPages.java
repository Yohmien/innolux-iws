/*
 * @Date         : 2026-11-05
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一原生控制台认证等待和错误页面样式；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.common.auth.utils;

/** 原生控制台认证状态页面的共用样式，不改变票据和回调处理。 */
public final class IamSsoPages {
    private IamSsoPages() {
    }

    /**
     * 在已转义内容的 HTML 中加入响应式认证状态样式。
     * @param html 原页面
     * @return 保留原内容和脚本的页面
     */
    public static String decorate(String html) {
        return html.replace("</head>", """
            <style>
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;
                font-family:system-ui,"Microsoft YaHei",sans-serif;color:#263746;
                background:radial-gradient(ellipse at 50% 20%,#e4f1f7,transparent 65%),#f6f8fb}
              main{width:min(100%,480px);background:#fff;border:1px solid #e2eaf0;
                border-radius:20px;box-shadow:0 16px 56px #173e5312;text-align:center}
              h1{margin:0 0 18px;font-size:22px;line-height:1.5;font-weight:600}
              p{color:#687b8b;line-height:1.8;overflow-wrap:anywhere}
              main p:last-child{margin:28px 0 0}
              a{display:inline-flex;align-items:center;justify-content:center;min-height:42px;
                padding:9px 20px;background:#176fa4;border-radius:9px;color:#fff;
                text-decoration:none;font-size:14px;transition:background .15s}
              a:hover{background:#125681}a:focus-visible{outline:3px solid #a6d4f4;outline-offset:3px}
              @media(max-width:480px){main{padding:28px 20px!important}h1{font-size:20px}}
            </style>
            </head>
            """);
    }
}
