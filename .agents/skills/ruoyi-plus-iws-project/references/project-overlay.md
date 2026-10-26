# IWS 项目覆盖

本文件只补充项目差异。后端、前端、示例和框架细节继续使用全局 `ruoyi-plus-ai-coding` 的命中参考；集中规约仍是项目权威入口：[IWS 集中项目规约](C:/code/company/doc/innolux-iws/agent.md)。

| 上游概念 | IWS 当前约定 | 核对位置 |
| --- | --- | --- |
| 项目模块与 Java 包 | `iws-*` 模块、`com.innolux.iws.*` | 当前模块声明与 import |
| 第三方依赖包 | 保留实际依赖包名 | 当前依赖与源码 |
| 代码生成器 | `iws-modules/iws-gen/src/main/resources/fm` | 当前生成器文件 |
| 后端缩进 | 4 空格 | 仓库根 `.editorconfig` |
| Web 缩进 | 2 空格 | 仓库根 `.editorconfig` |
| PDA 格式 | 保留目标文件现状 | 当前文件与邻近实现 |
| 常规分页响应 | `R<PageResult<Vo>>` | 当前 Controller、调用方与生成模板 |
| WMS 标准表页 | 复用 `IwsCrudPage`、CRUD hooks、通用 API、元数据注册和白名单校验 | [标准表页设计](../../../../docs/iws-standard-crud-page-design.md) |
| Web | Vue 3、TypeScript、Pinia | 当前源码 |
| PDA | uni-app、Vue 3、JavaScript、Vuex | 当前源码 |
| 业务展示名 | `IWS+` | 当前项目文案 |
| 新增可注释代码文件 | 沿用后端 README 的统一文件头，填写真实日期和用途 | 后端 `README.md` 与邻近源码 |

先查当前实现再采用表中映射。标准表页只声明已有能力时，不展开全部生成器模板；新增模块、包名、路径、权限、事务、缓存或数据语义发生实质变化时，再进入全局对应指南和集中项目规约的验证要求。
