# 卫星对时 — LSPosed 源码工程

模块在 Android `system_server` 中尝试启用 AOSP GNSS 时间服务；服务类存在且单一资源 hook 成功时，将时间源排序为 GNSS → 电话网 NITZ → 网络，并在自动改时决策路径上应用用户设置的 1–2000 ms 门槛（默认 100 ms）。

## 安全边界与已知限制

- 当前版本用 Android SDK Platform 35、JDK 17 编译；没有在 PKJ110 真机上验证。ColorOS 可能修改系统服务、资源或启动路径，GNSS 启用结果需以设备日志为准。
- 模块既 hook `ConfigurationInternal.getSystemClockUpdateThresholdMillis()`，也在 `TimeDetectorStrategyImpl.setSystemClockAndConfidenceUnderWakeLock()` 实际决策入口按配置属性覆盖当前阈值。诊断属性仅在实际访问/决策路径命中后报告 `当前门槛:设备原始值`；仅注册 hook 时显示 `armed`。诊断页可输入 1–2000 ms，默认 100 ms；调低门槛只会让较小差值更容易触发自动跳时，不会提高 GNSS/NITZ/NTP 样本精度，并可能导致频繁微调。
- 模块仅在 `android` 系统进程中运行。若 `GnssTimeUpdateService$Lifecycle` 类不存在，模块不会启用 GNSS 资源，也不会把 GNSS 加入优先级。
- 若服务类存在，模块仅 hook `Resources.getBoolean(int)`，并只对 `com.android.internal.R.bool.config_enableGnssTimeUpdateService` 对应的精确资源 ID 返回 `true`；其余布尔资源保持原值。模块不手动构造或启动 SystemService，以保留 Android 正常生命周期流程。
- 成功 hook 资源开关不代表一定收到 GNSS 时间。系统仍需要 GPS provider、定位开关和 GNSS 定位更新。AOSP 服务被动监听位置更新，不会主动开启 GPS；室外开阔环境及已有定位请求可能有帮助。
- 时间建议优先级为 GNSS (origin 4) → Telephony/NITZ (origin 1) → Network (origin 3)。如果 GNSS 建议不存在、太旧或无效，Android 时间检测策略可以评估较低优先级来源。其他 origin 保留在这些来源之后。
- 如果 OEM 删除服务实现、替换资源 ID、改变启动逻辑或阻止系统定位服务提供 GNSS 时间，本模块无法补回厂商缺失的能力。若出现 SystemServer 异常，立即用 LSPosed 安全模式禁用模块并重启。
- NITZ 监视器按每个 SIM 槽位使用 `预测当前 Unix 时间 = NITZ Unix 时间 + (当前 elapsedRealtime - NITZ 参考 elapsedRealtime)` 做被动插值，筛除异常诊断样本并估计长期漂移。它不修改 NITZ 建议、不自行设置系统时钟、也不联网。
- GNSS/NITZ/网络来源优先级只是系统挑选输入的顺序，不等于绝对精度保证。Android GNSS 受定位和卫星接收情况影响，NITZ 本身也不提供毫秒级准确性保证。

## 构建

使用 Android SDK Platform 35、JDK 17 或 21：

```sh
gradle assembleDebug
```

Gradle 原始输出：`app/build/outputs/apk/debug/app-debug.apk`；交付文件名为 `卫星对时_v1.35.apk`。

首次构建需要联网下载 Gradle/Android Gradle Plugin 和 Xposed API 依赖。

## 安装与验证

1. 安装 APK，在 LSPosed 启用“卫星对时”，只需勾选系统框架/Android 系统进程（包名 `android`；管理器可能显示 `系统框架`、`Android` 或 `android`），不需要勾选其他应用。模块 APK 无法自行修改 LSPosed 管理器保存的作用域，请在管理器中确认。
2. 重启设备。启动日志应出现 `GNSS service class present` 和 `config_enableGnssTimeUpdateService ... -> true`。只有阈值 hook 实际命中后，诊断状态才会显示已应用的当前值；只看到 `armed` 代表尚未确认实际决策路径。
3. 开启系统定位开关。在室外开阔处使用导航/定位应用请求一次 GNSS 定位，然后检查 LSPosed 日志是否有 `GNSS time suggestion reached time_detector`。
4. 通过 ADB 检查：

   ```sh
   adb shell dumpsys gnss_time_update_service
   adb shell dumpsys time_detector
   ```

   确认 GNSS 服务存在、`autoOriginPriorities` 包含 GNSS 并排在 telephony/network 前面，且 `Gnss suggestion history` 有记录。再看 `Time change log` 实际采用的来源。只有优先级变化而没有 GNSS suggestion history，表示来源已排序但 GNSS 授时数据尚未到达。
5. 如系统进程启动异常，使用 LSPosed 安全模式禁用模块并重启；卸载/禁用模块后应恢复原有设置。

## 手动 GNSS 授时按钮

桌面启动“卫星对时”后可点击“触发一次 GNSS 授时校准”。按钮会：

