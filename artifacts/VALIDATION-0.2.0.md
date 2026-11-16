# 0.2.0 验证

- assembleDebug：BUILD SUCCESSFUL。
- 模拟器覆盖安装成功，versionCode=2 / versionName=0.2.0。
- 启动与设置页 UI 检查通过：DeepSeek / 自定义、自动地址、密钥、连接并获取模型、模型选择及手动备用入口可见。
- git diff --check 通过。
- 没有真实 API 密钥，模型列表成功响应、鉴权失败、超时等网络路径尚未完成端到端测试。
- 本版沿用原包名和数据库；仍为 debug 预览包。
