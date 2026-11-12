package iuh.fit.venueservice.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Thị trường mô phỏng (dev / demo): 72 cơ sở ở 4 tỉnh/thành do 16 chủ sân mô phỏng quản lý, cùng 5 cơ sở của chủ sân
 * demo (DemoDataSeeder). Lịch sử {@link DemoPopulation#HISTORY_DAYS} ngày và lịch tương lai tới
 * {@link DemoPopulation#COVER_UNTIL}:
 * <ul>
 *   <li>khung giờ trống cho mọi sân con;</li>
 *   <li>lượt đặt của người chơi mô phỏng (đặt qua app, mỗi người vài buổi một tuần), kèm check-in và đánh giá;</li>
 *   <li>khách vãng lai / đặt qua điện thoại theo đường cầu từng giờ (tối và cuối tuần đông), để tỷ lệ lấp đầy giống
 *       sân thật — dự báo nhu cầu (Chronos-Bolt), doanh thu chủ sân và dashboard admin có số liệu;</li>
 *   <li>giữ sân cho các giải mô phỏng ({@link DemoTournaments}), giờ đã giữ không bán cho ai.</li>
 * </ul>
 *
 * <p>Tất định: cùng ngày chạy ra cùng dữ liệu, chạy lại chỉ bổ sung phần thiếu. Ngày tương lai được lấp dần khi tới gần
 * (đặt trước ít, sát ngày nhiều), và lượt đặt đã qua ngày được chốt (hoàn thành / vắng mặt). Chạy lúc khởi động và
 * mỗi đêm. Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(21)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoSimulationSeeder implements CommandLineRunner {
    static final String CREATED_BY = "demo-sim";
    private static final int SLOT_DAYS = 14;
    /** Người chơi đặt sân qua app trước tối đa bấy nhiêu ngày. */
    private static final int FUTURE_DAYS = 7;
    /** Mã lượt đặt: SM = người chơi mô phỏng, SV = khách vãng lai / đặt qua điện thoại. */
    private static final String PLAYER_CODE = "SM";
    private static final String WALK_IN_CODE = "SV";

    private final JdbcTemplate jdbc;

    record Court(UUID id, String name, String sport, int capacity, String surface, double price) { }

    /** {@code owner} null với cơ sở của chủ sân demo (không thuộc cộng đồng mô phỏng). */
    record Venue(UUID id, String name, DemoPopulation.City city, String district, String ward, String street,
                 double latitude, double longitude, LocalTime open, LocalTime close, DemoPopulation.Owner owner,
                 UUID ownerId, double quality, List<String> amenities, List<Court> courts) {
        boolean offers(String sport) {
            return courts.stream().anyMatch(court -> court.sport().equals(sport));
        }

        /** Độ đông khách so với đường cầu chuẩn: sân tốt đông hơn. */
        double popularity() {
            return 0.35 + Math.max(0, Math.min(1, (quality - 3.6) / 1.3)) * 0.45;
        }
    }

    // ---- Cơ sở mô phỏng ----

    /** Số cơ sở mỗi nơi, cùng thứ tự với {@link DemoPopulation#CITIES} (thứ tự cố định thì id mới tất định). */
    private static final int[] VENUES_PER_CITY = {14, 9, 6, 7};
    /** Đợt mở rộng: thêm bằng ấy cơ sở nữa, sinh bằng bộ số ngẫu nhiên riêng nên không đổi các cơ sở đã có. */
    private static final int[] MORE_VENUES_PER_CITY = {14, 9, 6, 7};
    private static final String[] NAMES = {"Hoàng Long", "Phượng Hoàng", "Thống Nhất", "Sao Mai", "Bình Minh",
            "Hòa Bình", "Kỳ Hòa", "Hải Âu", "Thành Công", "Chiến Thắng", "Nam Việt", "Đại Dương", "Bạch Đằng", "Lam Sơn",
            "Hùng Vương", "Đông Á", "Hưng Thịnh", "Ánh Dương", "Trường Xuân", "Phú Quý", "An Khang", "Tân Tiến",
            "Hồng Hà", "Sông Hàn", "Biển Xanh", "Vạn Phúc", "Minh Khai", "Lạc Long", "Hòa Hưng", "Phúc Lộc"};
    private static final String[] MORE_NAMES = {"Kim Long", "Thiên Phúc", "Hòa Phát", "Tường Vy", "Bến Thành",
            "Sen Vàng", "Thăng Long", "Ngọc Lan", "Sóng Thần", "Gió Mới", "Hướng Dương", "Bảo An", "Vĩnh Hưng",
            "Đồng Tâm", "Hải Đăng", "Cầu Vồng", "Mặt Trời", "Tràng An", "Hoa Sen", "Phước Lộc", "Mỹ An", "Trúc Xanh",
            "Ngân Hà", "Tân Phú", "Sơn Ca", "Hạnh Phúc", "Quang Trung", "Lê Lai", "An Phú", "Đất Việt"};
    private static final String[] STREETS = {"Nguyễn Văn Linh", "Lê Lợi", "Trần Hưng Đạo", "Hai Bà Trưng",
            "Lý Thường Kiệt", "Phạm Văn Đồng", "Võ Văn Kiệt", "Nguyễn Trãi", "Điện Biên Phủ", "Lê Duẩn", "Hoàng Văn Thụ",
            "Trường Chinh", "Cách Mạng Tháng 8", "Phan Đăng Lưu", "Nguyễn Thị Minh Khai"};
    private static final String[] TYPES = {"FOOTBALL", "BADMINTON", "PICKLEBALL", "TENNIS", "ARENA", "COMPLEX"};
    private static final int[] TYPE_WEIGHTS = {30, 28, 15, 9, 8, 10};
    private static final Map<String, Double> BASE_PRICE = Map.of("FOOTBALL", 380_000.0, "BADMINTON", 120_000.0,
            "PICKLEBALL", 160_000.0, "TENNIS", 200_000.0, "BASKETBALL", 300_000.0, "VOLLEYBALL", 250_000.0);
    private static final Map<String, Double> CITY_PRICE = Map.of("Hồ Chí Minh", 1.0, "Hà Nội", 1.0,
            "Đà Nẵng", 0.85, "Khánh Hòa", 0.8);

    static List<Venue> venues() {
        List<Venue> venues = new ArrayList<>();
        List<DemoPopulation.Owner> owners = DemoPopulation.owners();
        // Đợt đầu chỉ chia cho 8 chủ sân ban đầu: giữ nguyên chủ của các cơ sở đã có.
        addVenues(venues, new Random(0x5E4_2026L), NAMES, VENUES_PER_CITY,
                owners.subList(0, DemoPopulation.FIRST_OWNERS));
        addVenues(venues, new Random(0x5E4_2027L), MORE_NAMES, MORE_VENUES_PER_CITY,
                owners.subList(DemoPopulation.FIRST_OWNERS, owners.size()));
        return venues;
    }

    private static void addVenues(List<Venue> venues, Random random, String[] names, int[] perCity,
                                  List<DemoPopulation.Owner> owners) {
        int nameCursor = 0;
        for (int cityIndex = 0; cityIndex < perCity.length; cityIndex++) {
            DemoPopulation.City city = DemoPopulation.CITIES.get(cityIndex);
            List<DemoPopulation.Owner> cityOwners = owners.stream().filter(owner -> owner.city() == city).toList();
            for (int index = 0; index < perCity[cityIndex]; index++) {
                String type = TYPES[DemoPopulation.weighted(random, TYPE_WEIGHTS)];
                String brand = names[nameCursor++ % names.length];
                String name = switch (type) {
                    case "FOOTBALL" -> "Sân Bóng Đá Mini " + brand;
                    case "BADMINTON" -> "Cầu Lông " + brand;
                    case "PICKLEBALL" -> "Pickleball " + brand;
                    case "TENNIS" -> "Tennis " + brand;
                    case "ARENA" -> "Nhà Thi Đấu " + brand;
                    default -> "Trung Tâm Thể Thao " + brand;
                };
                String district = city.districts().get(random.nextInt(city.districts().size()));
                String ward = "Khánh Hòa".equals(city.name()) && "Nha Trang".equals(district)
                        ? "Phường " + new String[]{"Nam Nha Trang", "Bắc Nha Trang", "Tây Nha Trang"}[random.nextInt(3)]
                        : "Phường " + (1 + random.nextInt(15));
                String street = (10 + random.nextInt(480)) + " " + STREETS[random.nextInt(STREETS.length)];
                UUID venueId = id("sim-venue", city.name() + "|" + name);
                List<Court> courts = courts(type, venueId, city, random);
                boolean indoor = type.equals("BADMINTON") || type.equals("ARENA");
                List<String> amenities = new ArrayList<>(List.of("Bãi giữ xe", "Nước uống"));
                for (String amenity : new String[]{"Wifi", "Phòng thay đồ", "Căng tin", "Cho thuê dụng cụ", "Đèn chiếu sáng"}) {
                    if (random.nextInt(100) < 55) amenities.add(amenity);
                }
                if (indoor) amenities.add("Máy lạnh");
                DemoPopulation.Owner owner = cityOwners.get(index % cityOwners.size());
                venues.add(new Venue(venueId, name, city, district, ward, street,
                        round(city.latitude() + (random.nextDouble() - 0.5) * 0.12),
                        round(city.longitude() + (random.nextDouble() - 0.5) * 0.12),
                        LocalTime.of(random.nextBoolean() ? 5 : 6, 0), LocalTime.of(random.nextBoolean() ? 22 : 23, 0),
                        owner, owner.userId(), 3.6 + random.nextDouble() * 1.3, List.copyOf(amenities), courts));
            }
        }
    }

    private static List<Court> courts(String type, UUID venueId, DemoPopulation.City city, Random random) {
        List<String[]> plan = switch (type) {
            case "FOOTBALL" -> List.of(new String[]{"FOOTBALL", "Sân 5 người", "Cỏ nhân tạo", "10"},
                    new String[]{"FOOTBALL", "Sân 5 người", "Cỏ nhân tạo", "10"},
                    new String[]{"FOOTBALL", "Sân 7 người", "Cỏ nhân tạo", "14"});
            case "BADMINTON" -> repeat(new String[]{"BADMINTON", "Sân", "Sàn gỗ", "4"}, 4 + random.nextInt(3));
            case "PICKLEBALL" -> repeat(new String[]{"PICKLEBALL", "Sân", "Mặt nhựa acrylic", "4"}, 3 + random.nextInt(2));
            case "TENNIS" -> repeat(new String[]{"TENNIS", "Sân", "Mặt cứng", "4"}, 2 + random.nextInt(2));
            case "ARENA" -> List.of(new String[]{"BASKETBALL", "Sân bóng rổ", "Sàn gỗ", "10"},
                    new String[]{"VOLLEYBALL", "Sân bóng chuyền", "Sàn PVC", "12"},
                    new String[]{"VOLLEYBALL", "Sân bóng chuyền", "Sàn PVC", "12"});
            default -> List.of(new String[]{"FOOTBALL", "Sân 5 người", "Cỏ nhân tạo", "10"},
                    new String[]{"BADMINTON", "Sân cầu lông", "Thảm PVC", "4"},
                    new String[]{"BADMINTON", "Sân cầu lông", "Thảm PVC", "4"},
                    new String[]{"PICKLEBALL", "Sân pickleball", "Mặt nhựa acrylic", "4"});
        };
        List<Court> courts = new ArrayList<>();
        double priceFactor = CITY_PRICE.get(city.name()) * (0.85 + random.nextDouble() * 0.35);
        Map<String, Integer> numbering = new HashMap<>();
        for (String[] court : plan) {
            int number = numbering.merge(court[1], 1, Integer::sum);
            String name = court[1] + " " + number;
            double price = Math.round(BASE_PRICE.get(court[0]) * priceFactor * ("Sân 7 người".equals(court[1]) ? 1.4 : 1)
                    / 10_000d) * 10_000d;
            courts.add(new Court(id("sim-court", venueId + "|" + name), name, court[0], Integer.parseInt(court[3]),
                    court[2], price));
        }
        return courts;
    }

    private static List<String[]> repeat(String[] court, int count) {
        List<String[]> courts = new ArrayList<>();
        for (int index = 0; index < count; index++) courts.add(court);
        return courts;
    }

    /** Cơ sở của chủ sân demo (DemoDataSeeder) — chỉ lấy những cơ sở đã có trong DB. */
    private List<Venue> demoVenues() {
        Set<UUID> existing = new HashSet<>(jdbc.queryForList("select venue_id from venues", UUID.class));
        List<Venue> venues = new ArrayList<>();
        for (DemoDataSeeder.VenueSeed seed : DemoDataSeeder.VENUES) {
            UUID venueId = id("venue", seed.name());
            DemoPopulation.City city = DemoPopulation.CITIES.stream()
                    .filter(item -> item.name().equals(seed.city())).findFirst().orElse(null);
            if (city == null || !existing.contains(venueId)) continue;
            List<Court> courts = seed.courts().stream()
                    .map(court -> new Court(id("court", seed.name() + "|" + court.name()), court.name(),
                            court.sportType().name(), court.capacity(), court.surface(), court.pricePerHour()))
                    .toList();
            venues.add(new Venue(venueId, seed.name(), city, seed.district(), seed.ward(), seed.street(),
                    seed.latitude(), seed.longitude(), seed.openTime(), seed.closeTime(), null,
                    DemoPopulation.userId(seed.ownerEmail()), 4.4, List.of(), courts));
        }
        return venues;
    }

    // ---- Chạy ----

    @Override
    @Transactional
    public void run(String... args) {
        seed(LocalDate.now());
    }

    /** Mỗi đêm: chốt lượt đặt hôm qua, lấp thêm các ngày tới (sát ngày thì đông hơn). */
    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void daily() {
        seed(LocalDate.now());
    }

    void seed(LocalDate today) {
        LocalDate first = today.minusDays(DemoPopulation.HISTORY_DAYS);
        LocalDate last = today.plusDays(SLOT_DAYS - 1L).isAfter(DemoPopulation.COVER_UNTIL)
                ? today.plusDays(SLOT_DAYS - 1L) : DemoPopulation.COVER_UNTIL;
        List<Venue> simulated = venues();
        int newVenues = seedVenues(simulated, today);
        List<Venue> venues = new ArrayList<>(simulated);
        venues.addAll(demoVenues());
        int slots = seedSlots(venues, today, last);
        int holds = seedTournamentHolds();
        Calendar calendar = loadCalendar(venues, first, last);
        int settled = settle(venues, today);
        BookingStats players = seedPlayerBookings(venues, calendar, first, today);
        int walkIns = seedWalkIns(venues, calendar, first, today, last);
        // Lượt đặt sắp tới giữ đúng khung giờ, nếu không sân bị chào bán hai lần.
        jdbc.update("""
                update time_slots t set status = 'BOOKED', updated_at = now(), updated_by = ?
                from bookings b
                where b.created_by = ? and b.status = 'CONFIRMED' and t.venue_court_id = b.venue_court_id
                  and t.date = b.play_date and t.start_time = b.start_time and t.status = 'AVAILABLE'
                """, CREATED_BY, CREATED_BY);
        if (players.inserted() + players.reviews() + settled > 0) {
            refreshRatings();
            // Lượt đặt ghi thẳng vào DB không phát sự kiện hành vi: cho lượt nạp bù chạy lại để ai-service học được.
            jdbc.update("delete from activity_backfills where name = 'bookings-v1'");
        }
        log.info("Dữ liệu mô phỏng: {} cơ sở mới ({} cơ sở có lịch), {} khung giờ, {} lịch giữ sân cho giải, "
                        + "{} lượt đặt người chơi ({} đánh giá), {} lượt khách vãng lai, {} lượt đã chốt; tới {}",
                newVenues, venues.size(), slots, holds, players.inserted(), players.reviews(), walkIns, settled, last);
    }

    private int seedVenues(List<Venue> venues, LocalDate today) {
        int inserted = 0;
        Timestamp since = Timestamp.valueOf(today.minusDays(DemoPopulation.HISTORY_DAYS + 30).atStartOfDay());
        for (Venue venue : venues) {
            UUID addressId = id("sim-address", venue.id().toString());
            jdbc.update("""
                            insert into addresses (address_id, address, ward, district, city, latitude, longitude,
                                                   created_at, created_by, updated_at, updated_by)
                            values (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?) on conflict do nothing
                            """, addressId, venue.street(), venue.ward(), venue.district(), venue.city().name(),
                    venue.latitude(), venue.longitude(), CREATED_BY, CREATED_BY);
            double min = venue.courts().stream().mapToDouble(Court::price).min().orElse(0);
            double max = venue.courts().stream().mapToDouble(Court::price).max().orElse(0);
            inserted += jdbc.update("""
                            insert into venues (venue_id, name, description, open_time, close_time, active, min_price,
                                                max_price, average_rating, total_reviews, phone, email, owner_id,
                                                address_id, amenities, created_at, created_by, updated_at, updated_by)
                            values (?, ?, ?, ?, ?, true, ?, ?, 0, 0, ?, ?, ?, ?, cast(? as jsonb), ?, ?, now(), ?)
                            on conflict do nothing
                            """, venue.id(), venue.name(), description(venue), Time.valueOf(venue.open()),
                    Time.valueOf(venue.close()), min, max, "0283" + String.format("%07d", Math.abs(venue.id().hashCode()) % 10_000_000),
                    DemoPopulation.ascii(venue.name()) + "@" + DemoPopulation.DOMAIN, venue.ownerId(), addressId,
                    toJson(venue.amenities()), since, CREATED_BY, CREATED_BY);
            for (Court court : venue.courts()) {
                jdbc.update("""
                                insert into venue_courts (venue_court_id, name, sport_type, capacity, surface_type, active,
                                                          venue_id, created_at, created_by, updated_at, updated_by)
                                values (?, ?, ?, ?, ?, true, ?, ?, ?, now(), ?) on conflict do nothing
                                """, court.id(), court.name(), court.sport(), court.capacity(), court.surface(),
                        venue.id(), since, CREATED_BY, CREATED_BY);
            }
            jdbc.update("""
                            insert into cancellation_policies (policy_id, full_refund_hours_before,
                                                               partial_refund_hours_before, partial_refund_percentage,
                                                               no_refund_hours_before, venue_id,
                                                               created_at, created_by, updated_at, updated_by)
                            select ?, 24, 6, 50, 2, ?, now(), ?, now(), ?
                            where not exists (select 1 from cancellation_policies where venue_id = ?)
                            """, id("sim-policy", venue.id().toString()), venue.id(), CREATED_BY, CREATED_BY, venue.id());
        }
        // Chủ sân chỉ mở được khu vận hành khi có hồ sơ đã duyệt (gắn với cơ sở đầu tiên của họ).
        Set<UUID> approved = new HashSet<>();
        for (Venue venue : venues) {
            if (!approved.add(venue.ownerId())) continue;
            // address_id là duy nhất ở cả venues lẫn owner_applications: hồ sơ giữ bản sao địa chỉ riêng.
            UUID applicationAddressId = id("sim-owner-application-address", venue.owner().email());
            jdbc.update("""
                            insert into addresses (address_id, address, ward, district, city, latitude, longitude,
                                                   created_at, created_by, updated_at, updated_by)
                            select ?, address, ward, district, city, latitude, longitude, now(), ?, now(), ?
                            from addresses where address_id = ? on conflict do nothing
                            """, applicationAddressId, CREATED_BY, CREATED_BY, id("sim-address", venue.id().toString()));
            jdbc.update("""
                            insert into owner_applications (owner_application_id, user_id, full_name, phone, email,
                                                            business_name, business_type, status, address_id, venue_id,
                                                            reviewed_at, received_at, viewed_at,
                                                            created_at, created_by, updated_at, updated_by)
                            select ?, ?, ?, '0909000000', ?, ?, 'INDIVIDUAL', 'APPROVED', ?, ?, now(), now(), now(),
                                   now(), ?, now(), ?
                            where not exists (select 1 from owner_applications where user_id = ? and status = 'APPROVED')
                            """, id("sim-owner-application", venue.owner().email()), venue.ownerId(),
                    venue.owner().fullName(), venue.owner().email(), venue.name(),
                    applicationAddressId, venue.id(), CREATED_BY, CREATED_BY, venue.ownerId());
        }
        return inserted;
    }

    /** Khung giờ theo giờ mở cửa, từ hôm nay tới {@code last}; mỗi sân con một câu lệnh, id tất định theo md5. */
    private int seedSlots(List<Venue> venues, LocalDate today, LocalDate last) {
        int inserted = 0;
        for (Venue venue : venues) {
            for (Court court : venue.courts()) {
                inserted += jdbc.update("""
                                insert into time_slots (time_slot_id, date, start_time, end_time, price_per_hour, status,
                                                        venue_court_id, created_at, created_by, updated_at, updated_by)
                                select md5('goatsports:sim-slot:' || ? || '|' || d::date || '|' || h)::uuid,
                                       d::date, make_time(h, 0, 0), make_time(h + 1, 0, 0),
                                       case when h between 17 and 20 then round(? * 1.4 / 1000) * 1000 else ? end,
                                       'AVAILABLE', ?, now(), ?, now(), ?
                                from generate_series(cast(? as date), cast(? as date), interval '1 day') d,
                                     generate_series(?, ?) h
                                on conflict do nothing
                                """, court.id().toString(), court.price(), court.price(), court.id(), CREATED_BY,
                        CREATED_BY, Date.valueOf(today), Date.valueOf(last),
                        firstHour(venue), lastHour(venue));
            }
        }
        return inserted;
    }

    /** Giữ sân cho các giải mô phỏng của club-service: cả khoảng ngày của giải, trong khung giờ thi đấu. */
    private int seedTournamentHolds() {
        Set<UUID> courts = new HashSet<>(jdbc.queryForList("select venue_court_id from venue_courts", UUID.class));
        List<Object[]> rows = new ArrayList<>();
        for (DemoTournaments.Tournament tournament : DemoTournaments.ALL) {
            for (String court : tournament.courts()) {
                UUID courtId = tournament.venue().courtId(court);
                if (!courts.contains(courtId)) continue;
                rows.add(new Object[]{id("court-hold", tournament.id() + "|" + courtId), tournament.id(),
                        tournament.venue().id(), courtId, Date.valueOf(tournament.start()), Date.valueOf(tournament.end()),
                        Time.valueOf(tournament.dailyStart()), Time.valueOf(tournament.dailyEnd())});
            }
        }
        return sum(jdbc.batchUpdate("""
                        insert into court_holds (hold_id, tournament_id, venue_id, venue_court_id, from_date, to_date,
                                                 daily_start, daily_end, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, now()) on conflict do nothing
                        """, rows, 200, DemoSimulationSeeder::bind));
    }

    /**
     * Giờ đã có người đặt (kể cả người dùng thật) hoặc đã giữ cho giải. Mỗi sân con một BitSet theo giờ tính từ
     * {@code first}: vài trăm byte mỗi sân thay vì hàng trăm nghìn chuỗi (production chạy với heap 128MB).
     */
    private record Calendar(LocalDate first, Map<UUID, BitSet> taken, Map<UUID, List<Object[]>> holds) {
        void take(UUID courtId, LocalDate date, int hour) {
            taken.computeIfAbsent(courtId, key -> new BitSet()).set(index(date, hour));
        }

        boolean free(UUID courtId, LocalDate date, int hour) {
            BitSet hours = taken.get(courtId);
            if (hours != null && hours.get(index(date, hour))) return false;
            LocalTime slotStart = LocalTime.of(hour, 0);
            LocalTime slotEnd = slotStart.plusHours(1);
            for (Object[] hold : holds.getOrDefault(courtId, List.of())) {
                LocalDate from = ((Date) hold[0]).toLocalDate();
                LocalDate to = ((Date) hold[1]).toLocalDate();
                LocalTime holdStart = ((Time) hold[2]).toLocalTime();
                LocalTime holdEnd = ((Time) hold[3]).toLocalTime();
                if (!date.isBefore(from) && !date.isAfter(to)
                        && slotStart.isBefore(holdEnd) && slotEnd.isAfter(holdStart)) {
                    return false;
                }
            }
            return true;
        }

        private int index(LocalDate date, int hour) {
            return (int) ChronoUnit.DAYS.between(first, date) * 24 + hour;
        }
    }

    private Calendar loadCalendar(List<Venue> venues, LocalDate first, LocalDate last) {
        Set<UUID> courts = new HashSet<>();
        venues.forEach(venue -> venue.courts().forEach(court -> courts.add(court.id())));
        Map<UUID, List<Object[]>> holds = new HashMap<>();
        jdbc.query("select venue_court_id, from_date, to_date, daily_start, daily_end from court_holds", row -> {
            holds.computeIfAbsent(row.getObject(1, UUID.class), key -> new ArrayList<>())
                    .add(new Object[]{row.getDate(2), row.getDate(3), row.getTime(4), row.getTime(5)});
        });
        Calendar calendar = new Calendar(first, new HashMap<>(), holds);
        jdbc.query("""
                select venue_court_id, play_date, extract(hour from start_time)::int, extract(hour from end_time)::int
                from bookings
                where play_date between ? and ? and status not in ('CANCELLED', 'EXPIRED', 'REFUNDED')
                """, row -> {
            UUID courtId = row.getObject(1, UUID.class);
            if (!courts.contains(courtId)) return;
            LocalDate date = row.getDate(2).toLocalDate();
            for (int hour = row.getInt(3); hour < Math.max(row.getInt(3) + 1, row.getInt(4)); hour++) {
                calendar.take(courtId, date, hour);
            }
        }, Date.valueOf(first), Date.valueOf(last));
        return calendar;
    }

    record BookingStats(int inserted, int reviews) { }

    /**
     * Lượt đặt qua app của người chơi mô phỏng: mỗi người, mỗi ngày quyết định có đi chơi không (theo mức năng nổ,
     * cuối tuần nhiều hơn), chọn sân "quen" hoặc sân khác cùng tỉnh, giờ vàng buổi tối. Người đã có lịch sử thì chỉ
     * bổ sung từ ngày sau lượt đặt cuối; người mới (đợt mở rộng) được sinh đủ lịch sử.
     */
    private BookingStats seedPlayerBookings(List<Venue> venues, Calendar calendar, LocalDate first, LocalDate today) {
        LocalDate last = today.plusDays(FUTURE_DAYS);
        Date latest = jdbc.queryForObject("select max(play_date) from bookings where created_by = ? and booking_code like ?",
                Date.class, CREATED_BY, PLAYER_CODE + "%");
        LocalDate from = latest == null ? first : latest.toLocalDate().plusDays(1);
        Set<UUID> withHistory = new HashSet<>(jdbc.queryForList(
                "select distinct player_id from bookings where created_by = ? and booking_code like ? and player_id is not null",
                UUID.class, CREATED_BY, PLAYER_CODE + "%"));
        List<DemoPopulation.Player> players = DemoPopulation.players();
        boolean newcomers = players.stream().anyMatch(player -> !withHistory.contains(player.userId()));
        LocalDate start = newcomers ? first : from.isBefore(first) ? first : from;

        List<Object[]> bookings = new ArrayList<>();
        List<Object[]> checkIns = new ArrayList<>();
        List<Object[]> reviews = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(last); date = date.plusDays(1)) {
            for (DemoPopulation.Player player : players) {
                if (withHistory.contains(player.userId()) && date.isBefore(from)) continue;
                Random random = DemoPopulation.random(0xB00C, date.toEpochDay(), player.index());
                if (random.nextDouble() >= dailyProbability(player, date, first, today)) continue;
                String sport = player.sports().size() > 1 && random.nextInt(100) < 25 ? player.sports().get(1) : player.sports().get(0);
                List<Venue> candidates = venues.stream()
                        .filter(venue -> venue.city() == player.city() && venue.offers(sport)).toList();
                if (candidates.isEmpty()) continue;
                // Mỗi người có một sân "quen" cho mỗi môn; 65% số lần đặt ở đó.
                Venue venue = random.nextInt(100) < 65
                        ? candidates.get(Math.floorMod((player.email() + sport).hashCode(), candidates.size()))
                        : candidates.get(random.nextInt(candidates.size()));
                int hour = hour(random, date, venue);
                LocalDate day = date;
                Court court = venue.courts().stream()
                        .filter(item -> item.sport().equals(sport) && calendar.free(item.id(), day, hour))
                        .findFirst().orElse(null);
                if (court == null) continue;
                calendar.take(court.id(), date, hour);
                String status = !date.isBefore(today) ? "CONFIRMED"
                        : random.nextInt(100) < 88 ? "COMPLETED" : random.nextBoolean() ? "CANCELLED" : "NO_SHOW";
                double total = price(court, hour);
                double deposit = Math.round(total * 0.3 / 1000d) * 1000d;
                UUID bookingId = id("sim-booking", player.email() + "|" + date + "|" + hour);
                Timestamp createdAt = Timestamp.valueOf(date.minusDays(random.nextInt(5)).atTime(7 + random.nextInt(14), 0));
                bookings.add(new Object[]{bookingId, player.userId(), Date.valueOf(date), Time.valueOf(LocalTime.of(hour, 0)),
                        Time.valueOf(LocalTime.of(hour + 1, 0)), status, "DIRECT", total, deposit, total - deposit,
                        code(PLAYER_CODE, bookingId), null, court.id(), createdAt, CREATED_BY, createdAt, CREATED_BY});
                if (!"COMPLETED".equals(status)) continue;
                checkIns.add(checkIn(bookingId, venue, total - deposit, date, hour));
                if (random.nextInt(100) < 30) reviews.add(review(random, bookingId, player.userId(), venue, sport, date));
            }
        }
        int inserted = insertBookings(bookings);
        insertCheckIns(checkIns);
        return new BookingStats(inserted, insertReviews(reviews));
    }

    /**
     * Khách vãng lai / đặt qua điện thoại, theo đường cầu từng giờ của DemoDataSeeder × độ đông của sân × nhịp tăng
     * trưởng. Ngày đã qua sinh một lần; ngày tương lai mỗi lần chạy lấp thêm vì tỷ lệ đặt trước tăng khi tới gần ngày.
     */
    private int seedWalkIns(List<Venue> venues, Calendar calendar, LocalDate first, LocalDate today, LocalDate last) {
        Map<UUID, LocalDate> generatedUntil = new HashMap<>();
        jdbc.query("""
                select c.venue_id, max(b.play_date) from bookings b join venue_courts c on c.venue_court_id = b.venue_court_id
                where b.created_by = ? and b.booking_code like ? and b.play_date < ? group by c.venue_id
                """, row -> {
            generatedUntil.put(row.getObject(1, UUID.class), row.getDate(2).toLocalDate());
        }, CREATED_BY, WALK_IN_CODE + "%", Date.valueOf(today));
        List<Object[]> bookings = new ArrayList<>();
        int inserted = 0;
        for (Venue venue : venues) {
            // Ghi theo lô: lần chạy đầu sinh hơn trăm nghìn lượt, không giữ hết trong bộ nhớ.
            if (bookings.size() >= 2000) {
                inserted += insertBookings(bookings);
                bookings.clear();
            }
            LocalDate until = generatedUntil.get(venue.id());
            LocalDate from = until == null ? first : until.plusDays(1);
            double popularity = venue.popularity();
            for (LocalDate date = from; !date.isAfter(last); date = date.plusDays(1)) {
                double scale = popularity * growth(date, first, today) * advance(date, today);
                for (Court court : venue.courts()) {
                    for (int hour = firstHour(venue); hour <= lastHour(venue); hour++) {
                        UUID bookingId = id("sim-walkin", court.id() + "|" + date + "|" + hour);
                        if (DemoDataSeeder.unitFromId(bookingId) >= DemoDataSeeder.demandProbability(date, hour) * scale) continue;
                        if (!calendar.free(court.id(), date, hour)) continue;
                        calendar.take(court.id(), date, hour);
                        double total = price(court, hour);
                        String status = !date.isBefore(today) ? "CONFIRMED"
                                : DemoDataSeeder.unitFromId(id("sim-walkin-status", bookingId.toString())) < 0.95
                                ? "COMPLETED" : "NO_SHOW";
                        Timestamp createdAt = Timestamp.valueOf(date.atTime(hour, 0).minusHours(3 + Math.floorMod(bookingId.hashCode(), 60)));
                        bookings.add(new Object[]{bookingId, null, Date.valueOf(date), Time.valueOf(LocalTime.of(hour, 0)),
                                Time.valueOf(LocalTime.of(hour + 1, 0)), status, "WALK_IN", total, 0d, total,
                                code(WALK_IN_CODE, bookingId), customerName(bookingId), court.id(), createdAt, CREATED_BY,
                                createdAt, CREATED_BY});
                    }
                }
            }
        }
        return inserted + insertBookings(bookings);
    }

    /**
     * Chốt lượt đặt đã qua ngày (được sinh khi còn là "sắp tới"): người chơi 90% hoàn thành, còn lại vắng mặt; khách
     * vãng lai 95% hoàn thành. Hoàn thành thì có check-in, 30% lượt của người chơi kèm đánh giá.
     */
    private int settle(List<Venue> venues, LocalDate today) {
        Map<UUID, Venue> venueByCourt = new HashMap<>();
        venues.forEach(venue -> venue.courts().forEach(court -> venueByCourt.put(court.id(), venue)));
        List<Object[]> statuses = new ArrayList<>();
        List<Object[]> checkIns = new ArrayList<>();
        List<Object[]> reviews = new ArrayList<>();
        jdbc.query("""
                select b.booking_id, b.player_id, b.venue_court_id, b.play_date, extract(hour from b.start_time)::int,
                       b.remaining_amount, c.sport_type
                from bookings b join venue_courts c on c.venue_court_id = b.venue_court_id
                where b.created_by = ? and b.status = 'CONFIRMED' and b.play_date < ?
                """, row -> {
            UUID bookingId = row.getObject(1, UUID.class);
            UUID playerId = row.getObject(2, UUID.class);
            Venue venue = venueByCourt.get(row.getObject(3, UUID.class));
            LocalDate date = row.getDate(4).toLocalDate();
            Random random = DemoPopulation.random(0x5E77, bookingId.getMostSignificantBits(), bookingId.getLeastSignificantBits());
            boolean completed = random.nextInt(100) < (playerId == null ? 95 : 90);
            statuses.add(new Object[]{completed ? "COMPLETED" : "NO_SHOW", bookingId});
            if (!completed || venue == null) return;
            checkIns.add(checkIn(bookingId, venue, row.getDouble(6), date, row.getInt(5)));
            if (playerId != null && random.nextInt(100) < 30) {
                reviews.add(review(random, bookingId, playerId, venue, row.getString(7), date));
            }
        }, CREATED_BY, Date.valueOf(today));
        jdbc.batchUpdate("update bookings set status = ?, updated_at = now(), updated_by = 'demo-sim' where booking_id = ?",
                statuses, 500, DemoSimulationSeeder::bind);
        insertCheckIns(checkIns);
        insertReviews(reviews);
        return statuses.size();
    }

    // ---- Ghi DB ----

    private int insertBookings(List<Object[]> rows) {
        return sum(jdbc.batchUpdate("""
                        insert into bookings (booking_id, player_id, play_date, start_time, end_time, status, source,
                                              total_price, deposit_amount, remaining_amount, booking_code,
                                              walk_in_customer_name, venue_court_id,
                                              created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                        """, rows, 1000, DemoSimulationSeeder::bind));
    }

    private void insertCheckIns(List<Object[]> rows) {
        jdbc.batchUpdate("""
                        insert into check_in_records (check_in_id, checked_in_by, method, remaining_amount_collected,
                                                      checked_in_at, booking_id, created_at, created_by, updated_at, updated_by)
                        values (?, ?, 'BOOKING_CODE', ?, ?, ?, now(), 'demo-sim', now(), 'demo-sim') on conflict do nothing
                        """, rows, 1000, DemoSimulationSeeder::bind);
    }

    private int insertReviews(List<Object[]> rows) {
        return sum(jdbc.batchUpdate("""
                        insert into reviews (review_id, player_id, rating, content, status, venue_id, booking_id,
                                             created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?, ?) on conflict do nothing
                        """, rows, 1000, DemoSimulationSeeder::bind));
    }

    private static Object[] checkIn(UUID bookingId, Venue venue, double remaining, LocalDate date, int hour) {
        return new Object[]{id("sim-checkin", bookingId.toString()), venue.ownerId(), remaining,
                Timestamp.valueOf(date.atTime(hour, 0).minusMinutes(10)), bookingId};
    }

    private static Object[] review(Random random, UUID bookingId, UUID playerId, Venue venue, String sport, LocalDate date) {
        int rating = (int) Math.max(1, Math.min(5, Math.round(venue.quality() + random.nextGaussian() * 0.7)));
        Timestamp reviewedAt = Timestamp.valueOf(date.plusDays(1).atTime(19 + random.nextInt(3), 0));
        String text = reviewText(random, rating, sport);
        String abusive = abusiveText(bookingId);
        return new Object[]{id("sim-review", bookingId.toString()), playerId, abusive == null ? rating : 1,
                abusive == null ? text : abusive, venue.id(), bookingId, reviewedAt, CREATED_BY, reviewedAt, CREATED_BY};
    }

    /**
     * ~3% đánh giá vi phạm thật (lừa đảo, spam, xúc phạm, đe dọa) để bộ lọc "AI nghi vi phạm" của admin có việc.
     * Chọn theo mã đơn, không rút từ {@code random}: rút thêm sẽ làm lệch các lượt đặt sinh sau đó.
     */
    static String abusiveText(UUID bookingId) {
        if (Math.floorMod(bookingId.getLeastSignificantBits() >>> 20, 100) >= 3) return null;
        return ABUSIVE[Math.floorMod(bookingId.getMostSignificantBits() >>> 20, ABUSIVE.length)];
    }

    private static final String[] ABUSIVE = {
            "Nhận đặt sân giá rẻ hơn 50%, chuyển khoản trước 200k cọc qua số 0987xxxxxx rồi nhắn Zalo để giữ chỗ.",
            "Bấm link bit.ly/san-free-2026 nhận voucher 500k đặt sân miễn phí, chỉ cần nhập mật khẩu tài khoản.",
            "Bán acc game giá rẻ, cày thuê, inbox ngay!!! Bán acc game giá rẻ, cày thuê, inbox ngay!!!",
            "Thằng chủ sân mất dạy, mặt như cái l**, loại người như mày nên cút khỏi nghề đi.",
            "Nhân viên ở đây ngu như bò, đầu óc toàn bã đậu, tao gặp lại là tao tát vỡ mồm.",
            "Lần sau còn xếp sân kiểu này thì tao dẫn anh em tới đập nát cái sân, đốt luôn quầy nước.",
            "Dân tỉnh đó toàn bọn lừa đảo, đừng có cho tụi nó vào sân chơi chung."};

    // ---- Mô hình nhu cầu ----

    /** Xác suất người chơi đặt sân trong một ngày: theo mức năng nổ, cuối tuần đông hơn, cộng đồng lớn dần theo thời gian. */
    static double dailyProbability(DemoPopulation.Player player, LocalDate date, LocalDate first, LocalDate today) {
        double weekday = date.getDayOfWeek().getValue() >= 6 ? 1.6 : 0.75;
        return Math.min(0.9, player.bookingsPerWeek() / 7 * weekday * growth(date, first, today));
    }

    /** Nhịp tăng trưởng: đầu kỳ lịch sử bằng 55% hiện tại, sau hôm nay giữ nguyên. */
    static double growth(LocalDate date, LocalDate first, LocalDate today) {
        double span = Math.max(1, ChronoUnit.DAYS.between(first, today));
        double progress = Math.min(1, Math.max(0, ChronoUnit.DAYS.between(first, date) / span));
        return 0.55 + 0.45 * progress;
    }

    /** Phần đã được đặt trước của một ngày tương lai: xa ngày ít (12%), sát ngày gần đủ. */
    static double advance(LocalDate date, LocalDate today) {
        long ahead = ChronoUnit.DAYS.between(today, date);
        return ahead <= 0 ? 1 : Math.max(0.12, 1 - ahead / 24.0);
    }

    /** Giờ bắt đầu: tối là giờ vàng; cuối tuần có thêm buổi sáng; luôn nằm trong giờ mở cửa. */
    private static int hour(Random random, LocalDate date, Venue venue) {
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        int[] hours = weekend ? new int[]{6, 7, 8, 9, 15, 16, 17, 18, 19, 20} : new int[]{6, 11, 17, 18, 19, 20, 21};
        int[] weights = weekend ? new int[]{8, 12, 10, 6, 6, 8, 12, 14, 14, 10} : new int[]{6, 4, 14, 22, 24, 18, 8};
        int hour = hours[DemoPopulation.weighted(random, weights)];
        return Math.max(firstHour(venue), Math.min(lastHour(venue), hour));
    }

    /** Khung giờ đầu tiên trọn vẹn trong giờ mở cửa (mở 5:30 thì từ 6:00). */
    private static int firstHour(Venue venue) {
        return venue.open().getMinute() == 0 ? venue.open().getHour() : venue.open().getHour() + 1;
    }

    private static int lastHour(Venue venue) {
        return venue.close().getHour() - 1;
    }

    private static double price(Court court, int hour) {
        return Math.round((hour >= 17 && hour <= 20 ? court.price() * 1.4 : court.price()) / 1000d) * 1000d;
    }

    private static String code(String prefix, UUID bookingId) {
        return prefix + Math.floorMod(bookingId.getMostSignificantBits(), 1_000_000_000_000L);
    }

    private static final String[] CUSTOMERS = {"Anh Tuấn", "Chị Hằng", "Anh Phong", "Anh Khoa", "Chị Ngọc", "Anh Dũng",
            "Anh Hải", "Chị Trang", "Anh Long", "Anh Quân", "Chị Vy", "Anh Sơn", "Nhóm văn phòng", "Đội phủi",
            "Anh Nam", "Chị Linh", "Anh Hùng", "Anh Thịnh", "Chị Mai", "Anh Toàn"};

    private static String customerName(UUID bookingId) {
        return CUSTOMERS[Math.floorMod(bookingId.getLeastSignificantBits(), CUSTOMERS.length)] + " (khách vãng lai)";
    }

    private static final String[] GOOD = {"Sân đẹp, mặt sân êm, chủ sân nhiệt tình. Sẽ quay lại.",
            "Đặt online nhanh, tới nơi check-in bằng mã là vào chơi luôn.", "Đèn sáng, giá hợp lý so với khu vực.",
            "Nhóm mình chơi ở đây mỗi tuần, chất lượng ổn định.", "Có chỗ gửi xe rộng, nước uống sẵn, sạch sẽ."};
    private static final String[] MID = {"Sân ổn nhưng giờ cao điểm hơi đông, nên đặt sớm.",
            "Chất lượng tạm được, phòng thay đồ cần sạch hơn.", "Giá hơi cao so với mặt sân, bù lại vị trí thuận tiện."};
    private static final String[] BAD = {"Mặt sân xuống cấp, đèn một góc bị tối.", "Tới đúng giờ nhưng phải chờ 15 phút mới vào sân."};

    private static String reviewText(Random random, int rating, String sport) {
        String[] pool = rating >= 4 ? GOOD : rating == 3 ? MID : BAD;
        String text = pool[random.nextInt(pool.length)];
        return random.nextInt(100) < 40 ? text + " (" + sportLabel(sport) + ")" : text;
    }

    private static String description(Venue venue) {
        String sports = venue.courts().stream().map(Court::sport).distinct().map(DemoSimulationSeeder::sportLabel)
                .reduce((left, right) -> left + ", " + right).orElse("");
        return "Cụm " + venue.courts().size() + " sân " + sports.toLowerCase() + " tại " + venue.district() + ", "
                + venue.city().name() + ". Mở cửa " + venue.open() + " – " + venue.close() + ".";
    }

    private static String sportLabel(String sport) {
        return switch (sport) {
            case "FOOTBALL" -> "Bóng đá";
            case "BADMINTON" -> "Cầu lông";
            case "PICKLEBALL" -> "Pickleball";
            case "TENNIS" -> "Tennis";
            case "BASKETBALL" -> "Bóng rổ";
            default -> "Bóng chuyền";
        };
    }

    private void refreshRatings() {
        jdbc.update("""
                update venues v set average_rating = coalesce(stat.avg_rating, 0), total_reviews = coalesce(stat.total, 0)
                from (select venue_id, round(avg(rating)::numeric, 1) as avg_rating, count(*) as total
                      from reviews where status = 'PUBLISHED' group by venue_id) stat
                where v.venue_id = stat.venue_id
                """);
    }

    private static void bind(PreparedStatement statement, Object[] row) throws SQLException {
        for (int column = 0; column < row.length; column++) statement.setObject(column + 1, row[column]);
    }

    private static int sum(int[][] batches) {
        int total = 0;
        for (int[] batch : batches) for (int count : batch) total += Math.max(count, 0);
        return total;
    }

    private static String toJson(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\"", "") + "\"")
                .reduce((left, right) -> left + "," + right).map(body -> "[" + body + "]").orElse("[]");
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
