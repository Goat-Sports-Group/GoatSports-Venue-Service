package iuh.fit.venueservice.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Lịch giải đấu mô phỏng (tháng 7 – hết tháng 12/2026) trên các cơ sở của chủ sân demo. Ngày tuyệt đối, nên chạy lại
 * lúc nào cũng ra cùng lịch: club-service dựng giải, đội, trận và tỷ số; venue-service giữ sân (court_holds) đúng các
 * khung giờ đó để không chào bán trùng.
 *
 * <p><b>Bản sao giống hệt</b> ở venue-service và club-service. Sửa một bản thì sửa cả hai; {@code DemoTournamentsTest}
 * ở mỗi service chốt vài giá trị. Giờ thi đấu tránh khung tối (17–22h) và sáng 8–12h ở Phú Nhuận mà các giải trưng
 * bày (TournamentShowcaseSeeder) đang dùng.
 */
final class DemoTournaments {

    record DemoVenue(String name, String city, String ownerEmail) {
        UUID id() {
            return uuid("venue", name);
        }

        UUID courtId(String court) {
            return uuid("court", name + "|" + court);
        }
    }

    static final DemoVenue BINH_THANH = new DemoVenue("Sân Thể Thao Bình Thạnh", "Hồ Chí Minh", "bao.tranquoc@goatsports.vn");
    static final DemoVenue PHU_NHUAN = new DemoVenue("Trung Tâm Cầu Lông Phú Nhuận", "Hồ Chí Minh",
            "huong.nguyenthimai@goatsports.vn");
    static final DemoVenue MY_DINH = new DemoVenue("Sân Tennis Mỹ Đình", "Hà Nội", "phuc.lehoang@goatsports.vn");
    static final DemoVenue SON_TRA = new DemoVenue("Khu Liên Hợp Pickleball Sơn Trà", "Đà Nẵng", "khoi.phamminh@goatsports.vn");
    static final DemoVenue TAN_BINH = new DemoVenue("Nhà Thi Đấu Tân Bình", "Hồ Chí Minh", "bao.tranquoc@goatsports.vn");

    /**
     * Một giải: loại trực tiếp ({@code teams} là lũy thừa của 2) hoặc vòng tròn ({@code teams} chẵn). Vòng/lượt
     * {@code r} đá vào ngày {@code start + (r-1) * roundGapDays}; các trận cùng ngày chia lần lượt theo sân rồi theo giờ.
     */
    record Tournament(String name, String sport, String playFormat, String format, DemoVenue venue, List<String> courts,
                      LocalDate start, int roundGapDays, int teams, LocalTime dailyStart, LocalTime dailyEnd,
                      int matchMinutes, double fee, double prize, String description) {
        UUID id() {
            return uuid("tournament", name);
        }

        boolean knockout() {
            return "SINGLE_ELIMINATION".equals(format);
        }

        int rounds() {
            return knockout() ? Integer.numberOfTrailingZeros(teams) : teams - 1;
        }

        int matchesInRound(int round) {
            return knockout() ? teams >> round : teams / 2;
        }

        LocalDate roundDate(int round) {
            return start.plusDays((long) (round - 1) * roundGapDays);
        }

        LocalDate end() {
            return roundDate(rounds());
        }

        LocalDate registrationOpen() {
            return start.minusDays(30);
        }

        LocalDate registrationClose() {
            return start.minusDays(4);
        }

        String court(int matchIndex) {
            return courts.get(matchIndex % courts.size());
        }

        LocalTime matchStart(int matchIndex) {
            return dailyStart.plusMinutes((long) (matchIndex / courts.size()) * matchMinutes);
        }
    }

    private static final LocalTime H6 = LocalTime.of(6, 0);
    private static final LocalTime H7 = LocalTime.of(7, 0);
    private static final LocalTime H10 = LocalTime.of(10, 0);
    private static final LocalTime H11 = LocalTime.of(11, 0);
    private static final LocalTime H13 = LocalTime.of(13, 0);
    private static final LocalTime H17 = LocalTime.of(17, 0);
    private static final String KO = "SINGLE_ELIMINATION";
    private static final String RR = "ROUND_ROBIN";
    private static final List<String> SEVEN = List.of("Sân số 1 (7 người)", "Sân số 2 (7 người)");
    private static final List<String> FIVE = List.of("Sân số 3 (5 người)");
    private static final List<String> SHUTTLE = List.of("Sân A", "Sân B", "Sân C");
    private static final List<String> TENNIS = List.of("Sân 1", "Sân 2");
    private static final List<String> PICKLE = List.of("Sân Mỹ Khê 1", "Sân Mỹ Khê 2");
    private static final List<String> HOOPS = List.of("Sân bóng rổ 1", "Sân bóng rổ 2");
    private static final List<String> VOLLEY = List.of("Sân bóng chuyền 1");

