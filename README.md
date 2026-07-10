# GeminiMi

国行 HyperOS 下用 LSPosed 把"长按电源键唤起小爱"换成"长按电源键唤起 Gemini Overlay"。
对标国际版 Mi 设置里的"数字助理 → Google"路径，自动化教程里所有手动步骤，并修复重启
后数字助理被重置的问题。

## 等价的手动步骤（本模块自动完成）

| 教程步骤 | 本模块对应 |
|---------|----------|
| 关闭"超级小爱 → 电源键唤醒" | `XiaoAiPowerKeyDisableHook` |
| 默认应用 → 语音助手选 Google | `AssistantPersistHook` 写入系统设置 |
| 数字助理选 Google | 同上 |
| 开启"使用屏幕上的文字内容 / 使用屏幕截图" | `AssistantPersistHook` 写 `Secure.assist_structure_enabled` / `Secure.assist_screenshot_enabled` |
| 语音输入 / 语音识别选 Google | 同上 |
| 电源键唤起 Google 助手 | `PowerKeyOverlayHook`（直接接管按键事件） |
| 创建快捷方式 → 电源菜单 → 数字助理 | `AssistantPersistHook` 写 `Global.power_button_long_press = 5` |
| 重启后重选 | `AssistantPersistHook#applySystemServer`（启动及用户解锁时回写） |

## 编译

```
gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk` —— 自签后在 LSPosed
管理器里启用，作用域勾选 `android` / `com.miui.voiceassist`，重启生效。

## 已知限制

- `PowerKeyOverlayHook` 按已知命名模式扫描 `PhoneWindowManager` 助手入口，不同
  HyperOS 版本可能命名不同；如果
  长按无反应，抓 `logcat -s "[GeminiMi]"` 看是否打印了 `hooked PhoneWindowManager#…`。
  没打印就反编译当前 ROM 的 `services.jar` 找实际方法名补进 `PowerKeyOverlayHook`。
- 系统助手统一设置为 Google App 的 VoiceInteractionService，再由 Google App
  转发到 Gemini Overlay；因此 Google App 和 Gemini 都必须已安装并完成助手配置。
- 模块会在每个用户解锁时写入对应用户的助手设置。开机期间会阻止 HyperOS 回写
  小爱，系统发出 `BOOT_COMPLETED` 后不再阻止用户手动切换助手。
