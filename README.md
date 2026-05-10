# Order Service

Spring Boot microservice for order placement, lifecycle management, and status tracking in the FoodChain platform.

## Port and base URL

| Source | Port |
|--------|------|
| **`src/main/resources/application.yml`** | **8183** |
| **Spring Cloud Config** (`foodchain-config/order-service.yml`) / Docker stack | Often **8083** |

Context path: **`/api`**.

Examples:

- Direct (Maven with default yml): `http://localhost:8183/api`
- Config Server / Compose healthchecks often use: `http://localhost:8083/api`

**Via API Gateway:** `http://localhost:8080/api/v1/orders` (preferred for clients).

REST controllers live under **`/v1/orders`** on this service.

---

## Overview

- Accepts new orders from the frontend; resolves customer identity from the JWT header (`X-User-Id`) set by the API gateway — **customerId is never sent in the request body**.
- Supports `dine-in`, `takeaway`, and `delivery` order types (lowercase-hyphen from frontend; also accepts enum names).
- Publishes Kafka events via the transactional outbox pattern on order creation and every status change.
- Idempotent order creation via the `Idempotency-Key` header (cached in Redis for 1 minute).
- Optimistic locking (`@Version`) prevents concurrent status-update conflicts.

---

## Order Status Lifecycle

```
RECEIVED
   |--- CONFIRMED
   |        |--- PREPARING
   |                 |--- READY
   |                       |--- PICKED_UP   (terminal -- takeaway / delivery)
   |                       |--- SERVED      (terminal -- dine-in)
   |                       |--- COMPLETED   (terminal -- legacy, backward compat)
   |--- PREPARING  (kitchen accepts directly, skipping CONFIRMED)
   |--- CANCELLED  (terminal -- only from RECEIVED or CONFIRMED)
```

**Active statuses** (returned by `GET /v1/orders/active`): `RECEIVED`, `CONFIRMED`, `PREPARING`, `READY`

**History statuses** (returned by `GET /v1/orders/history`): `PICKED_UP`, `SERVED`, `COMPLETED`, `CANCELLED`

---

## orderType Casing Rules

| Frontend sends | Backend stores | Frontend receives |
|---|---|---|
| `"dine-in"` | `DINE_IN` | `"dine-in"` |
| `"takeaway"` | `TAKEAWAY` | `"takeaway"` |
| `"delivery"` | `DELIVERY` | `"delivery"` |

The service also accepts the backend enum names (`DINE_IN`, `TAKEAWAY`, `DELIVERY`) — useful for internal callers.

---

## How customerId is Extracted

The API gateway validates the JWT token and injects the authenticated user's ID as an `X-User-Id` HTTP header before forwarding the request to the order-service. The controller reads this header and passes it to the service. **Do not include `customerId` in the request body.**

---

## Endpoints

Paths below are **`/v1/orders/...`** relative to **`/api`** (full path on host: `/api/v1/orders/...`).

### POST /v1/orders — Place a New Order

**Required headers:**
- `X-User-Id: <customer-uuid>` — injected by API gateway; returns `401` if missing.
- `Content-Type: application/json`
- `Idempotency-Key: <client-uuid>` *(optional)* — safe retry support.

**Request body:**
```json
{
  "branchId": "branch-uuid",
  "branchName": "Main Branch",
  "orderType": "dine-in",
  "tableNumber": "5",
  "deliveryAddress": "123 Main St",
  "items": [
    {
      "menuItemId": "menu-item-uuid",
      "menuItemName": "Jollof Rice",
      "quantity": 2,
      "unitPrice": 10.00,
      "specialInstructions": "no pepper"
    }
  ],
  "customerName": "John",
  "phoneNumber": "555-1234",
  "paymentMethod": "card",
  "specialInstructions": "no onions",
  "notes": "birthday order"
}
```

**Optional fields:** `branchName`, `deliveryAddress`, `tableNumber`, `notes`, `specialInstructions`, `menuItemName` (defaults to `"Item <last-4-chars-of-id>"`), `unitPrice` (defaults to `0.00`).

**Required fields:** `branchId`, `orderType`, `items` (non-empty), `items[].menuItemId`, `items[].quantity`.

**Delivery fee:** `2.00` applied automatically for `delivery` orders; `0.00` for all others.

