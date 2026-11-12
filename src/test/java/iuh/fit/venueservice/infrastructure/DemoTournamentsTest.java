package iuh.fit.venueservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoTournaments có bản sao giống hệt ở venue-service và club-service: chốt lịch để hai bên giữ sân và dựng giải
 * trùng khớp. Test này cũng giống hệt ở hai nơi.
 */
class DemoTournamentsTest {

    @Test
    void scheduleIsStableAndFitsTheDailyWindow() {
        assertThat(DemoTournaments.ALL).hasSize(16);
        assertThat(DemoTournaments.ALL.get(0).id()).isEqualTo(UUID.fromString("d6d49277-3762-327d-bd6c-889f44bbcde9"));
        assertThat(DemoTournaments.ALL.stream().map(DemoTournaments.Tournament::name).distinct()).hasSize(16);
        DemoTournaments.Tournament open = DemoTournaments.ALL.get(7);
        assertThat(open.rounds()).isEqualTo(4);
        assertThat(open.end()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(DemoTournaments.ALL).allSatisfy(tournament -> {
            assertThat(tournament.end()).isBeforeOrEqualTo(DemoPopulation.COVER_UNTIL);
            if (tournament.knockout()) {
                assertThat(Integer.bitCount(tournament.teams())).as(tournament.name()).isEqualTo(1);
            } else {
                assertThat(tournament.teams() % 2).as(tournament.name()).isZero();
            }
            // Vòng đông trận nhất (vòng 1) vẫn đá xong trong khung giờ của ngày.
            int busiest = tournament.matchesInRound(1) - 1;
            assertThat(tournament.matchStart(busiest).plusMinutes(tournament.matchMinutes()))
                    .as(tournament.name()).isBeforeOrEqualTo(tournament.dailyEnd());
        });
    }
}
