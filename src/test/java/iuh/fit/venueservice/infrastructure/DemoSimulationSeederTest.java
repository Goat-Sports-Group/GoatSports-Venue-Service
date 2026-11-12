package iuh.fit.venueservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSimulationSeederTest {

    @Test
    void everyCityOffersTheCommonSportsWithUniqueIds() {
        List<DemoSimulationSeeder.Venue> venues = DemoSimulationSeeder.venues();
        assertThat(venues).hasSize(72);
        // Đợt đầu (36 cơ sở) giữ nguyên chủ cũ; đợt mở rộng thuộc 8 chủ sân mới.
        assertThat(venues.subList(0, 36)).allMatch(venue -> venue.owner().index() < DemoPopulation.FIRST_OWNERS);
        assertThat(venues.subList(36, 72)).allMatch(venue -> venue.owner().index() >= DemoPopulation.FIRST_OWNERS);
        assertThat(venues.stream().map(DemoSimulationSeeder.Venue::id).distinct()).hasSize(venues.size());
        // Id phải giống nhau giữa các lần chạy JVM (thứ tự lặp của Map.of thì không), nếu không mỗi lần khởi động
        // lại sinh thêm một bộ cơ sở mới.
        assertThat(venues).anyMatch(venue -> venue.name().equals("Cầu Lông Bình Minh")
                && venue.id().toString().equals("32a1ec2a-a903-3598-acdb-ec31d1f79669"));
        for (DemoPopulation.City city : DemoPopulation.CITIES) {
            List<DemoSimulationSeeder.Venue> local = venues.stream().filter(venue -> venue.city() == city).toList();
            assertThat(local).as(city.name()).anyMatch(venue -> venue.offers("FOOTBALL"));
            assertThat(local).as(city.name()).anyMatch(venue -> venue.offers("BADMINTON"));
            // Chủ sân chỉ quản lý cơ sở ở thành phố của mình.
            assertThat(local).allMatch(venue -> venue.owner().city() == city);
        }
        assertThat(venues).allSatisfy(venue -> assertThat(venue.courts())
                .allMatch(court -> court.price() >= 50_000 && court.price() % 10_000 == 0));
    }

    @Test
    void bookingDemandGrowsOverTimeAndPeaksOnWeekends() {
        DemoPopulation.Player player = DemoPopulation.player(0);
        LocalDate today = LocalDate.of(2026, 10, 16);
        LocalDate first = today.minusDays(DemoPopulation.HISTORY_DAYS);
        LocalDate monday = LocalDate.of(2026, 10, 12);
        double early = DemoSimulationSeeder.dailyProbability(player, first, first, today);
        double late = DemoSimulationSeeder.dailyProbability(player, monday, first, today);
        double weekend = DemoSimulationSeeder.dailyProbability(player, monday.plusDays(5), first, today);
        assertThat(early).isLessThan(late);
        assertThat(weekend).isGreaterThan(late).isLessThanOrEqualTo(0.9);
    }

    @Test
    void tournamentsUseCourtsOfTheDemoVenues() {
        assertThat(DemoTournaments.ALL).allSatisfy(tournament -> {
            DemoDataSeeder.VenueSeed venue = DemoDataSeeder.VENUES.stream()
                    .filter(seed -> seed.name().equals(tournament.venue().name())).findFirst().orElseThrow();
            assertThat(venue.ownerEmail()).isEqualTo(tournament.venue().ownerEmail());
            assertThat(venue.city()).isEqualTo(tournament.venue().city());
            assertThat(venue.courts()).extracting(DemoDataSeeder.CourtSeed::name).containsAll(tournament.courts());
            assertThat(venue.courts()).filteredOn(court -> tournament.courts().contains(court.name()))
                    .allMatch(court -> court.sportType().name().equals(tournament.sport()));
        });
    }

    @Test
    void futureDaysFillUpAsTheyGetCloser() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertThat(DemoSimulationSeeder.advance(today.minusDays(3), today)).isEqualTo(1);
        assertThat(DemoSimulationSeeder.advance(today.plusDays(2), today))
                .isGreaterThan(DemoSimulationSeeder.advance(today.plusDays(10), today));
        assertThat(DemoSimulationSeeder.advance(DemoPopulation.COVER_UNTIL, today)).isEqualTo(0.12);
        // Sau hôm nay không còn tăng trưởng thêm.
        LocalDate first = today.minusDays(DemoPopulation.HISTORY_DAYS);
        assertThat(DemoSimulationSeeder.growth(today.plusDays(30), first, today)).isEqualTo(1);
        assertThat(DemoSimulationSeeder.growth(first, first, today)).isEqualTo(0.55);
    }

    @Test
    void aSmallShareOfReviewsIsAbusiveForTheAiFilter() {
        long abusive = IntStream.range(0, 10_000)
                .mapToObj(i -> UUID.nameUUIDFromBytes(("goatsports:sim-booking:" + i).getBytes(StandardCharsets.UTF_8)))
                .filter(bookingId -> DemoSimulationSeeder.abusiveText(bookingId) != null)
                .count();
        assertThat(abusive).isBetween(200L, 400L);
    }
}
