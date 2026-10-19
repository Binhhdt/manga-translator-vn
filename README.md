# Dịch Truyện VN — Manga Translator Android Offline

Ứng dụng Android dịch truyện tranh Trung / Nhật / Hàn / Anh sang tiếng Việt, chạy trên điện thoại:
**mở ảnh → Dịch → nhận dạng chữ → dịch sang tiếng Việt → che chữ gốc → chèn chữ Việt vào đúng khung → lưu ảnh.**

Miễn phí 100%: không API key, không tài khoản, không quảng cáo, không máy chủ, không giới hạn số trang.
OCR và dịch dùng Google ML Kit chạy trên máy. Chỉ lần đầu dịch mỗi ngôn ngữ cần Internet để tải mô hình (~30 MB).

> Phiên bản này là **Giai đoạn 1 (MVP)**. Xem mục *Lộ trình* bên dưới.

---

## 1. Lấy APK bằng GitHub Actions (không cần cài Android Studio)

1. Tạo một repository mới trên GitHub (Public hoặc Private đều được).
2. Đưa **toàn bộ** thư mục này lên repository (giữ nguyên cấu trúc, kể cả thư mục ẩn `.github` và `gradle/wrapper/gradle-wrapper.jar`).
   - Cách dễ nhất trên máy tính: GitHub Desktop, hoặc
     ```bash
     git init && git add . && git commit -m "Giai đoan 1"
     git branch -M main
     git remote add origin https://github.com/<tên-bạn>/<tên-repo>.git
     git push -u origin main
     ```
3. Mở tab **Actions** của repository → workflow **Build APK** tự chạy (hoặc bấm **Run workflow**). Mất khoảng 5–10 phút.
4. Khi có dấu ✓ xanh, bấm vào lần chạy đó → kéo xuống mục **Artifacts** → tải **MangaTranslateVN-debug-apk** (tệp .zip).
5. Giải nén, chép `app-debug.apk` vào điện thoại, mở để cài (cho phép "Cài ứng dụng không rõ nguồn gốc" khi được hỏi).

Lưu ý: APK debug được ký bằng khóa debug tạo ngẫu nhiên mỗi lần build trên GitHub, nên khi cài bản mới có thể phải **gỡ bản cũ trước**. Giai đoạn 5 sẽ thêm khóa ký cố định.

Nếu build lỗi: mở lần chạy bị đỏ, xem bước bị lỗi, tải artifact `test-reports` (nếu có) và gửi log để sửa.

## 2. Build bằng Android Studio

Mở thư mục dự án bằng Android Studio (Ladybug 2024.2 trở lên), chờ Gradle sync, chọn **Run ▶** hoặc
**Build › Build App Bundle(s)/APK(s) › Build APK(s)**. Cần JDK 17 (Android Studio có sẵn).

Phiên bản: Gradle 8.9 · Android Gradle Plugin 8.7.3 · Kotlin 2.0.21 · compileSdk/targetSdk 35 · minSdk 26 (Android 8.0).

## 3. Cách dùng (Giai đoạn 1)

1. **Mở ảnh** (một hoặc nhiều ảnh), **Mở thư mục truyện**, hoặc **Mở file CBZ**. Trang được sắp xếp tự nhiên 1, 2, 3 … 10.
2. Màn hình đọc: kéo để cuộn, chụm 2 ngón để phóng to, chạm đúp để phóng/thu, ◀ ▶ chuyển trang.
3. Nhấn **Dịch**. Lần đầu mỗi ngôn ngữ sẽ tải mô hình dịch.
4. **Bản gốc / Bản dịch** để so sánh trước – sau.
5. **Sửa chữ**: hiện khung các vùng chữ; chạm một khung để sửa chữ OCR, sửa bản dịch, dịch lại, ẩn hoặc xóa vùng. Ảnh vẽ lại ngay.
   Khung đỏ = bản dịch không vừa khung (chữ vẫn được vẽ đủ, không bị cắt) → hãy rút ngắn câu.
