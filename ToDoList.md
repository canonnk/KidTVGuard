# 小米电视防沉迷强制答题锁 App - 开发任务清单（ToDoList）

> **说明与规范**：
> 1. 本清单依据 `项目开发文档.md` 与 `项目规划.md` 编制，覆盖项目从底层数据持久化到系统级防逃逸与家长端管理的全流程。
> 2. **AI 与开发者规则**：在每完成一个子功能或模块的编码与测试后，**必须及时更新本文件**，将对应的任务项由 `[ ]` 改为 `[x]`。

---

## 阶段一：基础架构与数据持久层（SQLite）

- [x] **任务 1.1**：Android TV 基础工程搭建与构建配置
  - [x] 初始化工程结构（包名 `com.parent.tvlock`，minSdk 23）
  - [x] 基础 `MainActivity` 与 Manifest 结构搭建
- [x] **任务 1.2**：工程依赖与基础工具准备
  - [x] 引入 `OkHttp`（网络请求）与 `Gson`（JSON 序列化解析）依赖
  - [x] 引入轻量协程组件 `kotlinx-coroutines-android`
  - [x] 编写轻量日志工具与时间格式化工具 `DateTimeUtils.kt`
  - [x] 编写配置管理器 `PreferenceManager.kt`（存储锁屏倒计时、锁机标志 `isLocked`、服务器 URL、家长密码）
- [x] **任务 1.3**：原生 SQLiteOpenHelper 与数据实体定义
  - [x] 编写数据实体类：`Question.kt`（题目）、`WrongQuestion.kt`（错题本）、`AnswerRecord.kt`（答题流水）、`AppUsageRecord.kt`（电视应用活动）
  - [x] 编写统一数据库管理单例 `AppDatabaseHelper.kt`，完成 4 张核心表的建表 DDL 及索引创建
- [x] **任务 1.4**：DAO 数据访问层开发
  - [x] 编写 `QuestionDao.kt`：题目增删改查、批量导入事务、随机抽取未答对题目
  - [x] 编写 `WrongQuestionDao.kt`：错题记录新增、错误次数累加 `wrongCount + 1`、攻克状态更新 `isMastered`、错题列表查询
  - [x] 编写 `AnswerRecordDao.kt`：答题流水日志写入、做题统计与历史记录分页查询
  - [x] 编写 `AppUsageRecordDao.kt`：应用使用记录写入、按日按应用时长汇总统计

---

## 阶段二：题库同步、图片离线缓存与本地兜底

- [x] **任务 2.1**：内置离线兜底题库
  - [x] 在 `assets/default_questions.json` 中配置 5 道以上的语数英理化初中测试题（包含题干文本、选项、解析、几何图形图片字段）
  - [x] 在自定义 `App.kt` 初始化逻辑中，判断数据库若为空则自动异步解析并导入兜底题目
- [x] **任务 2.2**：几何/物理题干图片离线下载器
  - [x] 编写 `ImageDownloadHelper.kt`：下载网络图片至 `context.filesDir/question_images/`
  - [x] 保存并更新本地图片路径 `localImagePath`，实现断网环境依旧可离线显示图片
- [x] **任务 2.3**：联网题库同步引擎
  - [x] 编写 `SyncManager.kt`：通过 OkHttp 异步请求服务器 JSON 接口（默认 `http://127.0.0.1:3000/questions.json`）
  - [x] 实现远程题库增量或覆盖同步，自动下载图片并写入 SQLite

---

## 阶段三：后台常驻保活与断电死锁保障

- [x] **任务 3.1**：后台常驻前台服务（`LockForegroundService`）
  - [x] 创建常驻前台通知渠道与持久通知（Notification）
  - [x] 实现倒计时计时器（支持读取家长配置时长，默认 10 分钟），计时结束触发全局锁屏
  - [x] 服务重写 `onStartCommand` 返回 `START_STICKY` 保障进程被杀后自启
- [x] **任务 3.2**：开机自启与断电死锁机制
  - [x] 编写静态广播接收器 `BootReceiver.kt`，注册监听 `BOOT_COMPLETED`
  - [x] 动态注册亮屏广播 `ACTION_SCREEN_ON`
  - [x] 实现断电死锁逻辑：开机或亮屏时检测 `PreferenceManager.isLocked`，若未解锁立即强制弹出答题悬浮窗

---

## 阶段四：全局答题悬浮窗与遥控器焦点交互

- [x] **任务 4.1**：全局悬浮窗管理器（`OverlayLockManager`）
  - [x] 封装单例管理器，基于 `WindowManager` 动态添加/移除顶层悬浮窗（`TYPE_APPLICATION_OVERLAY`）
  - [x] 全屏占满配置与异常安全防护（处理 `BadTokenException` 等）
