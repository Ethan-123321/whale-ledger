# 0.1 验证记录

- Android debug APK 编译成功，已在 emulator-5556 安装并启动。
- 模拟器手动添加 Lunch 28.50，保存后显示 -¥28.50；强制停止并重新启动后记录仍存在。
- 截图检查：聊天气泡、输入框、账单卡片和导航可见。当前验证设备为横屏模拟器，手机竖屏与软键盘适配仍需进一步验收。
- MoneyTest 两项通过直接 JUnitCore 执行：精确到分、非法金额拒绝。
- Gradle testDebugUnitTest 启动器报 MoneyTest ClassNotFoundException，直接执行同一已编译测试类通过；测试启动问题未解决。
- 没有用户 API 凭据，未进行真实 AI 服务端到端验证。
- 备份恢复、角色切换、编辑撤销尚未完成自动化 UI 验证。

交付为开发预览 APK，不是应用商店正式发布包。
