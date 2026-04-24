# PROJECT CONVENTIONS & FILE SYSTEM RULES

**Target Audience:** All Claude Agents, Human PMs, Architects, Workers, Librarians  
**Mode:** Script-driven workflow (no scheduler); Reviewer-based gate; Conversation resume on reject  
**Effective:** Phase 1+ (EPIC-TKT-001 restructure)

---

## 1. 目录架构与 Agent 操作权限

| 目录 | 用途 | 写权限 | 读权限 |
|:--|:--|:--|:--|
| `docs/01_living_architecture/` | 冻结架构（domain models, state machine, API contracts） | librarian | All agents (mandatory read) |
| `docs/02_decision_records/` | ADR 存档（核心设计决策） | librarian | All agents |
| `docs/03_features_workspace/` | **活跃工作台**（PM/Architect/Worker/Librarian 的执行区） | PM/Architect/Worker | All agents |
| `docs/ROADMAP.md` | Phase 规划与全局上下文 | PM（人类） | All agents |
| `.agents/` | 工作区配置与规范文档（**本文件**） | librarian | All agents |
| `src/main/resources/db/migration/` | Flyway 迁移脚本 | Worker | All agents (read) |

---

## 2. 核心命名规范

### 2.1 Feat 文件夹（在 docs/03_features_workspace/ 下）

**Regex:** `^feat-[A-Z]{3}\d{3}-\d{2}-[a-z0-9\-]+/?$`  
**格式:** `feat-[EPIC缩写+流水号]-[2位feat序号]-[动宾短语]`  
**示例:** `feat-TKT001-01-reservation-backbone`, `feat-TKT001-02-payment-confirmation`, `feat-TKT001-03-timeout-audit`

### 2.2 Feat 内文件（PM/Architect/Worker/Librarian 产出）

| 文件名 | 产出人 | 含义 |
|:--|:--|:--|
| `1_pm_spec.md` | PM | 需求规格（问题、价值、约束、决策点） |
| `1_refactor_spec.md` | PM-Refactor（仅重构路径） | 重构需求 |
| `2_blueprint.md` | Architect | 行为设计 + 存储设计（可合并） |
| `3_pr_summary.md` | Worker | 实现摘要 + 完成标记 + 验证清单 |
| `4_execution.jsonl` | (Optional) Worker/Reviewer | 内部博弈日志（重试、决策轨迹） |
| `0_scout_map.md` | Scout（仅重构路径） | 代码地图 |
| `0_behavior_report.md` | Behavior Analyst（仅重构路径） | 现状行为分析 |
| `0_structure_report.md` | Structure Analyst（仅重构路径） | 现状结构分析 |

**Regex for filenames:** `^[0-4]_[a-z_]+\.md$` 或 `4_execution\.jsonl$`

### 2.3 ADR 文件（在 docs/02_decision_records/ 下）

**Regex:** `^ADR-\d{3}-[a-z\-]+\.md$`  
**格式:** `ADR-[3位流水号]-[决策简称小写连字符]`  
**示例:** `ADR-001-non-blocking-concurrency.md`

### 2.4 Flyway 迁移脚本

**Regex:** `^V\d+\.\d+\.\d+__[a-z_]+\.sql$`  
**约束:** 只能由 Worker 生成；严禁生成在初始脚本中包含性能优化索引（仅 PK/UK 允许）。

---

## 3. Agent 定义与职责

| Agent | 角色 | 输入 | 输出 | 交付物 |
|:--|:--|:--|:--|:--|
| **PM** | 需求定义者 | ROADMAP.md + 人类需求 | 1_pm_spec.md | feat 进入工作台 |
| **Architect** | 设计承载者 | 1_pm_spec.md + 现状代码 | 2_blueprint.md | 设计冻结 |
| **Worker** | 实现执行者 | 2_blueprint.md | 代码 + 3_pr_summary.md | PR / commit |
| **Reviewer** | 门卫 | 3_pr_summary.md + git diff | Approve / Reject | 决定流转 |
| **Librarian** | 存档合并者 | 3_pr_summary.md + Reviewer Approve | 1. 提取决策到 02_decision_records/ 2. 更新 01_living_architecture/ 3. 标记 feat 完成 | 活字典更新 |

**Path A（默认）:** PM → Architect → Worker → Reviewer → Librarian  
**Path B（重构）:** Scout → Behavior Analyst → Structure Analyst → [Path A 从 Architect 开始]  
**Path C（PM-Refactor）:** PM-Refactor → [Path A 从 Architect 开始]（PM-Refactor 产出 1_refactor_spec.md）