    private static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2026, month, dayOfMonth);
    }

    static final List<Tournament> ALL = List.of(
            new Tournament("Cúp Bóng Đá 7 Người Bình Thạnh Hè 2026", "FOOTBALL", "FOOTBALL_7", KO, BINH_THANH, SEVEN,
                    day(7, 26), 7, 8, H7, H11, 60, 1_200_000, 10_000_000,
                    "Giải sân 7 phong trào loại trực tiếp 8 đội, đá sáng Chủ nhật trên hai sân cỏ nhân tạo."),
            new Tournament("Giải Cầu Lông Đôi Nam Nữ Phú Nhuận Hè 2026", "BADMINTON", "BADMINTON_DOUBLES", KO, PHU_NHUAN,
                    SHUTTLE, day(8, 8), 1, 16, H13, H17, 40, 200_000, 4_000_000,
                    "16 cặp đôi nam nữ loại trực tiếp trong 4 buổi chiều, một set 21 điểm."),
            new Tournament("Giải Bóng Rổ 3x3 Tân Bình Hè 2026", "BASKETBALL", "BASKETBALL_3X3", KO, TAN_BINH, HOOPS,
                    day(8, 15), 1, 8, H7, H11, 40, 400_000, 3_000_000,
                    "Bóng rổ đường phố 3x3, 8 đội, mỗi trận tối đa 10 phút hoặc 21 điểm."),
            new Tournament("Giải Pickleball Đà Nẵng Mở Rộng 2026", "PICKLEBALL", "PICKLEBALL_DOUBLES", KO, SON_TRA, PICKLE,
                    day(8, 22), 1, 16, H7, H11, 30, 300_000, 5_000_000,
                    "16 cặp đôi pickleball sát biển Mỹ Khê, đánh tới 11 điểm."),
            new Tournament("Giải Futsal 5 Người Bình Thạnh Thu 2026", "FOOTBALL", "FOOTBALL_5", RR, BINH_THANH, FIVE,
                    day(9, 6), 3, 6, H7, H10, 50, 600_000, 5_000_000,
                    "Vòng tròn một lượt 6 đội, thắng 3 điểm, hòa 1 điểm."),
            new Tournament("Giải Tennis Đơn Hà Nội Mùa Thu 2026", "TENNIS", "TENNIS_SINGLES", KO, MY_DINH, TENNIS,
                    day(9, 19), 1, 8, H6, H11, 90, 350_000, 6_000_000,
                    "Đơn nam nữ loại trực tiếp, đánh 3 set thắng 2 trên mặt sân cứng Mỹ Đình."),
            new Tournament("Giải Cầu Lông Đơn Phong Trào Tháng 10", "BADMINTON", "BADMINTON_SINGLES", KO, PHU_NHUAN, SHUTTLE,
                    day(10, 3), 1, 8, H13, H17, 40, 150_000, 2_000_000,
                    "Đơn phong trào cho người chơi trình độ trung bình, một set 21 điểm."),
            new Tournament("Giải Bóng Đá Phủi Sài Gòn Mở Rộng 2026", "FOOTBALL", "FOOTBALL_7", KO, BINH_THANH, SEVEN,
                    day(10, 11), 7, 16, H6, H11, 60, 1_500_000, 20_000_000,
                    "16 đội phủi khắp Sài Gòn, loại trực tiếp qua 4 Chủ nhật, hòa thì đá luân lưu."),
            new Tournament("Giải Bóng Chuyền Phong Trào Tân Bình Thu 2026", "VOLLEYBALL", "VOLLEYBALL_6", RR, TAN_BINH, VOLLEY,
                    day(10, 17), 2, 6, H7, H10, 60, 500_000, 4_000_000,
                    "Vòng tròn 6 đội, đấu 3 set thắng 2."),
            new Tournament("Giải Pickleball Đôi Mỹ Khê Tháng 11", "PICKLEBALL", "PICKLEBALL_DOUBLES", RR, SON_TRA, PICKLE,
                    day(11, 7), 1, 6, H7, H11, 30, 250_000, 3_000_000,
                    "Vòng tròn 6 cặp đôi trong 5 buổi sáng."),
            new Tournament("Giải Cầu Lông Đơn Mở Rộng Phú Nhuận", "BADMINTON", "BADMINTON_SINGLES", RR, PHU_NHUAN, SHUTTLE,
                    day(11, 14), 1, 6, H13, H17, 40, 150_000, 2_500_000,
                    "Vòng tròn 6 tay vợt, xếp hạng theo số trận thắng rồi hiệu số điểm."),
            new Tournament("Cúp Bóng Đá 5 Người Cuối Năm 2026", "FOOTBALL", "FOOTBALL_5", KO, BINH_THANH, FIVE,
                    day(11, 22), 7, 8, H7, H11, 50, 700_000, 6_000_000,
                    "8 đội futsal loại trực tiếp qua 3 Chủ nhật cuối năm."),
            new Tournament("Giải Bóng Rổ 5x5 Tân Bình Mùa Đông", "BASKETBALL", "BASKETBALL_5X5", KO, TAN_BINH, HOOPS,
                    day(11, 28), 7, 8, H7, H11, 90, 800_000, 8_000_000,
                    "8 đội bóng rổ 5x5, 4 hiệp x 10 phút, đá mỗi thứ Bảy."),
            new Tournament("Giải Cầu Lông Đôi Cuối Năm 2026", "BADMINTON", "BADMINTON_DOUBLES", KO, PHU_NHUAN, SHUTTLE,
                    day(12, 5), 1, 16, H13, H17, 40, 200_000, 5_000_000,
                    "16 cặp đôi loại trực tiếp trong 4 buổi chiều đầu tháng 12."),
            new Tournament("Giải Bóng Đá 7 Người Chào Xuân 2027", "FOOTBALL", "FOOTBALL_7", RR, BINH_THANH, SEVEN,
                    day(12, 13), 3, 6, H7, H11, 60, 1_000_000, 12_000_000,
                    "Vòng tròn 6 đội sân 7 đón năm mới, đang mở đăng ký."),
            new Tournament("Giải Tennis Đôi Mỹ Đình Giáng Sinh", "TENNIS", "TENNIS_DOUBLES", KO, MY_DINH, TENNIS,
                    day(12, 19), 1, 8, H6, H11, 90, 500_000, 8_000_000,
                    "8 cặp đôi tennis tranh cúp Giáng Sinh, đang mở đăng ký.")
    );

    private DemoTournaments() { }

    static UUID uuid(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
