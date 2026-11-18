# GoatSports Venue Service

Service lõi của nền tảng: cơ sở thể thao, sân con, khung giờ, đặt sân, check-in, đánh giá và hồ sơ chủ sân.

## Chức năng chính

- **Cơ sở và sân**: chủ sân tạo cơ sở, sân con, giá theo khung giờ, giờ mở cửa, ảnh.
- **Tìm sân**: lọc theo môn, giá, đánh giá, bán kính, khu vực; cache kết quả bằng Caffeine; tìm kiếm ngữ nghĩa
  qua ai-service (tự quay về tìm theo từ khóa khi AI lỗi). Geocoding qua Nominatim / Vietmap.
- **Đặt sân không trùng**: khóa dòng khung giờ (`PESSIMISTIC_WRITE` / `SELECT … FOR UPDATE`), giữ chỗ có thời hạn
  (`BookingHoldExpirationJob`), đặt cọc qua payment-service; tiền về sau khi hết giờ giữ chỗ thì tự hoàn tiền.
- **Vé QR và check-in**: vé ký HMAC (`CHECK_IN_QR_SECRET`), chủ sân quét để check-in trong cửa sổ cho phép.
- **Hủy sân và hoàn tiền**: yêu cầu hủy chạy trên quy trình Camunda (workflow-service). Trạng thái hủy trong DB
  chỉ là bản chiếu do workflow ghi xuống qua `/internal/booking-cancellations`.
- **Vòng đời đặt sân**: hoàn thành tự động, không đến (`BookingAutoCompletionJob`, `BookingNoShowJob`) và
  đồng bộ với quy trình `booking-lifecycle` bên workflow-service.
- **Đánh giá**: mỗi đơn một đánh giá, kiểm duyệt, AI gợi ý phân loại (`ReviewAiTriageJob`, chỉ gợi ý).
- **Hồ sơ chủ sân**: nộp giấy tờ, AI xác minh căn cước, admin duyệt.
- **Sân ngoài hệ thống (cold-start)**: nhập sân từ OpenStreetMap (Overpass), người chơi gợi ý sân và bấm "muốn đặt online",
  admin mời chủ sân và chủ sân nhận (claim) sân đó.
- **Giữ sân cho giải đấu** (`/internal/tournament-court-holds`) và đặt sân cho kèo ghép trận (`/internal/matchmaking/bookings`).

## API chính

| Nhóm | Đường dẫn |
|---|---|
| Người chơi | `/api/v1/venues`, `/api/v1/venue-courts`, `/api/v1/bookings`, `/api/v1/player/reviews`, `/api/v1/venues/external` |
| Chủ sân | `/api/v1/owner/venues`, `/owner/bookings`, `/owner/check-ins`, `/owner/revenue`, `/owner/reviews`, `/api/v1/owner-applications` |
| Quản trị | `/api/v1/admin/venues`, `/admin/bookings`, `/admin/reviews`, `/admin/owner-applications`, `/admin/external-venues`, `/admin/stats` |
| Nội bộ | `/internal/bookings`, `/internal/booking-cancellations`, `/internal/booking-lifecycles`, `/internal/owner-revenue`, … |

## Cấu trúc

```
src/main/java/iuh/fit/venueservice/
├── domain/          # entity, enum, quy tắc thuần (vd. CancellationPolicy)
├── application/     # service theo use case, port, DTO, mapper
├── infrastructure/  # JPA, Kafka, Feign, geocoding, job định kỳ, demo seeder
└── presentation/    # REST controller
shared-kernel/       # @AuditLog, Feign interceptor dùng chung
```

## Tích hợp

- **PostgreSQL** (Liquibase), **Caffeine** cache.
- **Kafka nhận**: `payment.events`, `user-deleted`, `file.events.move-completed`. **Kafka phát**: `user-activity` (cho cá nhân hóa), `user-log`.
- **Feign**: ai-service (tìm kiếm ngữ nghĩa, xác minh căn cước, kiểm duyệt, đồng bộ ghép trận), auth, payment,
  notification, storage, workflow.

## Chạy và kiểm thử

```bash
mvn -f shared-kernel/pom.xml clean install -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # cần application-dev.properties (bị .gitignore)
mvn test
```

Bật `DEMO_DATA_ENABLED=true` (và `DEMO_SIMULATION_ENABLED=true`) để có sân, đơn đặt mẫu.

## CI/CD

`.gitlab-ci.yml`: test → `jib:build` lên Docker Hub → gọi Jenkins job `GoatSports-CD`.
