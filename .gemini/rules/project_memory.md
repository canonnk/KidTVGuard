# 项目全局记忆（Project Memory）

## 1. 目标终端硬件规格（Target Device Hardware）
- **设备型号**：**小米电视4A 65英寸 黑色**（Xiaomi TV 4A 65-inch Black）
- **物理屏幕**：65 英寸 4K 超高清（3840 × 2160），60Hz 刷新率，广视角
- **交互方式**：红外 / 蓝牙遥控器 D-Pad 十字方向键 + 确认键（OK / Enter）
- **UI 渲染画布**：标准 1080P 画布（1920 × 1080 @ xhdpi/hdpi），大屏客厅远距离观看（视距 2.5 米 ~ 3.5 米）

## 2. 大屏视觉与焦点边距规范（Focus & Border Margin Standard）
- **焦点边框防溢出原则（Zero-Overflow Policy）**：
  - 在 65 英寸巨幕上，遥控器获得焦点/选中状态时，按钮若放大或显示高亮边框，**严禁超出父容器边框、严禁被容器截断、严禁遮挡相邻元素**。
  - 所有承载焦点动效的父容器（ScrollView、LinearLayout、Card 等）必须显式声明：
    `android:clipChildren="false"` 与 `android:clipToPadding="false"`。
- **边距安全冗余要求（Safety Margins）**：
  - **选项与独立操作按钮**（如答题 A/B/C/D 选项、立即锁屏按钮等）：四边必须预留充足的安全外边距（Margin 至少 6dp ~ 10dp），且缩放比例控制在平滑适度的 `1.02f ~ 1.025f`，预留足够的呼吸外延空间。
  - **分组卡片内嵌行（Inset Grouped Rows）**：卡片内部的列表行（如权限行、设置行、看板入口行）获得焦点时，**禁止采用大比例物理外扩放大**（scale = 1.0f 或 1.008f），应以内嵌圆角高亮背景和发光描边为主，且卡片必须拥有足够的内边距（padding 至少 8dp ~ 10dp），确保焦点边框严格收敛在卡片外圆角内部。
  - **分段控制器（Segmented Control）**：限额选项按钮（5分/10分/15分等）获得焦点时保持在轨道槽内切换高亮，不得溢出 40dp 槽位。
