# Path A Skeleton: 2_blueprint.md

**Feat ID:** `feat-TKT00X-XX-[name]`  
**Architect Date:** YYYY-MM-DD

---

## 行为设计

### 1. 流程交互

[Sequence diagram 或 textual flow]

### 2. 状态机

[Mermaid state diagram]

### 3. 关键设计决策

[表格：决策 | 理由]

---

## 存储设计

### 表结构

[列出新建表 / 扩展字段]

### 事务边界

[T1, T2, T3 etc. SQL pseudocode]

### 并发控制

[version CAS, UK constraints, etc.]

---

## API 契约概览

[表格：Method | Path | Idempotency | Return]

---

## 实现指南

1. **表创建** — Flyway migration
2. **Entity** — JPA entities
3. **Service** — Service layer with transaction boundaries
4. **Controller** — REST endpoints
5. **Tests** — Unit, Integration, Concurrency