**Response `201 Created`:**
```json
{
  "id": "order-uuid",
  "status": "RECEIVED",
  "items": [
    { "id": "item-uuid", "name": "Jollof Rice", "price": 10.0, "quantity": 2 }
  ],
  "subtotal": 20.00,
  "deliveryFee": 0.00,
  "total": 20.00,
  "branchId": "branch-uuid",
  "branchName": "Main Branch",
  "orderType": "dine-in",
  "tableNumber": "5",
  "deliveryAddress": null,
  "customerName": "John",
  "phoneNumber": "555-1234",
  "specialInstructions": "no onions",
  "estimatedTime": "20-30 minutes",
  "placedAt": "2024-01-01T12:00:00Z",
  "paymentMethod": "card"
}
```

---

### GET /v1/orders/{orderId} — Get Order Details

Returns the same `FrontendOrderResponse` shape as the create endpoint.

**Response `200 OK`:** (same shape as create response above)

**Response `404 Not Found`:** Order not found.

---

### GET /v1/orders/active — List Active Orders

Returns a paginated list of orders currently in progress.

**Query params:**
- `customerId` *(optional)* — filter to a specific customer
- `branchId` *(optional)* — filter to a specific branch
- `page` *(default 0)*
- `size` *(default 10)*

**Response `200 OK`:**
```json
{
  "content": [
    {
      "orderId": "uuid",
      "customerId": "uuid",
      "branchId": "uuid",
      "orderType": "dine-in",
      "status": "PREPARING",
      "tableNumber": "5",
      "totalAmount": 25.00,
      "createdAt": "2024-01-01T12:00:00",
      "updatedAt": "2024-01-01T12:05:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1
}
```

---

### GET /v1/orders/history — Order History

Returns a paginated list of completed or cancelled orders (PICKED_UP, SERVED, COMPLETED, CANCELLED), sorted by most recent first.

**Query params:** same as `/v1/orders/active`.

---

### PUT /v1/orders/{orderId}/status — Update Order Status

**Request body:**
```json
{
  "newStatus": "CONFIRMED",
  "updatedBy": "staff-uuid",
  "notes": "Kitchen confirmed"
}
```

Valid `newStatus` values: `RECEIVED`, `CONFIRMED`, `PREPARING`, `READY`, `PICKED_UP`, `SERVED`, `COMPLETED`, `CANCELLED`.

Status value is case-insensitive (`confirmed` and `CONFIRMED` both work).

**Response `200 OK`:**
```json
{
  "orderId": "uuid",
  "status": "CONFIRMED",
  "totalAmount": 25.00,
  "createdAt": "2024-01-01T12:00:00"
}
```

**Response `400 Bad Request`:** Unknown status value or invalid status transition.

**Response `404 Not Found`:** Order not found.

**Response `409 Conflict`:** Concurrent update detected (optimistic locking).

---

### POST /v1/orders/{orderId}/cancel — Cancel an Order

Only cancellable from `RECEIVED` or `CONFIRMED` status.

**Request body:**
```json
{
  "cancelledBy": "cust-uuid",
  "reason": "Changed mind"
}
```

**Response `200 OK`:** Same slim `OrderResponse` shape as `updateOrderStatus`.

**Response `400 Bad Request`:** Order is not in a cancellable state.

**Response `404 Not Found`:** Order not found.

---

## Kafka Topics Published

| Topic | Trigger | Payload includes |
|---|---|---|
| `order.received` | New order created | Full order detail: items, customer info, delivery info, payment method |
| `order.status.updated` | Any status change | orderId, customerId, branchId, status, totalAmount, orderType |
| `order.ready` | Status transitions to `READY` | Same as `order.status.updated` |

All events are written to the `outbox_events` table first (transactional outbox pattern) and relayed to Kafka by the `OutboxRelay` scheduler.

---

## Fields: Required vs Optional

| Field | Required | Notes |
|---|---|---|
| `branchId` | Yes | |
| `orderType` | Yes | "dine-in", "takeaway", "delivery" |
| `items` | Yes | Non-empty list |
| `items[].menuItemId` | Yes | |
| `items[].quantity` | Yes | |
| `items[].menuItemName` | No | Defaults to "Item {last4}" if omitted |
| `items[].unitPrice` | No | Defaults to 0.00 if omitted |
| `items[].specialInstructions` | No | |
| `branchName` | No | Stored for display; not looked up |
| `deliveryAddress` | Conditional | Required for delivery orders |
| `tableNumber` | Conditional | Required for dine-in orders |
| `customerName` | No | Stored on order for display |
| `phoneNumber` | No | |
| `paymentMethod` | No | e.g. "card", "cash" |
| `specialInstructions` | No | Order-level instructions |
| `notes` | No | Internal kitchen notes |
