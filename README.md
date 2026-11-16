# 鱼记 Android

角色陪伴记账的 Android 首版，Kotlin / Compose，最低 Android 8。

## 已实现
- 手动新增、编辑、撤销账单，整数分存储金额，本地 SQLite 事务保存。
- 微信式聊天气泡与文字自动记账，明确交易由模型提取后保存；含糊信息追问。
- 大肥鱼、小福子、糯米、阿算盘与自定义人设。
- 本月收支、结余、分类比例图。
- HTTPS OpenAI-compatible Chat Completions 接口配置；Android Keystore 加密 API Key。
- JSON 账本导出和合并恢复；聊天最近 100 条本地保存。

## 使用
设置中填写基础地址（例如服务商提供的 https://host/v1）、模型和密钥，再保存。应用追加 /chat/completions。文字和人设会发送给该服务商，不会发送整个账本。不配置 API 也能手动记账。AI 可能识别错误，请核对卡片并编辑/撤销。

## 构建
JDK 17、Android SDK 35、Gradle 8.9。Android Studio 打开根目录，设置 SDK 后运行 app。
Windows：`gradlew.bat assembleDebug testDebugUnitTest`。
APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 当前限制
截图识别、退款/转账专用账务、多账户、预算、多轮追问上下文、对话修改和删除尚未实现。追问后请重新发送完整交易信息。聊天页展示最近四笔账单，完整流水位于账本。接口兼容性需用真实服务商验证；暂无云同步。
备份是明文账单，不包含密钥和聊天；恢复按 ID 合并并覆盖相同 ID。卸载前请导出。

这是开发预览版，已编译 APK 并在模拟器验证启动、手动保存和重启持久化。尚未完成真机和真实 AI 接口验收，不适合直接商店发布。详见 artifacts/VALIDATION.md。
