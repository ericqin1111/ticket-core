# PROJECT CONVENTIONS & FILE SYSTEM RULES
**Target Audience:** All AI Agents & Human Developers
**Strict Rule:** No agent is allowed to create, rename, or modify files outside of the permissions and naming formats defined in this document.

---

## 1. 目录架构与 Agent 读写权限 (Directory Map & Permissions)

| 目录路径 | 用途说明 | 写权限 (Write) | 读权限 (Read) |
| :--- | :--- | :--- | :--- |
| `.agents/` | 机器工作区与全局状态中枢 | `scheduler`, `governance` | All Agents |
| `docs/01_registries/` | 活字典 (API、DB 结构、功能名录) | `governance` | All Agents (强制读取) |
| `docs/02_epics/` | 宏观领域文档 (业务愿景与核心规则) | `requirement-analyst` | All Agents |
| `docs/03_rfcs/active/` | 活跃的微观图纸 (当前正在开发的变更) | `requirement-analyst`, `system-architect`, `database-engineer`,`reviewer`| All Agents |
| `docs/03_rfcs/archived/`| 已上线的历史 RFC 归档 | `governance` | `scheduler` |
| `docs/04_adrs/` | 架构决策记录 (ADR) | `system-architect` | All Agents |
| `src/main/resources/db/migration/` | Flyway 数据库迁移脚本 | `database-engineer` | `implementation`, `reviewer`, `qa` |

---

## 2. 核心命名规范 (Naming Formats & Regex)

所有 Agent 在创建文档时，必须进行正则自检。不符合以下命名规范的文件将被系统拦截。

### 2.1 宏观领域文档 (Epic)
* **Regex:** `^EPIC-[A-Z]{3}-\d{3}-[a-z0-9\-]+\.md$`
* **格式:** `EPIC-[3位领域大写缩写]-[3位流水号]-[核心业务小写连字符].md`

### 2.2 微观变更图纸 (RFC)
* **Regex:** `^RFC-[A-Z]{3}\d{3}-\d{2}-[a-z0-9\-]+\.md$`
* **格式:** `RFC-[所属EPIC缩写+流水号]-[2位RFC序号]-[动宾短语小写连字符].md`
* **约束:** 每个 RFC 必须明确关联一个存在的 Epic。

### 2.3 Flyway 迁移脚本 (Database Migration)
* **Regex:** `^V\d+\.\d+\.\d+__create_[a-z_]+_table\.sql$` (或遵循项目特定的版本前缀如 V1__)
* **约束:** 由 `database-engineer` 生成。严禁在初始脚本中包含普通性能查询索引（idx_xxx），仅允许 PK 和 UK。

---

## 3. Git 提交权限与提议矩阵 (Git Commit Protocol)

**【最高红线】：** 任何 Agent 绝对禁止擅自在终端中执行 `git commit` 或 `git push` 命令。
所有 Agent 在完成本阶段任务后，只能在 Handoff (交接单) 中 **组装并提议 (Propose)** 一个符合 Conventional Commits 规范的 Git 命令，等待人类输入 `APPROVE` 后由控制台或人类执行。

* **`system-architect`:** `docs(design): define architecture behavior and contracts [RFC-XXX]`
* **`database-engineer`:** `style(db): add schema migration for [RFC-XXX]`
* **`implementation-agent`:** `feat(module): implement feature logic [RFC-XXX]`
* **`qa-agent`:** `test(integration): add destructive tests for [RFC-XXX]`
* **`governance-agent`:** `chore(docs): archive [RFC-XXX] and update global registries`

---

## 4. 全局核心资产指针 (Core Asset Pointers)

Agent 在执行关键决策前，必须读取以下实体文件：

1. **状态账本 (State Ledger):** `.agents/WORKSPACE_STATE.yaml`
   * *用途:* 确定当前在执行哪个任务、处在哪个阶段、白名单文件有哪些。
2. **API 全景 (API Catalog):** `docs/01_registries/api-catalog.yaml`
   * *用途:* 架构师 (`system-architect`) 必读。检查接口是否已存在。
3. **数据库全景 (Schema Summary):** `docs/01_registries/schema-summary.md`
   * *用途:* DBA (`database-engineer`) 必读。在输出 Flyway 脚本前比对现有表结构。

---

## 5. 物理交接铁律 (Handoff Protocol)

任何 Agent 在完成本阶段任务后，必须执行以下物理操作闭环：
1. **改状态:** 更新 `.agents/WORKSPACE_STATE.yaml` 中的 `current_stage` 与 `assigned_agent`。
2. **组装 Git 提议:** 准备好推荐的 `git commit` 命令串。
3. **呼叫人类:** 在终端输出结构化交接单（如 `SYSTEM_ARCHITECT_HANDOFF:`），明确要求人类敲入 `APPROVE`, `REJECT` 或 `SUSPEND`。