6. **Thêm vùng**: kéo một ngón tay khoanh vùng chữ mà OCR tự động bỏ sót / đọc sai (thường gặp với chữ dọc tiếng Nhật). Vùng được phóng to rồi OCR riêng.
7. **Lưu ảnh**: lưu ở độ phân giải gốc vào *Thư viện ảnh › Pictures › MangaTranslateVN › <tên truyện>*, tên tệp thêm `_vi`. Ảnh gốc không bao giờ bị ghi đè.
8. **Cài đặt**: ngôn ngữ nguồn (tự động/chọn tay), kiểu xưng hô (Ta/Ngươi, Tôi/Cậu, Anh/Em, Huynh/Muội, Bổn tọa/Các ngươi), màu chữ, viền, đậm, giãn dòng, JPG/PNG, tải trước mô hình dịch để dùng offline.

## 4. Kiến trúc

```
app/src/main/java/vn/mangatranslate/app/
├── core/
│   ├── model/        BoxF (tọa độ ảnh gốc), TextRegion, SourceLanguage, PronounStyle, RenderStyle
│   ├── image/        ImageLoader (SAF: ảnh, thư mục, CBZ; giải mã an toàn RAM), PreviewChunker (xem trước chia dải)
│   ├── ocr/          OCRProcessor (ML Kit v2, phóng chữ nhỏ, chia ô ảnh dài), TextRegionDetector (gom vùng thoại, thứ tự đọc, chữ dọc)
│   ├── translate/    TranslationEngine (ML Kit Translate + cache), PronounAdapter (đổi xưng hô)
│   ├── render/       VietnameseTextRenderer (che nền, tự xuống dòng, tự cỡ chữ, viền, cảnh báo tràn)
│   ├── export/       ImageExporter (MediaStore / thư mục Pictures cho Android 8–9)
│   ├── pipeline/     PageTranslator (OCR → gom vùng → dịch; vùng khoanh tay; đoán ngôn ngữ)
│   └── util/         NaturalOrderComparator, TextNormalizer
├── data/             SettingsStore + AppSettings
└── ui/               MainActivity, MainViewModel (MVVM, coroutines), màn hình Home / Reader / Settings, ZoomableMangaCanvas, EditRegionDialog
```

Nguyên tắc bộ nhớ (máy RAM 4 GB): chỉ giữ ảnh gốc độ phân giải đầy đủ của **một** trang; ảnh > 30 MP mới bị giảm
độ phân giải (có thông báo); bản xem trước được thu nhỏ và cắt dải ≤ 2048 px; ảnh tạm được giải phóng ngay;
mọi xử lý nặng chạy trên `Dispatchers.Default/IO`, giao diện không bị khóa; có nút **Hủy**.

Các thành phần `TextMaskGenerator`, `InpaintingProcessor`, `BatchTranslationManager`, `ProjectRepository` (Room)
**chưa có trong Giai đoạn 1** — chúng được viết thật ở các giai đoạn sau, không tạo hàm rỗng giả.

## 5. Giới hạn hiện tại (nói thẳng)

- **Chất lượng dịch**: ML Kit dịch từng câu, không hiểu ngữ cảnh, không biết ai nói với ai. Trung/Nhật/Hàn → Việt đi qua tiếng Anh trung gian nên kém tự nhiên hơn Anh → Việt. Đổi xưng hô chỉ là thay thế theo quy tắc. Hãy coi bản dịch là bản nháp để sửa.
- **OCR**: ML Kit đọc tốt chữ in ngang. Chữ **dọc tiếng Nhật**, chữ cách điệu, chữ viết tay, hiệu ứng âm thanh thường đọc sai hoặc bỏ sót → dùng *Thêm vùng* và sửa tay.
- **Gom bong bóng** dựa trên khoảng cách hình học giữa các khối chữ, không nhận diện hình bong bóng — đôi khi gộp/tách sai, sửa bằng *Sửa chữ*.
- **Xóa chữ gốc**: Giai đoạn 1 phủ một khối màu nền (màu lấy từ viền vùng chữ). Đẹp với bong bóng trắng/màu trơn; trên nền tranh phức tạp sẽ lộ mảng màu → Giai đoạn 2 (mask nét chữ + OpenCV inpainting).
- **Kết quả dịch chỉ giữ trong bộ nhớ** khi ứng dụng đang mở; ảnh đã lưu thì giữ vĩnh viễn. Lưu/mở lại dự án có ở Giai đoạn 3.
- Font dùng sans-serif hệ thống (Roboto/Noto) — hỗ trợ đầy đủ dấu tiếng Việt; font phong cách truyện tranh sẽ thêm sau.
- Chưa đo tốc độ trên thiết bị thật nên không cam kết thời gian xử lý.

