package com.microservices.order.notification;

import com.microservices.order.entity.Order;

import java.util.stream.Collectors;

/**
 * HTML email templates for transactional order emails (Brevo via Kafka).
 */
public final class OrderEmailTemplates {

    private static final String BRAND = "FoodChain";

    private static final String WRAPPER_START = """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:#f4f4f5;font-family:system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="padding:24px 12px;">
            <tr><td align="center">
            <table role="presentation" width="100%" style="max-width:560px;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.06);">
            <tr><td style="background:#111827;color:#fff;padding:20px 24px;font-size:18px;font-weight:600;">
            """;

    private static final String WRAPPER_AFTER_TITLE = """
            </td></tr>
            <tr><td style="padding:24px;color:#374151;font-size:15px;line-height:1.6;">
            """;

    private static String wrap(String inner) {
        String footer = "</td></tr>"
                + "<tr><td style=\"padding:16px 24px;background:#f9fafb;color:#9ca3af;font-size:12px;text-align:center;\">"
                + "You received this email because you placed an order at " + BRAND + ".</td></tr>"
                + "</table></td></tr></table></body></html>";
        return WRAPPER_START + BRAND + WRAPPER_AFTER_TITLE + inner + footer;
    }

    private OrderEmailTemplates() {}

    public static String orderPlacedOnline(Order order, String paymentUrl, String currencyLabel) {
        String itemsRows = order.getItems() == null ? ""
                : order.getItems().stream()
                .map(i -> "<tr><td style=\"padding:8px 0;border-bottom:1px solid #e5e7eb;\">"
                        + escape(i.getMenuItemName()) + " × " + i.getQuantity()
                        + "</td><td style=\"padding:8px 0;text-align:right;border-bottom:1px solid #e5e7eb;\">"
                        + (i.getSubtotal() != null ? i.getSubtotal().toPlainString() : "")
                        + "</td></tr>")
                .collect(Collectors.joining());

        String payBlock = (paymentUrl != null && !paymentUrl.isBlank())
                ? """
                <div style="margin:24px 0;text-align:center;">
                  <a href="%s" style="display:inline-block;background:#0d9488;color:#fff;text-decoration:none;padding:14px 28px;border-radius:8px;font-weight:600;">
                    Pay with Paystack
                  </a>
                </div>
                <p style="font-size:13px;color:#6b7280;">If the button does not work, copy this link into your browser:<br/>
                <span style="word-break:break-all;color:#2563eb;">%s</span></p>
                """.formatted(escapeAttr(paymentUrl), escape(paymentUrl))
                : "";

        String inner = """
                <p style="margin-top:0;">Hi %s,</p>
                <p>Thanks for your order — we’ve received it and it’s <strong>waiting for payment</strong>.</p>
                <p style="margin:8px 0;"><strong>Order ID:</strong> %s<br/>
                <strong>Branch:</strong> %s<br/>
                <strong>Type:</strong> %s<br/>
                <strong>Total:</strong> %s %s</p>
                <table role="presentation" width="100%" style="border-collapse:collapse;margin:16px 0;">%s</table>
                %s
                <p style="margin-bottom:0;">Complete payment to confirm your order and send it to the kitchen.</p>
                """.formatted(
                escape(order.getCustomerName() != null ? order.getCustomerName() : "there"),
                escape(order.getId()),
                escape(order.getBranchName() != null ? order.getBranchName() : order.getBranchId()),
                escape(order.getOrderType() != null ? order.getOrderType().name() : ""),
                escape(order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "0"),
                escape(currencyLabel),
                itemsRows,
                payBlock);

        return wrap(inner);
    }

    public static String orderPlacedOffline(Order order, String currencyLabel) {
        String itemsRows = order.getItems() == null ? ""
                : order.getItems().stream()
                .map(i -> "<tr><td style=\"padding:8px 0;border-bottom:1px solid #e5e7eb;\">"
                        + escape(i.getMenuItemName()) + " × " + i.getQuantity()
                        + "</td><td style=\"padding:8px 0;text-align:right;border-bottom:1px solid #e5e7eb;\">"
                        + (i.getSubtotal() != null ? i.getSubtotal().toPlainString() : "")
                        + "</td></tr>")
                .collect(Collectors.joining());

        String inner = """
                <p style="margin-top:0;">Hi %s,</p>
                <p>Your order has been <strong>placed successfully</strong> and sent to the restaurant.</p>
                <p style="margin:8px 0;"><strong>Order ID:</strong> %s<br/>
                <strong>Branch:</strong> %s<br/>
                <strong>Type:</strong> %s<br/>
                <strong>Total:</strong> %s %s</p>
                <table role="presentation" width="100%" style="border-collapse:collapse;margin:16px 0;">%s</table>
                <p style="margin-bottom:0;">We’ll keep you updated as your order progresses.</p>
                """.formatted(
                escape(order.getCustomerName() != null ? order.getCustomerName() : "there"),
                escape(order.getId()),
                escape(order.getBranchName() != null ? order.getBranchName() : order.getBranchId()),
                escape(order.getOrderType() != null ? order.getOrderType().name() : ""),
                escape(order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "0"),
                escape(currencyLabel),
                itemsRows);

        return wrap(inner);
    }

    public static String paymentSuccessful(Order order, String currencyLabel) {
        String inner = """
                <p style="margin-top:0;">Hi %s,</p>
                <p><strong>Payment received</strong> — your order <strong>%s</strong> is confirmed.</p>
                <p>Total paid: <strong>%s %s</strong></p>
                <p style="margin-bottom:0;">The kitchen has been notified and will start preparing your order. Thank you for choosing %s.</p>
                """.formatted(
                escape(order.getCustomerName() != null ? order.getCustomerName() : "there"),
                escape(order.getId()),
                escape(order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "0"),
                escape(currencyLabel),
                BRAND);

        return wrap(inner);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("'", "&#39;");
    }
}
