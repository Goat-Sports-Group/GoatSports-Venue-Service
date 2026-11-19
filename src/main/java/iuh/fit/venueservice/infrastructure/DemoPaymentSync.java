package iuh.fit.venueservice.infrastructure;

import iuh.fit.venueservice.infrastructure.external.client.DemoPaymentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Dòng tiền của dữ liệu mô phỏng: lấy các lượt đặt sân qua app (có cọc) và đẩy sang payment-service, để khoản cọc,
 * tiền còn lại thu tại quầy, hoàn tiền, số dư và lịch sử rút tiền của chủ sân khớp với lịch đặt sân.
 *
 * <p>Lượt bị hủy được chốt như luồng hủy thật: hủy trước giờ chơi từ 24 giờ hoàn 100% tiền cọc, trễ hơn hoàn 50%; ghi
 * {@code booking_cancellations} (tự duyệt) và đổi booking sang {@code REFUND_PENDING} khi tiền hoàn đang chờ người chơi
 * thêm tài khoản (hủy trong 2 ngày gần đây) hoặc {@code REFUNDED} khi đã hoàn. Thời điểm hủy và số tiền hoàn tính một
 * lần ở đây rồi gửi sang payment-service, nên hai bên luôn cùng con số.
 *
 * <p>Lần đầu thử mỗi 30 phút (payment-service có thể khởi động sau) tới khi thành công, sau đó mỗi đêm sau seeder mô
 * phỏng (00:20). Id tất định, mọi lệnh ghi bỏ qua dòng đã có: chạy lại bao nhiêu lần cũng được.
 * Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoPaymentSync {
    private static final int BATCH = 2000;
    private static final int PENDING_REFUND_DAYS = 2;
    private static final Set<String> CANCELLED = Set.of("CANCELLED", "REFUND_PENDING", "REFUNDED");

    private final JdbcTemplate jdbc;
    private final DemoPaymentClient client;
    private volatile boolean synced;

    @Scheduled(initialDelay = 4, fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    public void syncUntilDone() {
        if (!synced) sync();
    }

    @Scheduled(cron = "0 50 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void nightly() {
        sync();
    }

    void sync() {
        LocalDateTime now = LocalDateTime.now();
        Map<UUID, String> ownerNames = new HashMap<>();
        DemoPopulation.owners().forEach(owner -> ownerNames.put(owner.userId(), owner.fullName()));
        List<Object[]> cancellations = new ArrayList<>();
        List<Object[]> statuses = new ArrayList<>();
        List<DemoPaymentClient.BookingPayment> rows = jdbc.query("""
                select b.booking_id, b.player_id, v.owner_id, v.name, b.deposit_amount, b.remaining_amount, b.status,
                       b.created_at, b.play_date + b.start_time, ci.checked_in_at
                from bookings b
                join venue_courts c on c.venue_court_id = b.venue_court_id
                join venues v on v.venue_id = c.venue_id
                left join check_in_records ci on ci.booking_id = b.booking_id
                where b.created_by in ('demo-sim', 'demo-seed') and b.player_id is not null and b.deposit_amount > 0
                """, (rs, index) -> {
            UUID bookingId = rs.getObject(1, UUID.class);
            UUID playerId = rs.getObject(2, UUID.class);
            UUID ownerId = rs.getObject(3, UUID.class);
            double deposit = rs.getDouble(5);
            String status = rs.getString(7);
            LocalDateTime createdAt = rs.getTimestamp(8).toLocalDateTime();
            LocalDateTime playStartsAt = rs.getTimestamp(9).toLocalDateTime();
            Timestamp checkedIn = rs.getTimestamp(10);
            Cancellation cancellation = CANCELLED.contains(status) ? cancellation(bookingId, deposit, createdAt, playStartsAt, now) : null;
            if (cancellation != null) {
                cancellations.add(new Object[]{id("booking-cancellation", bookingId.toString()), bookingId, playerId,
                        id("refund", bookingId.toString()), cancellation.reason(), cancellation.percentage(),
                        cancellation.amount(), cancellation.pending() ? "REFUND_AWAITING_BANK_ACCOUNT" : "REFUNDED",
                        cancellation.pending() ? "Người chơi chưa liên kết tài khoản nhận tiền hoàn" : null,
                        Timestamp.valueOf(cancellation.at().plusMinutes(5)), Timestamp.valueOf(cancellation.at()),
                        Timestamp.valueOf(cancellation.at())});
                statuses.add(new Object[]{cancellation.pending() ? "REFUND_PENDING" : "REFUNDED", bookingId});
            }
            return new DemoPaymentClient.BookingPayment(bookingId, playerId, ownerId,
                    ownerNames.getOrDefault(ownerId, "HKD " + rs.getString(4)), deposit, rs.getDouble(6), status,
                    createdAt, playStartsAt, checkedIn == null ? null : checkedIn.toLocalDateTime(),
                    cancellation == null ? null : cancellation.at(), cancellation == null ? 0 : cancellation.amount(),
                    cancellation == null ? null : cancellation.reason());
        });
        recordCancellations(cancellations, statuses, now);
        try {
            int payments = 0;
            for (int from = 0; from < rows.size(); from += BATCH) {
                Map<String, Object> result = client.bookingPayments(rows.subList(from, Math.min(rows.size(), from + BATCH)));
                Object inserted = result == null ? null : result.get("payments");
                if (inserted instanceof Number number) payments += number.intValue();
            }
            synced = true;
            log.info("Đã đẩy {} lượt đặt mô phỏng sang payment-service ({} khoản thu mới, {} lượt hủy)",
                    rows.size(), payments, cancellations.size());
        } catch (RuntimeException exception) {
            log.warn("Chưa đẩy được lượt đặt mô phỏng sang payment-service (thử lại sau): {}", exception.getMessage());
        }
    }

    record Cancellation(LocalDateTime at, double percentage, double amount, String reason, boolean pending) { }

    /**
     * Thời điểm hủy: vài giờ tới hơn một ngày sau khi đặt, luôn trước giờ chơi ít nhất 1 giờ. Hủy trước giờ chơi từ 24 giờ
     * hoàn 100% tiền cọc, trễ hơn hoàn 50%. Hủy trong 2 ngày gần đây: tiền hoàn còn chờ người chơi thêm tài khoản.
     */
    static Cancellation cancellation(UUID bookingId, double deposit, LocalDateTime createdAt, LocalDateTime playStartsAt,
                                     LocalDateTime now) {
        long salt = bookingId.getLeastSignificantBits();
        LocalDateTime at = createdAt.plusMinutes(15).plusHours(2 + Math.floorMod(salt >> 8, 30));
        if (!at.isBefore(playStartsAt.minusHours(1))) at = playStartsAt.minusHours(1);
        if (at.isBefore(createdAt.plusMinutes(30))) at = createdAt.plusMinutes(30);
        if (at.isAfter(now) || deposit <= 0) return null;
        boolean early = !at.isAfter(playStartsAt.minusHours(24));
        double percentage = early ? 100 : 50;
        double amount = early ? deposit : Math.round(deposit * 0.5 / 1000d) * 1000d;
        String reason = early ? "Hủy trước giờ chơi từ 24 giờ: hoàn 100% tiền cọc"
                : "Hủy trong vòng 24 giờ trước giờ chơi: hoàn 50% tiền cọc";
        return new Cancellation(at, percentage, amount, reason, at.isAfter(now.minusDays(PENDING_REFUND_DAYS)));
    }

    private void recordCancellations(List<Object[]> cancellations, List<Object[]> statuses, LocalDateTime now) {
        jdbc.batchUpdate("""
                insert into booking_cancellations (cancellation_id, booking_id, requested_by_user_id, refund_id, reason,
                                                   refund_percentage, refund_amount, status, review_mode,
                                                   refund_failure_reason, processed_at, created_at, updated_at,
                                                   created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'AUTOMATIC', ?, ?, ?, ?, 'demo-sim', 'demo-sim')
                on conflict do nothing
                """, cancellations);
        // Tiền hoàn đã chờ quá 2 ngày coi như người chơi đã thêm tài khoản và nhận tiền (khớp payment-service).
        jdbc.update("""
                update booking_cancellations set status = 'REFUNDED', refund_failure_reason = null, updated_at = now(),
                       updated_by = 'demo-sim'
                where created_by = 'demo-sim' and status = 'REFUND_AWAITING_BANK_ACCOUNT' and created_at < ?
                """, Timestamp.valueOf(now.minusDays(PENDING_REFUND_DAYS)));
        jdbc.batchUpdate("""
                update bookings set status = ?, updated_at = now(), updated_by = 'demo-sim'
                where booking_id = ? and status in ('CANCELLED', 'REFUND_PENDING')
                """, statuses);
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