## 6. Kiểm thử

**Đã chạy**: 18 unit test cho phần logic thuần (`app/src/test/`) — sắp xếp tự nhiên, gom vùng thoại, đọc chữ dọc phải→trái,
lọc nhiễu, thứ tự đọc manga, chuẩn hóa chữ IN HOA tiếng Anh, nối dòng CJK/Latin/Hàn, đổi xưng hô. Các test này chạy
trên GitHub Actions trước khi build APK (`./gradlew testDebugUnitTest`).

**Chưa chạy được** (cần điện thoại thật + ảnh truyện): OCR/dịch thật, vẽ chữ, lưu ảnh. Hãy kiểm tra theo bảng:

| # | Tình huống | Cách kiểm tra | Kỳ vọng |
|---|---|---|---|
| 1 | Một bong bóng | Mở 1 trang có 1 câu thoại → Dịch | 1 khung, chữ Việt nằm giữa bong bóng |
| 2 | Nhiều bong bóng | Trang 5–10 câu thoại | Mỗi bong bóng một vùng; thứ tự hợp lý |
| 3 | Tiếng Trung | Manhua, ngôn ngữ = Tự động | Nhận "Tiếng Trung", dịch được |
| 4 | Tiếng Nhật | Manga chữ ngang và chữ dọc | Chữ ngang tốt; chữ dọc có thể sai → *Thêm vùng* |
| 5 | Tiếng Hàn | Manhwa | Nhận "Tiếng Hàn" |
| 6 | Nền trắng | Bong bóng trắng | Chữ gốc bị che sạch |
| 7 | Nền phức tạp | Chữ đè lên tranh | Lộ mảng màu (giới hạn đã biết, Giai đoạn 2) |
| 8 | Ảnh dài | Manhwa dải 720×15000 | Mở được, cuộn mượt, OCR đủ các đoạn |
| 9 | 100 trang | — | Giai đoạn 3 |
| 10 | Lưu/mở lại dự án | — | Giai đoạn 3 |
| 11 | Font tiếng Việt | Lưu ảnh có "Người, Được, Ở, Ữ, Ỵ" → mở trong Thư viện | Dấu hiển thị đúng, không bị cắt |

## 7. Lộ trình

- **Giai đoạn 1 (bản này)**: mở ảnh/thư mục/CBZ, OCR, dịch, chèn chữ Việt, sửa chữ, khoanh vùng thủ công, lưu ảnh.
- **Giai đoạn 2**: TextMaskGenerator (tách nét chữ khỏi nền bằng ngưỡng thích nghi), OpenCV inpainting (Telea/Navier-Stokes), công cụ sửa mask, hoàn tác, xem trước.
- **Giai đoạn 3**: BatchTranslationManager (WorkManager, tạm dừng/tiếp tục/hủy, dịch lại trang lỗi, lưu tiến trình), Room lưu dự án, xuất cả chương JPG/PNG/CBZ.
- **Giai đoạn 4**: kéo thả/đổi cỡ/xoay khung chữ, chọn font, bảng thuật ngữ & tên nhân vật theo từng bộ truyện, undo/redo.
- **Giai đoạn 5**: tối ưu RAM, xử lý lỗi, khóa ký cố định, APK release.
