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

    /**
     * 生成一次性写入本地 Token 的桥接页，避免依赖目标应用的浏览器会话 Cookie。
     *
     * @param title 页面标题
     * @param heading 页面主标题
     * @param tokenKey 本地 Token 存储键
     * @param token 目标应用本地 Token
     * @param returnPath 写入后跳转路径
     * @param namespaceKey 本地命名空间存储键；为空时不写入
     * @param namespaceId 本地命名空间值
     * @return 桥接页
     */
    public static String bootstrapPage(
        String title,
        String heading,
        String tokenKey,
        String token,
        String returnPath,
        String namespaceKey,
        String namespaceId
    ) {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>%s</title>
            </head>
            <body>
              <main>
                <h1>%s</h1>
                <p id="iam-status">正在写入本地登录状态...</p>
                <div hidden>
                  <input id="iam-token-key" value="%s">
                  <input id="iam-token" value="%s">
                  <input id="iam-return-path" value="%s">
                  <input id="iam-namespace-key" value="%s">
                  <input id="iam-namespace" value="%s">
                </div>
                <noscript><p>当前浏览器已禁用脚本，无法建立本地登录状态。</p></noscript>
              </main>
              <script>
              (function () {
                const value = function (id) {
                  return document.getElementById(id).value;
                };
                try {
                  const token = value('iam-token');
                  const returnPath = value('iam-return-path');
                  if (!token || !returnPath) throw new Error('统一身份桥接结果不完整');
                  localStorage.setItem(value('iam-token-key'), JSON.stringify(token));
                  const namespaceKey = value('iam-namespace-key');
                  const namespaceId = value('iam-namespace');
                  if (namespaceKey && namespaceId) {
                    localStorage.setItem(namespaceKey, JSON.stringify(namespaceId));
                  }
                  window.location.replace(returnPath);
                } catch (error) {
                  document.getElementById('iam-status').textContent =
                    error.message || '本地登录状态写入失败';
                }
              })();
              </script>
            </body>
            </html>
            """.formatted(
            escapeHtml(title),
            escapeHtml(heading),
            escapeAttribute(tokenKey),
            escapeAttribute(token),
            escapeAttribute(returnPath),
            escapeAttribute(namespaceKey),
            escapeAttribute(namespaceId)
        );
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return escapeHtml(value)
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
