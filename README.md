# Order Service

Spring Boot microservice for order placement, lifecycle management, and status tracking in the FoodChain platform.

---

## Base URLs

| Environment | URL |
|---|---|
| Production (via gateway) | `http://54.235.78.18:8080/api/v1/orders` |
| Local (direct) | `http://localhost:8083/api/v1/orders` |

> **Always call through the gateway in production.** The gateway validates the JWT, strips the `Authorization` header, and injects `X-User-Id`, `X-User-Role`, and `X-User-Email` headers before forwarding to this service.

---

## Authentication

All order endpoints require a valid Bearer token in the `Authorization` header.

```
Authorization: Bearer <access_token>
```

The gateway extracts the user ID from the token and forwards it as `X-User-Id`. **Never send `customerId` in the request body** — it is always resolved from the token.

---

## Order Status Lifecycle

```
RECEIVED
  ├── CONFIRMED         (optional step — e.g. branch confirms order)
  │     └── PREPARING
  │           └── READY
  │                 ├── PICKED_UP   ← takeaway / delivery (terminal)
  │                 ├── SERVED      ← dine-in (terminal)
  │                 └── COMPLETED   ← legacy fallback (terminal)
  ├── PREPARING         (kitchen accepts directly, skipping CONFIRMED)
  └── CANCELLED         ← only from RECEIVED or CONFIRMED (terminal)
```

| Status group | Statuses | Endpoint |
|---|---|---|
| Active | `RECEIVED`, `CONFIRMED`, `PREPARING`, `READY` | `GET /orders/active` |
| History | `PICKED_UP`, `SERVED`, `COMPLETED`, `CANCELLED` | `GET /orders/history` |

---

## Order Type Values

Always send and receive in **lowercase-hyphen** format:

| Send | Meaning | Receive |
|---|---|---|
| `"dine-in"` | Table service | `"dine-in"` |
| `"takeaway"` | Counter pickup | `"takeaway"` |
| `"delivery"` | Home delivery (+£2.00 fee) | `"delivery"` |

---

## Error Response Format

All error responses share this shape:

```json
{
  "success": false,
  "status": 400,
  "error": "Bad Request",
  "message": "Human-readable description of what went wrong",
  "path": "/api/v1/orders",
  "timestamp": "2025-05-13T10:00:00.000Z"
}
```

| HTTP Status | When |
|---|---|
| `400` | Validation failed, invalid status value, illegal status transition |
| `401` | No JWT / missing `X-User-Id` header |
| `404` | Order not found |
| `409` | Concurrent update conflict (retry the request) |
| `500` | Unexpected server error |

---

## Endpoints

---

### `POST /orders` — Place a New Order

Creates a new order. The customer is identified from the JWT token — do not send `customerId`.

**Headers:**

| Header | Required | Description |
|---|---|---|
| `Authorization` | Yes | `Bearer <token>` |
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | No | Client-generated UUID. Send the same key on retry to prevent duplicate orders. |

**Request body:**

```json
{
  "branchId": "00e03993-6425-4703-a38f-cc661ceedf44",
  "branchName": "FoodChain Lekki",
  "orderType": "dine-in",
  "tableNumber": "5",
  "deliveryAddress": null,
  "customerName": "Amara Okafor",
  "customerEmail": "amara@example.com",
  "phoneNumber": "+234-801-000-0000",
  "paymentMethod": "card",
  "specialInstructions": "No onions on any item",
  "notes": "Birthday table — add a candle",
  "items": [
    {
      "menuItemId": "a1b2c3d4-0000-0000-0000-000000000001",
      "menuItemName": "Jollof Rice",
      "quantity": 2,
      "unitPrice": 12.50,
      "specialInstructions": "Extra spicy"
    },
    {
      "menuItemId": "a1b2c3d4-0000-0000-0000-000000000002",
      "menuItemName": "Chicken Suya",
      "quantity": 1,
      "unitPrice": 8.00,
      "specialInstructions": null
    }
  ]
}
```

**Field rules:**