1. 覆盖应用内上一次校准结果，并记录本次按钮点击时的 `elapsedRealtime` 作为新基线；本次结果只认可点击之后到达的新建议，旧系统历史记录不会被当成本次结果。
2. 写入 `debug.nitz.gnss` 系统属性；LSPosed 在 `system_server` 中监听该属性变化。在 FgThread 上，模块会在 GNSS 服务锁内移除该服务自己的旧被动监听，再调用 `startGnssListeningInternal()` 重新注册；服务会按自身逻辑取消待执行的 4 小时闹钟。Android 17 AOSP 的方法仍存在；模块通过反射按实际运行系统调用。
3. 按钮点击后立即启动 Android `location` 类型前台服务，持续以 1 秒最小间隔请求 `GPS_PROVIDER` 更新，最多运行 5 分钟；用户可从常驻通知或页面内停止。收到时间戳晚于本次操作基线的新 GNSS 建议后，会提前停止定位。切换到其他应用或关闭屏幕后，前台服务继续工作；结束校准或关闭页面会释放定位资源。页面每 2 秒轮询服务 dump 和建议历史，并显示位置回报次数、最近定位精度及位置时间戳年龄。
4. 若 `GnssTimeUpdateService.handleLocationAvailable()` 回调后仍读到旧 `mLastSuggestedGnssTime`，模块会在本次按钮请求的 5 分钟窗口内每隔至少 15 秒重新挂接一次被动监听，最多 18 次；发现新样本、收到用户取消或达到时限/次数上限即停止。此逻辑只促使服务重新读取 GPS provider 最近位置的时间字段，不会直接命令 GNSS 芯片刷新 UTC，也不保证厂商 HAL 会提供新的 `LocationTime`。校准页默认只显示当前收样状态、GPS 位置回报和卫星数量；点击“查看卫星详情”才展开各星座数量、定位耗时等技术信息。新 GNSS 建议进入 `time_detector` 才显示本次收样成功；定位结果和卫星状态回调本身不等于授时。超时结果会区分未收到样本与“系统回调到达但样本仍旧”，并给出简短的下一步提示。
5. 校准页另外注册公开的 `GnssMeasurementsEvent.Callback` 读取 `GnssClock`。默认摘要只显示本机领先/落后 GNSS 的毫秒值及最近测量年龄；点击“查看技术详情”可展开 FullBias、Bias、TimeNanos、GPS−UTC 闰秒、不确定度、漂移和时钟不连续计数。其计算公式为 `GPS time = TimeNanos - (FullBiasNanos + BiasNanos)`，再换算为 UTC，并按共同的 `elapsedRealtime` 基准与手机时钟比较。缺少必要字段或样本异常时会明确标记不可计算。芯片报告 0 不确定度不等于真实 UTC 绝对误差为零；这些原始读数仅用于诊断，不会自动修改系统时间。持续 GPS 会增加耗电，5 分钟上限用于避免无限后台定位。

关闭校准页面会停止持续定位并记录操作取消；将应用切到后台则由 location 前台服务保持定位，位置与卫星状态通过共享首选项提供给校准页面。任务提交会检查线程池状态，避免 Activity 销毁后仍向已关闭的 worker 提交任务。root 命令会并行读取输出，避免先等待进程退出、再读取管道造成卡住或丢失基线。

按钮需要在 LSPosed 中启用模块并授予 `android`/系统进程作用域、允许应用获得 root、授予精确定位权限，并开启系统定位。首次开始后台持续定位时会请求通知权限，以展示常驻任务和停止入口。建议在室外开阔处使用。重新挂接只让系统服务重新读取已有 GPS provider 的最近位置，不会直接开启卫星接收机或制造 GNSS 时间；持续位置请求和有限重挂仍需 GNSS HAL 返回有效的新 `LocationTime`。ROM 修改私有服务字段/方法时，桥接会安全失败并写入 LSPosed 日志。

该按钮只让 Android 原生 GNSS 时间服务重新取样并提交建议，不直接设置系统时间。即使检测到新 GNSS 样本，系统时钟也可能因与建议相差小于设备阈值而不跳变；可用 `dumpsys time_detector` 查看建议历史和实际时间变更记录。

## 授时诊断/日志页面

主页面优先显示手机时钟是否与卫星时间对齐。只有 GNSS 建议样本不超过 10 分钟，且外推后手机与卫星时间偏差在 ±100 毫秒内，才显示“手机时钟已与卫星时间同步”；收到新建议本身不代表手机时钟已经调整。“本次 GNSS 实际校时”检查 Android `Time change log` 中本次操作后的 `Set system clock ... origin=gnss` 记录。“实际改时来源与系统动作”事件时间线按系统日志显示改钟来源、调整方向与毫秒差；置信度更新单独标记。新增“与北京时间参考误差”卡片，以 Android 网络 NTP 建议样本外推估算本机快/慢，并显示北京时间参考戳、样本年龄和 NTP 上报不确定度；样本超过 24 小时则不再显示估算，超过 10 分钟会标记为仅供参考。它是网络时间估算，不是独立国家授时中心校验，不确定度也不含样本外推后的额外漂移。新增“清理历史记录和日志”按钮，二次确认后清除本应用保存的 GNSS 趋势、定位诊断快照、校准及强制跳改结果，并从事件时间线隐藏清理前的系统动作；运行中的定位或跳改任务期间禁止清理。自动改时门槛设置会保留，Android 自己维护的原始 `time_detector` / `Time change log` 不会被修改或删除。诊断页和校准页统一采用浅蓝/青色液态玻璃风格，两个页面均显示署名“作者：酷安 R1263599071”。趋势图在诊断页前台每分钟采样，显示最近 6 小时记录，并标明最后观测距今多久。应用名称和桌面图标为“卫星对时”及蓝底自适应时钟图标。

