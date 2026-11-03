# APK 验收材料

后端 APK 测试保存在 `iws-modules/iws-system/src/test`，只随个人仓库交付。前端来源仓库仅推送产品页面和 PDA 更新功能，测试原路径补丁保存在本目录：

| 文件 | 应用仓库 | 执行入口 |
| --- | --- | --- |
| web-tests.patch | innolux-iws-web | 应用补丁后运行 `pnpm exec vitest run src/api/system/apk/__tests__/presentation.spec.ts` |
| pda-tests.patch | innolux-iws-pda-plus | 应用补丁后运行 `node --test tests/apk-update.test.mjs` |

在对应前端仓库中先运行 `git apply --check <补丁绝对路径>`，确认无已有文件冲突后再运行 `git apply <补丁绝对路径>`。本机已保留这些测试文件，不需重复应用。

升级与启动见 [APK 配置说明](../../apk-management.md)。`project-guidance` 是交付时的集中项目指引快照，实际本机权威入口仍在集中规约目录，不将快照另行注册为 Skill。

验证记录：后端 39 项单测和 2 项真实隔离 MySQL 集成测试、Web 4 项逻辑测试和浏览器交互、PDA 10 项逻辑测试通过。未执行真实业务库升级，未将 Android 真机安装或生命周期标记为已验收。