| Field | Required | Notes |
|---|---|---|
| `branchId` | Yes | UUID of the branch |
| `orderType` | Yes | `"dine-in"`, `"takeaway"`, or `"delivery"` |
| `items` | Yes | Non-empty array |
| `items[].menuItemId` | Yes | UUID of the menu item |
| `items[].quantity` | Yes | Integer ≥ 1 |
| `items[].menuItemName` | No | Defaults to `"Item <last4ofId>"` if omitted |
| `items[].unitPrice` | No | Defaults to `0.00` if omitted |
| `items[].specialInstructions` | No | Per-item instruction |
| `branchName` | No | Stored for display only |
| `deliveryAddress` | Conditional | Required when `orderType` is `"delivery"` |
| `tableNumber` | Conditional | Recommended when `orderType` is `"dine-in"` |
| `customerName` | No | Shown in kitchen and receipts |
| `customerEmail` | No | Shown in receipts |
| `phoneNumber` | No | Shown for delivery orders |
| `paymentMethod` | No | Free text e.g. `"card"`, `"cash"` |
| `specialInstructions` | No | Order-level instructions |
| `notes` | No | Internal kitchen notes |

**Delivery fee:** `2.00` added automatically for `"delivery"` orders. All other types get `0.00`.

**Response `201 Created`:**

```json
{
  "id": "49bffe49-f62b-492b-92d8-2e784c76ded7",
  "status": "RECEIVED",
  "items": [
    {
      "id": "item-uuid-1",
      "name": "Jollof Rice",
      "price": 12.5,
      "quantity": 2
    },
    {
      "id": "item-uuid-2",
      "name": "Chicken Suya",
      "price": 8.0,
      "quantity": 1
    }
  ],
  "subtotal": 33.00,
  "deliveryFee": 0.00,
  "total": 33.00,
  "branchId": "00e03993-6425-4703-a38f-cc661ceedf44",
  "branchName": "FoodChain Lekki",
  "orderType": "dine-in",
  "tableNumber": "5",
  "deliveryAddress": null,
  "customerName": "Amara Okafor",
  "customerEmail": "amara@example.com",
  "phoneNumber": "+234-801-000-0000",
  "specialInstructions": "No onions on any item",
  "estimatedTime": "20-30 minutes",
  "placedAt": "2025-05-13T10:28:00Z",
  "paymentMethod": "card"
}
```

---

### `GET /orders/{orderId}` — Get a Single Order

Returns full details for one order. Same response shape as the create endpoint.

**Path param:** `orderId` — UUID of the order.

**Response `200 OK`:** same shape as `POST /orders` response above.

**Response `404`:** Order not found.

---

### `GET /orders/active` — List Active Orders

Returns a paginated list of orders currently in progress (`RECEIVED`, `CONFIRMED`, `PREPARING`, `READY`), sorted most-recent first.

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `customerId` | UUID string | — | Filter to one customer's orders |
| `branchId` | UUID string | — | Filter to one branch's orders |
| `page` | integer | `0` | Zero-based page number |
| `size` | integer | `10` | Orders per page |

**Example:** `GET /orders/active?branchId=00e03993-...&page=0&size=20`

**Response `200 OK`:**

```json
{
  "content": [
    {
      "orderId": "49bffe49-f62b-492b-92d8-2e784c76ded7",
      "customerId": "cust-uuid",
      "branchId": "00e03993-6425-4703-a38f-cc661ceedf44",
      "orderType": "dine-in",
      "status": "PREPARING",
      "tableNumber": "5",
      "totalAmount": 33.00,
      "createdAt": "2025-05-13T10:28:00",
      "updatedAt": "2025-05-13T10:30:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 10
}
```

**Note:** This list response is a summary shape. Use `GET /orders/{orderId}` for the full detail including items.

---

### `GET /orders/history` — Order History

Returns a paginated list of completed or cancelled orders (`PICKED_UP`, `SERVED`, `COMPLETED`, `CANCELLED`), sorted most-recent first.

**Query params:** same as `/orders/active`.

**Response `200 OK`:** same pagination shape as `/orders/active`.

---

### `PUT /orders/{orderId}/status` — Update Order Status

Transitions an order to a new status. Follows the lifecycle rules above — invalid transitions return `400`.

> This endpoint is primarily called by the **kitchen-service** internally. Frontends typically trigger status changes through the kitchen endpoints instead.

**Request body:**

```json
{
  "newStatus": "CONFIRMED",
  "updatedBy": "staff-uuid-or-name",
  "notes": "Confirmed by branch manager"
}
```

| Field | Required | Notes |
|---|---|---|
| `newStatus` | Yes | See valid values below |
| `updatedBy` | No | Who triggered the change (for audit trail) |
| `notes` | No | Optional note stored on the status update |