估算偏差是把样本按 `elapsedRealtime` 外推到当前时刻后与手机时钟比较，**不是与真实北京时间的绝对误差**。诊断页门槛输入范围为 1–2000 ms，默认 100 ms；Root 写入配置属性后，system_server 在后续授时决策中读取。显示“设定值、生效待确认”时，需等待一次时间建议决策再刷新。改时阈值不是精度承诺；过低门槛可能造成时钟微调频繁。每次按钮操作都会重置应用内的旧校准结果并使用新的单调基线过滤旧建议；Android `dumpsys` 历史由系统管理，不会被模块清空，只用于本次基线之后的改时判断。

## 悬浮时钟与定时事件（v1.35）

首页新增“悬浮时间 / 倒计时设置”。首次开启需在系统设置中授予“显示在其他应用上层”权限；悬浮时钟以用户主动开启的 special-use 前台服务运行，并显示常驻通知。悬浮窗可拖动位置，轻点打开设置；位置、显示模式和样式保存在本机。

悬浮窗支持常规系统时钟与选定事件倒计时，可设置字号 16–72 sp、预设或 HEX 自定义文字颜色、背景透明度 0–100%，以及毫秒显示 0–3 位；设置页提供实时预览。时钟显示本机当前系统时间，倒计时显示到下一目标事件的剩余时间。页面所示毫秒位数仅是显示精度，不代表授时精度。

定时事件支持名称、日期/时间、可选的 HTTP(S) 目标链接、逐事件提前提醒和默认提醒时长，自动分为“待开始/已结束”，并显示下一事件倒计时。v1.35 的提前提醒和到点提醒在精确闹钟权限可用时用 `AlarmManager.setAlarmClock()` 设置为唤醒闹钟；到点通知采用闹钟类别，播放系统默认闹钟铃声并重复振动，最多持续 60 秒，通知上提供“停止提醒”。事件页另有“停止当前提醒”；删除或编辑正在提醒的事件时，会立即停止铃声和振动并撤销该事件通知。提前提醒播放默认通知铃声并短振；目标前最后 3 秒仍尝试短振。声音和振动可在事件页分别开关；“测试提醒声音与震动”是即时声振测试，“12 秒后测试真实闹钟”则验证 AlarmManager 到接收器的完整链路。Android 12+ 需要允许应用“精确闹钟/闹钟与提醒”特殊访问；权限未授权时只会尽力使用非精确提醒，系统可能明显延迟，保存事件和启动真实闹钟测试时会提示授权。Android 14+ 新安装应用可能默认未获精确闹钟权限。通知权限、通知总开关、勿扰模式、闹钟/通知音量、设备振动、省电策略及 OEM 后台管理仍可能抑制提醒。事件通知只在用户点按链接按钮/通知后打开目标，不会自动跳转；事件保存在本机，重启后会尝试恢复。

当前版本暂不含文章示例中的淘宝、天猫、大麦等平台专属时间源，也不把这些平台时间写入系统时钟。悬浮时钟只是显示本机系统时间；实际授时来源与偏差仍应查看“授时诊断”。

## 强制跳改（高级选项）

授时诊断页的“强制跳改”允许选择 GNSS（样本不超过 10 分钟）、基站 NITZ 或网络 NTP（样本不超过 24 小时）。页面先显示目标 UTC 时间、样本年龄和相对当前系统时钟的预计跳变量；目标时间按样本的 `elapsedRealtime` 外推至确认时刻，不是直接回拨到旧样本时间。确认预览后还会再弹出一次最终确认，期间样本如过期会取消操作。只有最终点击“确认跳改”后，应用才通过 root 发出请求，由独立后台任务等待 `system_server` 回执；关闭诊断页不会主动中断该任务。system_server 同时使用系统属性回调和每秒一次的低频轮询来检测请求，并在收到请求时写入 `force-time request received` 日志。若 10 秒内未收到回执，状态显示“无法确认”，而不是断言跳改失败；应检查 force_result 属性和 LSPosed 日志后再决定是否重试。

强制跳改会绕过 Android 自动时间检测的更新阈值，可能影响证书校验、定时任务和日志顺序。来源外推值不一定等于真实北京时间；自动授时之后也可能再次调整系统时间。建议优先选 GNSS，并在每次操作前核对目标 UTC 与偏差预览。本功能尚未在 PKJ110 真机验证。
