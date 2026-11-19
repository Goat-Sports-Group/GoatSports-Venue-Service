package iuh.fit.venueservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Quy tắc hủy của dữ liệu mô phỏng: luôn trước giờ chơi, hoàn 100% / 50% theo mốc 24 giờ, hủy gần đây thì chờ hoàn. */
class DemoPaymentSyncTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Test
    void cancellationAlwaysHappensBeforePlayAndRefundFollowsThe24HourRule() {
        for (int index = 0; index < 500; index++) {
            UUID bookingId = UUID.nameUUIDFromBytes(("booking-" + index).getBytes());
            LocalDateTime createdAt = NOW.minusDays(30).plusHours(index % 72);
            LocalDateTime playStartsAt = createdAt.plusHours(4 + index % 90);
            DemoPaymentSync.Cancellation cancellation =
                    DemoPaymentSync.cancellation(bookingId, 120_000, createdAt, playStartsAt, NOW);

            assertThat(cancellation).isNotNull();
            assertThat(cancellation.at()).isBefore(playStartsAt).isAfter(createdAt);
            boolean early = !cancellation.at().isAfter(playStartsAt.minusHours(24));
            assertThat(cancellation.amount()).isEqualTo(early ? 120_000 : 60_000);
            assertThat(cancellation.percentage()).isEqualTo(early ? 100 : 50);
            assertThat(cancellation.pending()).isFalse();
        }
    }

    @Test
    void recentCancellationWaitsForThePlayersBankAccountAndFutureOneIsSkipped() {
        UUID bookingId = UUID.nameUUIDFromBytes("recent".getBytes());
        DemoPaymentSync.Cancellation recent =
                DemoPaymentSync.cancellation(bookingId, 90_000, NOW.minusHours(40), NOW.plusDays(2), NOW);
        DemoPaymentSync.Cancellation future =
                DemoPaymentSync.cancellation(bookingId, 90_000, NOW.minusMinutes(5), NOW.plusDays(2), NOW);

        assertThat(recent).isNotNull();
        assertThat(recent.pending()).isTrue();
        assertThat(future).isNull();
    }
}
