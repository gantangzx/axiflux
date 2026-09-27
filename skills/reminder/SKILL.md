---
name: reminder
description: 定时提醒技能。当用户说"提醒我…"、"几分钟后"、"明天几点"等设置提醒/闹钟/定时任务的请求时使用。
triggers:
  - 提醒我
  - 设置提醒
  - remind me
  - 定时提醒
  - 分钟后
  - 几点提醒
requiredTools:
  - schedule_task
  - date_time
sandboxed: false
executionMode: llm_guided
---

# 定时提醒技能

## 触发语
- "提醒我…xx"
- "5分钟后提醒我开会"
- "明天上午10点提醒提交报告"

## 执行流程

### 1. 解析提醒内容
从用户输入中提取：
- 提醒时间（绝对时间或相对时间，时间缺省时先问清楚）
- 提醒内容
- 目标会话（默认投递回当前会话）

### 2. 必要时确认当前时间
如果用户用相对/自然语言时间且你无法确定当前时间，先调用 `date_time` 工具获取当前时间。

### 3. 创建定时任务
调用 `schedule_task` 工具创建任务：
- 相对时间（"30分钟后"）→ delay 调度（毫秒/时间间隔）
- 绝对时间（"明天下午3点"、"每周一9点"）→ cron 调度（本地时间表达式，不转 UTC）
- payload 为 agentTurn：让目标会话到时输出提醒文案
- delivery 用 announce 投递到会话/频道

### 4. 确认信息
返回确认，包含：
- 提醒时间（人类可读格式）
- 提醒内容
- 任务 ID（用于后续取消/修改）

## 工具说明

### schedule_task
- 创建 CRON 定时任务（绝对/周期时间）
- 创建 delay 一次性任务（相对时间）
- 返回任务 ID，可 list/update/cancel

### date_time
- 获取当前日期时间
- 辅助换算自然语言时间为 cron 表达式

## 示例

用户："提醒我30分钟后喝水"
→ 用 date_time 确认当前时间（如需）
→ schedule_task 创建 delay 任务（30 分钟），agentTurn 内容为提醒喝水
→ 回复："已设置 30 分钟后（HH:MM）提醒你喝水。任务 ID：..."

用户："提醒我明天下午3点开会"
→ 换算为明天 15:00 的一次性 cron
→ schedule_task 创建 at/cron 任务
→ 回复："已设置明天 15:00 提醒你开会。任务 ID：..."
