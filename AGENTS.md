# 开发约定

zmby 是 Kotlin + Jetpack Compose 的 Android TV Emby 多服务器客户端，播放委托给 Zidoo 系统播放器。先读 [README](README.md) 了解构建、功能边界与本地 mock。

## 构建与验证

- JDK 21、Android SDK Platform 36；使用仓库 Gradle Wrapper。`mise.toml` 是可选的 macOS 工具链配置。
- 常规检查：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`。按改动选择相关任务；不要把缓存命中写成新完成的真机验证。
- `python3 -I tools/mock_emby.py --multi` 提供不需要真实凭据的演示服务器。假 Zidoo 播放器只安装到模拟器，不安装到真实盒子。
- 遥控器走查前确认前台 App 与焦点，截图和日志只能使用 mock 或已脱敏内容。

## 实现边界

- 所有条目引用保留 `serverId + itemId`，播放与进度回传必须落到对应服务器。跨服合并优先外部 ID，歧义时保守处理。
- 网络、数据库与文件 I/O 放后台；解析、合并、排序与拼音计算不占主线程。界面优先订阅本地缓存，后台刷新。
- Compose 列表提供稳定 key 与 contentType，保留遥控焦点和返回位置。不要在组合阶段执行大列表计算。
- 修改 Room schema 时更新导出的 `app/schemas/` 并验证迁移；不要用清库代替迁移。
- 不把设备固件行为、模拟器结果、设计目标混写成已验证功能。字幕、302 换链和高码率能力需说明证据范围。

## 公开内容与安全

- 不提交真实服务器地址、账号、令牌、签名直链、设备备份、私钥、签名库、原始日志、第三方 APK 或反编译源码。
- 示例使用 mock、回环地址或保留示例域名；配图使用自有资产。添加依赖时核对许可证并更新第三方声明。
- 项目采用 GPL-3.0-or-later。pinyin4j 按源码 GPL-2.0-or-later 声明处理，不根据其 POM 的 BSD 字样省略 GPL；内含 Sparta 保留 LGPL 声明。分发 APK 时同步提供对应源码和必要许可材料。
- 凭据不进日志；新增 URL / 请求头格式需检查 `log/Redactor.kt` 是否覆盖。报告问题前仍需人工检查日志。
- `.gitignore` 不能清除已跟踪内容或旧历史。公开前检查拟发布文件与历史，不把本地工作区整体推到公共远端。
- `tools/export_public.py --output <新目录>` 生成受控的公开源码副本及 SHA-256 清单；维护该脚本的文件白名单。公开副本不包含本地 Notes 或旧版本历史。
- release 使用 `ZMBY_SIGNING_PROPERTIES` 指向仓库外的发布签名配置；未配置时生成 unsigned APK，不回退到 debug 签名。发布前用 apksigner 校验，并保持后续版本使用同一密钥；密钥与密码单独备份。

## 本地版本控制

保留已有用户修改，先检查 Git 状态与工作区。存在 jj 时核实 `jj workspace root` 与 Git 根目录一致，再创建本次独立 change；不在未被 jj 管理的 Git worktree 中运行写入 jj 命令。并行写入使用独占 workspace，不改写他人 change 或检查点。公开源码副本可使用普通 Git，无需安装 jj。

<!-- agent-notes:begin -->
## Agent Notes

本地开发仓库若包含 `.agents/notes/`，其文档、计划、调研、分析、设计、指南和决策由其中的 README 统一治理；公开源码副本不包含这些本地记录。公开使用与开发说明维护在根目录 README 与本文件，构建、测试和贡献不依赖 Context Engine。

- 非平凡改动在同一变更中更新其权威正文；已有归属就更新，不额外创建重复 ADR。
- 本地可用时先用 CE type=note / `node .agents/bin/agent-notes.js related <关键词>` 检索，read 核验后确定归属。
- 六种 kind 按用途选择，小主题不强制拆分。使用 new --kind、show/write、transition；具体格式见治理说明。
- 保持证据范围与实现状态区别，归档和 rejected 决策冻结。修改本地 Notes 后，结束前 `node .agents/bin/agent-notes.js verify` 必须通过。
<!-- agent-notes:end -->