### 无白名单

信任 Reviewer 的判断。Worker **自由实现**；Reviewer 审批通过后提交。不需要预先白名单审批。

---

## 4. Reviewer 拒绝 → 对话恢复

**Reviewer Reject 流程：**

1. Reviewer 读取 3_pr_summary.md + git diff
2. 若发现问题，返回 `REJECT: [reason]`
3. 触发 Claude 对话恢复：重新加载 feat 上下文 + Reviewer 反馈
4. Worker 修复 → 输出新 3_pr_summary.md + git commit
5. Reviewer 再审 → Approve 或继续 Reject

**实现机制：**
- feat 文件夹保存所有中间态（1_pm_spec, 2_blueprint, 3_pr_summary, 4_execution.jsonl）
- Conversation resume 时自动加载这些文件 + 最新 git diff
- 无需 WORKSPACE_STATE.yaml 或 HANDOFF_NOTES.md（文件系统本身即状态）

---

## 5. 活字典冻结规范

完成 Reviewer Approve 后，Librarian 负责：

1. **提取 ADR：** 从 2_blueprint.md 中提取关键设计决策 → docs/02_decision_records/ADR-NNN-*.md
2. **更新活字典：** 将 2_blueprint.md 内容合并到 docs/01_living_architecture/（domain_models.md / global_state_machine.md / api_contracts/*.md）
3. **标记 feat 完成：** 在 3_pr_summary.md 添加 Librarian sign-off 时间戳

**不许 Librarian 做的事：**
- 删除或修改旧 feat 输出（保留完整审计轨迹）
- 覆盖已冻结的 ADR（若修改，必须新增 ADR-NNN）

---

## 6. Git 提交规范

所有 commit 由 Claude 或人类执行；Agent **建议而不提议**。

```
feat(domain): [描述] [feat-ID]
docs(architecture): extract ADRs from [feat-ID]
chore(librarian): archive [feat-ID] to living architecture
```

**禁止：**
- `git push --force`（除非 Reviewer 明确授权）
- `git commit --amend`（修复用 new commit）
- Co-Authored-By 行（Agent 不署名）

---

## 7. 核心资产指针（强制读取）

Agent 执行前必读：

1. **docs/ROADMAP.md** — Phase 规划、依赖关系、完成门槛
2. **docs/01_living_architecture/** — 当前冻结架构（domain_models, state_machine, api_contracts）
3. **docs/02_decision_records/** — 历史设计决策 (ADR)
4. **docs/03_features_workspace/** — 活跃工作台（feat 文件夹）

---

## 8. 信息流总结

```
人类需求 / code review request
        ↓
   ┌────────────────────────────────┐
   │ PM:  1_pm_spec.md              │
   ├────────────────────────────────┤
   │ Architect: 2_blueprint.md       │
   ├────────────────────────────────┤
   │ Worker: code + 3_pr_summary.md  │
   ├────────────────────────────────┤
   │ Reviewer: Approve/Reject        │
   │   [On Reject: resume conversation with Worker]
   ├────────────────────────────────┤
   │ Librarian: archive + sign-off   │
   │   - Extract ADR → 02_decision_records/
   │   - Update 01_living_architecture/
   │   - Mark feat complete
   └────────────────────────────────┘
        ↓
  Feat 进行存档；Phase 继续推进
```

---

## 9. 违规与补救

| 违规行为 | 后果 | 补救 |
|:--|:--|:--|
| Agent 擅自执行 `git commit` | 对话中止；人类手工撤销 | 由 Reviewer 重新审查并下达 APPROVE/REJECT |
| Worker 忽视 Architect 设计 | PR 被 Reviewer 拒绝 | Conversation resume；重新对齐 2_blueprint.md |
| Librarian 覆盖或删除旧 feat | 审计轨迹被破坏 | 立即恢复；重新冻结规范 |
| 文件名不符合 Regex | 文件被系统拦截 | 重命名后重新提交 |

---

## 10. 版本历史

| 版本 | 日期 | 改动 |
|:--|:--|:--|
| v1.0 (Script-driven) | 2026-04-24 | 替换 scheduler-driven 工作流；引入 PM/Architect/Worker/Reviewer/Librarian；无白名单；对话恢复机制 |
| v0.x (Scheduler-era) | 2026-04 | 已弃用 |
