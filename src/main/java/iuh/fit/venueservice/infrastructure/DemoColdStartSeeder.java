package iuh.fit.venueservice.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nhu cầu với sân ngoài hệ thống (dev / demo) cho màn "Gợi ý thêm sân" và "Sân ngoài hệ thống":
 * <ul>
 *   <li>đề xuất thêm sân của người chơi mô phỏng: 4 đang chờ admin duyệt, 2 đã bị từ chối (trùng / không phải sân);</li>
 *   <li>lượt "muốn sân này lên GOAT Sports" cho các sân OSM đã được admin nhập (nếu đã nhập), để danh sách sân tiềm năng
 *       có số người quan tâm.</li>
 * </ul>
 * Không tạo sân OSM (admin nhập theo khu vực) và không tạo hồ sơ chủ sân chờ duyệt (cần giấy tờ thật). Id tất định,
 * bỏ qua dòng đã có. Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(22)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoColdStartSeeder implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    record Suggestion(int player, String name, String sports, double latitude, double longitude, String address,
                      String phone, String note, String status, String rejectReason, int daysAgo) { }

    static final List<Suggestion> SUGGESTIONS = List.of(
            new Suggestion(14, "Sân Bóng Đá Mini Cây Gõ", "FOOTBALL", 10.7612, 106.6431,
                    "Đường Minh Phụng, Quận 6, TP. Hồ Chí Minh", "0909 456 112",
                    "Sân cỏ nhân tạo 3 sân 5 người, tối nào cũng kín, mong sân lên app để đặt online.", "PENDING", null, 1),
            new Suggestion(37, "Nhà Thi Đấu Phú Thọ - Sân Cầu Lông", "BADMINTON", 10.7681, 106.6576,
                    "Lữ Gia, Quận 11, TP. Hồ Chí Minh", null,
                    "Có 8 sân cầu lông thảm, đặt qua điện thoại hay bị trùng giờ.", "PENDING", null, 2),
            new Suggestion(88, "Sân Pickleball Thảo Điền", "PICKLEBALL", 10.8027, 106.7357,
                    "Đường Quốc Hương, Thảo Điền, TP. Thủ Đức", "0938 220 415",
                    "Sân mới mở 4 sân, chưa có trên app nào.", "PENDING", null, 3),
            new Suggestion(162, "Sân Tennis Hồ Tây", "TENNIS", 21.0587, 105.8224,
                    "Đường Thanh Niên, Tây Hồ, Hà Nội", null,
                    "Hai sân mặt cứng, chủ sân dễ thương, nên mời lên GOAT.", "PENDING", null, 4),
            new Suggestion(203, "Sân Bóng Rổ Công Viên Lê Văn Tám", "BASKETBALL", 10.7889, 106.6926,
                    "Công viên Lê Văn Tám, Quận 1, TP. Hồ Chí Minh", null,
                    "Sân công cộng, không cần đặt.", "REJECTED", "Sân công cộng miễn phí, không nhận đặt lịch.", 15),
            new Suggestion(241, "Sân Bóng Đá Mini Kỳ Hòa", "FOOTBALL", 10.7745, 106.6702,
                    "Đường 3 Tháng 2, Quận 10, TP. Hồ Chí Minh", null,
                    "Sân bóng gần nhà mình.", "REJECTED", "Sân đã có trên GOAT Sports.", 20));

    @Override
    @Transactional
    public void run(String... args) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        List<DemoPopulation.Player> players = DemoPopulation.players();
        List<Object[]> suggestions = new ArrayList<>();
        for (Suggestion suggestion : SUGGESTIONS) {
            UUID suggestionId = id("sim-venue-suggestion", suggestion.name());
            LocalDateTime createdAt = now.minusDays(suggestion.daysAgo()).withHour(19);
            boolean reviewed = !"PENDING".equals(suggestion.status());
            suggestions.add(new Object[]{suggestionId, players.get(suggestion.player()).userId(), suggestion.name(),
                    suggestion.sports(), suggestion.latitude(), suggestion.longitude(), suggestion.address(),
                    suggestion.phone(), suggestion.note(), suggestion.status(), suggestion.rejectReason(),
                    reviewed ? DemoPopulation.userId("kiemduyet@goatsports.vn") : null,
                    reviewed ? Timestamp.valueOf(createdAt.plusDays(1).withHour(10)) : null,
                    Timestamp.valueOf(createdAt)});
        }
        int inserted = sum(jdbc.batchUpdate("""
                insert into external_venue_suggestions (suggestion_id, suggested_by, name, sport_types, latitude, longitude,
                                                        address, phone, note, status, reject_reason, reviewed_by,
                                                        reviewed_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                """, suggestions));
        int wants = wants(players, now);
        log.info("Nhu cầu sân ngoài hệ thống mô phỏng: {} đề xuất, {} lượt muốn sân lên GOAT", inserted, wants);
    }

    /** 8 sân OSM đầu tiên (theo id) ở mỗi khu vực đã nhập: 3–9 người chơi mô phỏng muốn sân lên GOAT Sports. */
    private int wants(List<DemoPopulation.Player> players, LocalDateTime now) {
        List<UUID> venues = jdbc.queryForList("""
                select external_venue_id from (
                    select external_venue_id, row_number() over (partition by region order by external_venue_id) as rank
                    from external_venues where claimed_venue_id is null
                ) ranked where rank <= 8
                """, UUID.class);
        List<Object[]> rows = new ArrayList<>();
        for (UUID venueId : venues) {
            int count = 3 + Math.floorMod(venueId.hashCode(), 7);
            for (int index = 0; index < count; index++) {
                DemoPopulation.Player player = players.get(Math.floorMod(venueId.hashCode() + index * 37, players.size()));
                rows.add(new Object[]{venueId, player.userId(),
                        Timestamp.valueOf(now.minusDays(1L + Math.floorMod(index * 5 + venueId.hashCode(), 25)).withHour(20))});
            }
        }
        return sum(jdbc.batchUpdate("""
                insert into external_venue_wants (external_venue_id, user_id, created_at) values (?, ?, ?)
                on conflict do nothing
                """, rows));
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) total += Math.max(count, 0);
        return total;
    }
}
