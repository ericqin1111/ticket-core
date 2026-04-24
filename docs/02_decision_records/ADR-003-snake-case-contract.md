# ADR-003: snake_case Contract Format (HTTP Boundary)

**Status:** Accepted  
**Context:** RFC-TKT001-01 / RFC-TKT001-02  
**Date:** 2026-04-23

## Problem

API 契约字段命名风格决定了：
- 客户端集成成本（编程语言 convention 适配）
- 渠道网关 payload 标准化复杂度
- 内部 Java 代码中的 snake_case ↔ camelCase 转换开销

## Decision

**HTTP 边界（JSON）统一采用 `snake_case`：**
- Request/Response JSON：`external_trade_no`, `catalog_item_id`, `buyer_ref`, `payment_deadline_at` 等
- Java 内部：保持 camelCase（`externalTradeNo`, `catalogItemId`），由框架（MyBatis、Jackson）自动转换

示例：

```json
{
  "external_trade_no": "...",
  "catalog_item_id": "...",
  "reserved_quantity": 10,
  "payment_deadline_at": "2026-04-25T10:30:00Z"
}
```

## Rationale

1. **渠道对齐：** 支付渠道、运营系统多数采用 snake_case；内部转换成本低。
2. **Java 生态兼容：** Jackson `PropertyNamingStrategy.SNAKE_CASE` 一行配置即可；MyBatis 同理。
3. **可读性：** snake_case 在多语言文档中通用，客户端无需猜测。

## Consequences

### Positive
- 跨渠道集成成本低。
- 文档即代码，无需专门的命名转换说明。

### Negative
- Java 代码中需显式配置命名转换（Jackson @JsonProperty 或 mapper 配置）。

## Related Files
- `api_contracts/_shared_error_codes.md`（error-response 字段命名）
- `api_contracts/catalog.md`（sellable-availability-response）
- `api_contracts/reservation.md`（create-reservation-request/response）
- 后续 Java 实现需 Jackson PropertyNamingStrategy 配置
