# IWS 功能扩展索引

通用扩展清单以全局[扩展功能索引](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/index.md)为唯一清单。这里只增加 IWS 项目路由：按当前任务选一项，再读全局单项，不批量预读 16 篇。

| 任务分支 | 按需入口 |
| --- | --- |
| 外部业务接入、多节点流程、异步结果回调；计划事件、INBOUND、OUTBOUND、INTERNAL、UNKNOWN/checkpoint 恢复时先评估复用 | [IWS 计划事件项目接入](../../iws-plan-event-project/SKILL.md) |
| AI、SnailAI、Agent、OpenAPI、RAG | [AI / SnailAI](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/ai.md) |
| 外部 MCP 调用或服务端暴露 | [MCP 集成](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/mcp.md) |
| SSE、WebSocket、用户消息、广播 | [统一消息推送](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/message_push.md) |
| MQTT 客户端、订阅、设备监听 | [MQTT 客户端](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/mqtt.md) |
| DS 路由、跨源事务 | [多数据源](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/dynamic_datasource.md) |
| 短信、邮件 | [短信模块](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/sms.md)、[邮件功能](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/mail.md) |
| 表单防重、导入防重、业务幂等 | [防重幂等](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/idempotent.md) |
| Redis、Spring Cache、失效与过期 | [缓存使用](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/cache.md) |
| VO 脱敏、API 报文加密、字段加密 | [数据脱敏](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/sensitive.md)、[API 加解密](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/api_encrypt.md)、[数据加解密](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/encrypt.md) |
| ID 映射、批量翻译、导出展示 | [翻译功能](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/translation.md) |
| SkyWalking、MaxKey、TOPIAM | [SkyWalking](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/skywalking.md)、[MaxKey](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/maxkey.md)、[TOPIAM](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/references/framework/extend/topiam.md) |

## IWS 裁决点

- 计划事件中的幂等由来源合同、载荷摘要、计划命令键或业务任务键决定；通用防重不能替代这些领域事实。
- 消息推送和 MQTT 是传输能力。异步业务结果仍要按计划事件合同进入独立 `INBOUND`，不能把消息已发送当作业务完成。
- 缓存可作为性能层，不能替代实例、事件、检查点、请求或审计中的持久事实。
- 日志、文档、示例和错误报文继续遵守脱敏与安全引用边界；启用某项扩展前核对当前依赖、配置开关和真实调用方。