- [x] **任务 4.2**：电视答题横屏 UI 设计
  - [x] 编写答题布局：科目标签、题干文字、几何题图片 `ImageView`（无图自适应隐藏）、A/B/C/D 四个大尺寸选项卡片
  - [x] 编写解析展示区域与状态反馈动效
- [x] **任务 4.3**：电视遥控器 D-Pad 焦点适配
  - [x] 为选项按钮定制 focus 状态 drawable（发光高亮边框、选中缩放放大动效）
  - [x] 编写 `FocusHelper.kt`，悬浮窗弹出后强制优先聚焦选项 A，保证遥控器开箱即用
  - [x] 拦截遥控器 `KEYCODE_BACK` 返回键，禁止退出悬浮窗
- [x] **任务 4.4**：智能判题与数据联动闭环
  - [x] 用户按遥控器 `OK` 键提交答案，记录作答耗时并写入 `answer_records` 表
  - [x] **答对逻辑**：更新题目为已答对；若在错题本中则更新 `isMastered = 1`；提示成功并延时关闭悬浮窗，通知前台服务重启 10 分钟倒计时
  - [x] **答错逻辑**：记录/累加错题至 `wrong_questions` 表；标红错误项并弹出题目详细解析，保持锁定并刷新下一题

---

## 阶段五：无障碍服务（防逃逸拦截 + 电视活动监控）

- [x] **任务 5.1**：无障碍服务基础搭建（`TvAccessibilityService`）
  - [x] 声明无障碍 XML 配置文件及 `AndroidManifest.xml` 服务声明
  - [x] 实现返回键与 Home 键（`KEYCODE_HOME`）在锁屏期间的强制拦截
- [x] **任务 5.2**：防逃逸与设置/卸载界面拦截
  - [x] 监听 `TYPE_WINDOW_STATE_CHANGED`，捕获系统设置包名（如 `com.android.settings`）及应用安装器
  - [x] 编写 `ParentPasswordOverlayView.kt`：全屏弹出家长 6 位密码拦截窗（默认密码 `888888`），阻断孩子试图进入系统设置强退或卸载
- [x] **任务 5.3**：电视日常活动记录与应用时长监控
  - [x] 实时捕获前台应用包名与应用名称（`appName`）
  - [x] 应用切换算法：检测到 App 切换、回桌面或关屏时，自动结算前一 App 的使用时长（过滤 5 秒内闪现无效弹窗）
  - [x] 将使用记录（包名、应用名、开始时间、结束时间、时长秒数、日期）写入 `app_usage_records` 表
  - [x] 过滤电视系统桌面 Launcher、输入法及本 App 自身的悬浮窗，确保只统计真实应用

---

## 阶段六：设备管理器（DeviceAdmin）防卸载

- [x] **任务 6.1**：设备管理器配置与接收器
  - [x] 编写 `res/xml/device_admin_policies.xml` 设备策略配置
  - [x] 编写 `TvDeviceAdminReceiver.kt` 接收器并在 Manifest 中声明
- [x] **任务 6.2**：反取消激活与防卸载闭环
  - [x] 在家长后台提供一键激活设备管理器跳转
  - [x] 结合无障碍服务阻断针对设备管理员的停用流程

---

## 阶段七：家长设置管理后台与数据看板（MainActivity）

- [x] **任务 7.1**：权限检测与一键引导卡片
  - [x] 悬浮窗权限、无障碍服务权限、设备管理器激活状态实时状态检测
  - [x] 一键跳转到系统对应授权页面的辅助引导流程
- [x] **任务 7.2**：核心参数设置与测试面板
  - [x] 倒计时时长修改设置（5 / 10 / 15 / 30 / 60 分钟可调）
  - [x] 题库同步接口 URL 配置与一键“立即拉取同步”按钮
  - [x] “立即测试锁屏”按钮（便于家长与开发实时验证）
  - [x] 家长密码修改功能
- [x] **任务 7.3**：数据看板三模块开发
  - [x] **答题历史记录页（`QuestionHistoryActivity`）**：时间流展示作答详情、耗时、选项及对错状态
  - [x] **错题本复盘页（`WrongQuestionsActivity`）**：展示错题频次、最后做错时间，支持手动触发“错题强化重练”
  - [x] **电视活动与时长统计页（`AppUsageStatsActivity`）**：按日期分组展示孩子观看的应用列表与总时长柱状统计

---

## 阶段八：真机与遥控器调优测试

- [ ] **任务 8.1**：全流程纯遥控器（D-Pad）操作与焦点走位校验
- [ ] **任务 8.2**：断网状态与兜底题库加载测试
- [ ] **任务 8.3**：电视重启 / 拔插头死锁恢复鲁棒性测试
- [ ] **任务 8.4**：长时间后台运行与前台应用时长监控准确度验收
