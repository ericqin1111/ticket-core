# API Contract — Catalog

**Maintained by:** librarian
**Last updated by:** migration (from RFC-TKT001-01 / `api-catalog.yaml`)
**Owning aggregate:** `CatalogItem`（参见 `domain_models.md §2.1`）

---

## GET /catalog-items/{catalogItemId}/sellable-availability

**Introduced by:** `feat-TKT001-01`
**Purpose:** 查询商品当前可售快照，供锁票前同步校验。
**Idempotency:** 幂等查询，无 `Idempotency-Key` 要求。

### Path Parameters

| Name | Type | Required | Description |
|:--|:--|:--|:--|
| `catalogItemId` | string | yes | `CatalogItem.catalog_item_id` |

### Response 200 — `sellable-availability-response`

```json
{
  "$id": "sellable-availability-response",
  "type": "object",
  "required": [
    "catalog_item_id",
    "inventory_resource_id",
    "sellable_quantity",
    "reserved_quantity",
    "status",
    "checked_at"
  ],
  "properties": {
    "catalog_item_id":       { "type": "string" },
    "inventory_resource_id": { "type": "string" },
    "sellable_quantity":     { "type": "integer", "minimum": 0 },
    "reserved_quantity":     { "type": "integer", "minimum": 0 },
    "status":                { "type": "string", "enum": ["SELLABLE", "LOW_STOCK", "SOLD_OUT", "OFF_SHELF"] },
    "checked_at":            { "type": "string", "format": "date-time" }
  }
}
```

**Field notes**

* `sellable_quantity = InventoryResource.total_quantity - InventoryResource.reserved_quantity`（应用层投影，不落库）。
* `status` 为应用层派生快照：
  * `SELLABLE` — `catalog_item.status = ACTIVE` 且 `sellable_quantity > 阈值`。
  * `LOW_STOCK` — `sellable_quantity > 0` 且低于阈值。
  * `SOLD_OUT` — `sellable_quantity = 0`。
  * `OFF_SHELF` — `catalog_item.status != ACTIVE`。
* `checked_at` 为服务端读快照时间，客户端不得据此假设之后的可售性。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 422 | `CATALOG_ITEM_NOT_SELLABLE` | `catalog_item_id` 不存在或 `status != ACTIVE` |

详见 `_shared_error_codes.md`。