**Valid `newStatus` values:**

| Value | From status | Meaning |
|---|---|---|
| `"CONFIRMED"` | `RECEIVED` | Branch confirmed the order |
| `"PREPARING"` | `RECEIVED` or `CONFIRMED` | Kitchen started cooking |
| `"READY"` | `PREPARING` | Food is ready |
| `"PICKED_UP"` | `READY` | Takeaway/delivery collected |
| `"SERVED"` | `READY` | Dine-in order served at table |
| `"COMPLETED"` | `READY` | Legacy completion |
| `"CANCELLED"` | `RECEIVED` or `CONFIRMED` | Order cancelled |

Values are case-insensitive.

**Response `200 OK`:**

```json
{
  "orderId": "49bffe49-f62b-492b-92d8-2e784c76ded7",
  "status": "CONFIRMED",
  "totalAmount": 33.00,
  "createdAt": "2025-05-13T10:28:00"
}
```

**Response `400`:** Unknown status value or illegal transition. The error `message` will say which transitions are valid from the current status.

**Response `404`:** Order not found.

**Response `409`:** Concurrent update conflict — another request updated the order at the same time. Retry the request.

---

### `POST /orders/{orderId}/cancel` — Cancel an Order

Cancels an order. Only works when the order is in `RECEIVED` or `CONFIRMED` status.

**Request body:**

```json
{
  "cancelledBy": "customer-uuid-or-name",
  "reason": "Changed my mind"
}
```

Both fields are optional but recommended for the audit trail.

**Response `200 OK`:**

```json
{
  "orderId": "49bffe49-f62b-492b-92d8-2e784c76ded7",
  "status": "CANCELLED",
  "totalAmount": 33.00,
  "createdAt": "2025-05-13T10:28:00"
}
```

**Response `400`:** Order is already being prepared and cannot be cancelled.

**Response `404`:** Order not found.

---

## Common Frontend Flows

### Customer places an order

```
POST /orders
  → 201: store the returned `id` as the active order ID
  → 409: duplicate key — you already placed this order (show existing order)
  → 400: check `message` for which field is missing/invalid
```

### Customer tracks their order

```
GET /orders/active?customerId=<userId>
  → shows all in-progress orders for this customer
GET /orders/{orderId}
  → full detail including items for a single order
GET /orders/history?customerId=<userId>
  → shows completed/cancelled orders
```

### Customer cancels before kitchen starts

```
POST /orders/{orderId}/cancel   body: { "cancelledBy": "<userId>", "reason": "..." }
  → 200: order is now CANCELLED
  → 400: "Order is in a state that cannot be cancelled" — already PREPARING or later
```

### Showing order status as a human-readable label

```js
const STATUS_LABELS = {
  RECEIVED:   'Order Received',
  CONFIRMED:  'Order Confirmed',
  PREPARING:  'Being Prepared',
  READY:      'Ready for Pickup / Service',
  PICKED_UP:  'Picked Up',
  SERVED:     'Served',
  COMPLETED:  'Completed',
  CANCELLED:  'Cancelled',
};
```

---

## Kafka Events Published

These are published automatically — the frontend does not trigger them directly.

| Topic | When | Key payload fields |
|---|---|---|
| `order.received` | New order created | `orderId`, `customerId`, `branchId`, `orderType`, `status`, `totalAmount`, `items[]`, `tableNumber`, `deliveryAddress`, `customerName`, `customerEmail`, `phoneNumber`, `paymentMethod` |
| `order.status.updated` | Any status transition | `orderId`, `customerId`, `branchId`, `status`, `totalAmount`, `orderType`, `previousStatus`, `newStatus`, `updatedBy`, `notes` |
| `order.ready` | Status → `READY` | Same as `order.status.updated` |

All events are written to the `outbox_events` table first and published to Kafka by the `OutboxRelay` scheduler (transactional outbox pattern — guarantees delivery even if Kafka is temporarily unavailable).

---

## Idempotent Order Creation

To safely retry a failed order request without creating duplicates:

1. Generate a UUID on the client before calling `POST /orders`.
2. Send it as the `Idempotency-Key` header.
3. If the network fails and you retry, send the **same UUID** in `Idempotency-Key`.
4. The service returns the original response instead of creating a second order.

The cache expires after **1 minute** — retries beyond that window may create a new order.
