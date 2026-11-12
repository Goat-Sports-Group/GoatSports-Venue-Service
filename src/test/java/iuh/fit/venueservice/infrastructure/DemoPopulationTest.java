package iuh.fit.venueservice.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoPopulation có bản sao giống hệt ở auth-service, venue-service, social-service và club-service (ai-service có bản
 * Python): chốt vài giá trị để bản nào lệch (khác người, khác id) thì test của service đó đỏ ngay. Test này cũng giống
 * hệt ở cả bốn nơi; {@code tests/test_demo_population.py} của ai-service chốt cùng các giá trị.
 */
class DemoPopulationTest {

    @Test
    void generatesTheSamePopulationInEveryService() {
        List<DemoPopulation.Player> players = DemoPopulation.players();
        assertThat(players).hasSize(DemoPopulation.PLAYERS);
        assertThat(players.get(0).fullName()).isEqualTo("Lý Hoàng Tuấn");
        assertThat(players.get(0).city().name()).isEqualTo("Khánh Hòa");
        assertThat(players.get(0).sports()).containsExactly("BADMINTON");
        assertThat(players.get(0).userId()).isEqualTo(UUID.fromString("427feff9-522c-333c-ab96-6add5aba4394"));
        assertThat(players.get(119).fullName()).isEqualTo("Phạm Hoàng Quang");
        // 120 người của bản đầu không đổi khi tăng quy mô (sinh theo chỉ số).
        assertThat(players.subList(0, 120).stream().mapToInt(DemoPopulation.Player::elo).sum()).isEqualTo(162878);
        assertThat(players.get(299).fullName()).isEqualTo("Phan Bảo Huy");
        assertThat(players.get(299).sports()).containsExactly("BADMINTON", "BASKETBALL");
        assertThat(players.stream().mapToInt(DemoPopulation.Player::elo).sum()).isEqualTo(401997);
        assertThat(DemoPopulation.owners().get(6).userId())
                .isEqualTo(UUID.fromString("3a9f0569-9538-3bf2-b6a4-792ed8211aab"));
        assertThat(DemoPopulation.owners().get(15).userId())
                .isEqualTo(UUID.fromString("e56e4e7a-68ae-3acf-acde-0236225b7ccc"));
    }

    @Test
    void everyPlayerHasAWeekendSlotAndUniqueAccount() {
        List<DemoPopulation.Player> players = DemoPopulation.players();
        assertThat(players).allSatisfy(player -> assertThat(player.availability())
                .anyMatch(slot -> slot.day().getValue() >= 6));
        assertThat(players.stream().map(DemoPopulation.Player::email).distinct()).hasSize(players.size());
        assertThat(players.stream().map(DemoPopulation.Player::username).distinct()).hasSize(players.size());
    }
}
