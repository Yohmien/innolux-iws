# PDA 能力复用

路径相对于 PDA 仓库。当前为 HBuilderX/uni-app、Vue 3、JavaScript、uView Pro 和 Vuex，不沿用 Web 的 Element Plus/Pinia 构建体系。

## 入口导航

| 需求 | 当前入口 |
| --- | --- |
| 应用与插件 | `main.js`、`App.vue` |
| APK 强制更新 | `utils/apk-update.js`、`api/appUpdate.js`、`components/apkUpdateGate/apkUpdateGate.vue`、登录页及 `App.vue` 恢复检查 |
| 页面注册 | `pages.json`，包含注释，按 uni-app 配置处理 |
| 业务 API | `api/work`；系统 API 在 `api/system` |
| 统一 HTTP | `utils/request/index.js`、uView Pro 的 `http` |
| 其他请求封装 | `utils/request/request.js`，先确认调用方，不无故合并 |
| 登录与令牌 | `api/login.js`、`utils/auth.js`、`store/modules/user.js` |
| 状态与字典 | `store/index.js`、`store/modules/dict.js`、`utils/dict.js` |
| 扫码桥接 | `components/scanCode/scanCode.vue` |
| 扫码样例 | `pages/work/testModule/testView.vue`、`testViewTwo.vue` |
| 声音反馈 | `common/radio.js` |

`api/work/inbound.js` 的 testAPI/secondTestAPI 请求根路径，仍是测试占位；不要据此编造入库 API 或状态机。

## 请求契约

现有统一配置从 `config.js` 取 baseUrl/clientId，再拼 `/innolux`；H5 由 `vite.config.js` 代理并移除前缀，设备端则需确认最终 URL 和网络配置。不要机械把 Web 的 `/dev-api` 带入 PDA。

请求拦截处理 Bearer token、clientid、特定 POST/PUT 的请求体加密及 `meta.loading/music`。复用既有方法，不新造第二套鉴权和加密。配置含敏感值时只检查必要字段，不复制到日志或文档。

响应拦截当前返回业务响应对象 `res.data`，业务载荷仍需按接口读取；部分非 200 分支未统一 reject。新增调用方必须判断业务结果，不能仅因 Promise fulfilled 就提示作业成功；修复全局拦截行为需要核查所有调用方并明确范围。

## 扫码与页面生命周期

当前桥接仅在 APP-PLUS 中访问 `plus.android`，广播 action 为 `android.intent.ACTION_DECODE_DATA`，条码键为 `barcode_string`，应用事件名为 `scancodedate`，载荷为 `{ code }`。这些是当前设备适配，不代表所有 PDA 厂商通用。

复用已有桥接与输入组件。新增页面时用明确的回调注册和解除订阅，核对显示、隐藏、返回、卸载与重复进入行为，避免后台页面消费条码或重复注册。150ms 本地抖动抑制不能代替服务端业务幂等。

当前桥接仅在 onMounted 启动接收，onDeactivated 和 onUnmounted 均直接注销，未提供 onActivated 恢复及注册状态保护。若复用场景存在停用后再激活或重复清理，必须核对桥接的恢复和幂等清理；仅恢复页面的事件订阅不能证明硬件广播已恢复。这是源码可见风险，是否在当前挂载方式下发生仍需真机验证，不要求无关任务先修桥接。

不直接复制样例的全局 `uni.$off('scancodedate')` 作为跨页面清理方案；根据实际订阅者解除当前回调。已有代码的问题只在任务范围内修复。

## 验收边界

Android 更新使用 PackageManager 的真实包名和 longVersionCode，版本代码用十进制字符串比较。登录请求和 GetInfo 异步完成后须复核当前检查代次再跳转；业务页恢复时检查新版，失败或不匹配回登录门禁。安装前核验下载文件大小、包名与版本，调起系统安装不代表安装已完成；取消、权限和恢复行为需真机核验。部署门禁客户端前必须先有对应包名的已发布 APK，后端说明见 `docs/apk-management.md`。

新增页面同时核对 pages.json、API、权限与实际返回层级。H5 可验证基础页面和代理，Android 广播接收、设备声音及隐藏/恢复行为必须真机验收。根目录没有 package.json，不能虚构 pnpm build/test 命令，也不把 unpackage 产物和 uni_modules 第三方源码纳入普通业务修改。
