package iuh.fit.venueservice.infrastructure.external.client;

import iuh.fit.venueservice.infrastructure.external.config.PaymentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Đẩy lượt đặt sân mô phỏng sang payment-service để dựng dòng tiền demo ({@code DemoPaymentSync}). Endpoint chỉ có
 * khi payment-service bật {@code app.demo-data.simulation.enabled}.
 */
@FeignClient(
        name = "GOATSPORTS-PAYMENT-SERVICE",
        contextId = "demoPaymentClient",
        url = "${payment.service.url}",
        path = "/internal/demo",
        configuration = PaymentFeignConfiguration.class
)
public interface DemoPaymentClient {

    @PostMapping("/booking-payments")
    Map<String, Object> bookingPayments(@RequestBody List<BookingPayment> rows);

    /** Khớp {@code DemoPaymentSeeder.BookingPayment} ở payment-service. */
    record BookingPayment(UUID bookingId, UUID playerId, UUID ownerId, String ownerAccountName,
                          double depositAmount, double remainingAmount, String status,
                          LocalDateTime createdAt, LocalDateTime playStartsAt, LocalDateTime checkedInAt,
                          LocalDateTime cancelledAt, double refundAmount, String refundReason) { }
}
