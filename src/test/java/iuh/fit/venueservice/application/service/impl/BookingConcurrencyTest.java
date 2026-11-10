package iuh.fit.venueservice.application.service.impl;

import iuh.fit.venueservice.application.dto.request.venue.CreateBookingCommand;
import iuh.fit.venueservice.application.exception.ServiceException;
import iuh.fit.venueservice.application.port.BookingLifecyclePort;
import iuh.fit.venueservice.application.service.BookingService;
import iuh.fit.venueservice.domain.enums.BookingSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Bằng chứng NFR-02 (zero overbooking): N người bấm đặt cùng một khung giờ đúng cùng lúc trên PostgreSQL thật.
 * Chỉ một người được giữ chỗ; những người còn lại bị chặn ở SELECT ... FOR UPDATE rồi thấy slot đã LOCKED.
 *
 * Lệnh mở process Camunda (gọi workflow-service qua HTTP, nằm TRONG transaction) được giả lập chậm 200 ms để
 * cửa sổ tranh chấp rộng như production: bỏ khóa hàng thì mọi luồng đều đọc thấy AVAILABLE và test này đỏ.
 */
@SpringBootTest(properties = {
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BookingConcurrencyTest {

    private static final int CONCURRENT_PLAYERS = 20;
    private static final LocalTime START = LocalTime.of(18, 0);
    private static final LocalTime END = LocalTime.of(19, 0);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Giống VenueServiceApplication.main: Windows báo "Asia/Saigon", PostgreSQL 16 không nhận tên cũ đó.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> CONCURRENT_PLAYERS + 2);
    }

    @Autowired private BookingService bookingService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private BookingLifecyclePort bookingLifecyclePort;

    @Test
    void onlyOneOfManySimultaneousBookingsForTheSameSlotWins() throws Exception {
        when(bookingLifecyclePort.start(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return 1L;
        });
        UUID courtId = seedCourtWithOneAvailableSlot();
        LocalDate playDate = LocalDate.now().plusDays(1);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_PLAYERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_PLAYERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_PLAYERS; i++) {
            CreateBookingCommand command = new CreateBookingCommand(
                    UUID.randomUUID(), courtId, playDate, START, END, BookingSource.DIRECT, null, null, null);
            Callable<String> attempt = () -> {
                ready.countDown();
                go.await();
                try {
                    bookingService.createBooking(command);
                    return "OK";
                } catch (ServiceException e) {
                    return e.getMessage();
                }
            };
            outcomes.add(pool.submit(attempt));
        }
        ready.await();
        long startedAt = System.nanoTime();
        go.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        pool.shutdown();

        long winners = results.stream().filter("OK"::equals).count();
        List<String> rejections = results.stream().filter(result -> !"OK".equals(result)).toList();
        Integer bookingsInDb = jdbc.queryForObject(
                "select count(*) from bookings where venue_court_id = ? and play_date = ?",
                Integer.class, courtId, playDate);
        String slotStatus = jdbc.queryForObject(
                "select status from time_slots where venue_court_id = ? and date = ?",
                String.class, courtId, playDate);

        System.out.printf("[NFR-02] %d request đồng thời / 1 slot: %d thành công, %d bị từ chối, %d booking trong DB,"
                + " slot = %s, %d ms%n", CONCURRENT_PLAYERS, winners, rejections.size(), bookingsInDb, slotStatus, elapsedMs);

        assertThat(winners).isEqualTo(1);
        assertThat(rejections).hasSize(CONCURRENT_PLAYERS - 1).containsOnly("Khung giờ không còn khả dụng.");
        assertThat(bookingsInDb).isEqualTo(1);
        assertThat(slotStatus).isEqualTo("LOCKED");
    }

    private UUID seedCourtWithOneAvailableSlot() {
        UUID addressId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        UUID courtId = UUID.randomUUID();
        jdbc.update("insert into addresses (address_id, address) values (?, 'Q1')", addressId);
        jdbc.update("insert into venues (venue_id, name, owner_id, address_id, active) values (?, 'Concurrency', ?, ?, true)",
                venueId, UUID.randomUUID(), addressId);
        jdbc.update("insert into venue_courts (venue_court_id, name, sport_type, venue_id, active) values (?, 'A', 'BADMINTON', ?, true)",
                courtId, venueId);
        jdbc.update("""
                insert into time_slots (time_slot_id, venue_court_id, date, start_time, end_time, price_per_hour, status)
                values (?, ?, ?, ?, ?, 100000, 'AVAILABLE')
                """, UUID.randomUUID(), courtId, LocalDate.now().plusDays(1), START, END);
        return courtId;
    }
}
