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
4. Khi có dấu ✓ xanh, vào trang repo → mục **Releases** (cột bên phải) → bản **"APK debug mới nhất"** → tải **MangaTranslateVN-debug.apk**.
   (Có thể mở trực tiếp trên điện thoại khi đã đăng nhập GitHub; mỗi lần build bản này được thay bằng bản mới.)
   Đường phụ: trong lần chạy Actions, mục **Artifacts** — chỉ có khi tài khoản còn hạn mức lưu trữ Artifacts.
5. Mở tệp APK để cài (cho phép "Cài ứng dụng không rõ nguồn gốc" khi được hỏi).

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
│   ├── vision/       BubbleDetector (tìm bong bóng khép kín), TextLayoutAnalyzer (tách cột dọc, phát hiện furigana),
│   │                 VerticalComposer (xếp chữ dọc thành hàng ngang), WhiteSpaceExpander, TextBlobFinder — thuần Kotlin
│   ├── ocr/          OCRProcessor (ML Kit v2), RegionOcr (OCR từng vùng nhiều cách + chấm điểm), RubyFilter (bỏ furigana),
│   │                 OcrCandidateScorer, TextRegionDetector (gom vùng ngoài bong bóng, thứ tự đọc)
│   ├── translate/    TranslationEngine (ML Kit + cache), Glossary (thuật ngữ/tên riêng), QualityChecker ("Cần kiểm tra"), PronounAdapter
│   ├── render/       VietnameseTextRenderer (xóa chữ trong bong bóng trắng, lớp phủ, đặt chữ trong bong bóng), FontSizing
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

## 5. Quy trình OCR & dịch (bản nâng cấp 0.1.1)

1. OCR toàn trang bằng ML Kit để biết chỗ có chữ.
2. Tìm bong bóng khép kín (vùng trắng có viền); mỗi dòng chữ được gán vào đúng bong bóng chứa nó → **không ghép chữ của hai bong bóng**.
3. Mỗi bong bóng được cắt riêng (tô trắng phần ngoài), rồi OCR bằng nhiều cách: *đọc riêng vùng* (phóng to), *tách cột dọc*
   (tách cột phải→trái, bỏ cột furigana, xếp ký tự thành hàng ngang giữ nguyên chiều đứng), *ảnh đen trắng* (khi kết quả yếu).
   Chọn cách có điểm cao nhất: đúng loại chữ của ngôn ngữ nguồn, khớp số ô ký tự đếm được trên ảnh. **Không xoay cả ảnh 90°**
   (ký tự nằm nghiêng làm ML Kit đọc kém hơn).
4. Chữ ngoài bong bóng: bỏ furigana rồi gom theo khoảng cách, cũng OCR lại từng vùng; vùng đặt chữ Việt = khoảng trắng quanh chữ.
5. Nhật/Trung/Hàn: dò cụm nét giống chữ mà OCR toàn trang bỏ sót, đọc lại, gắn nhãn **Cần kiểm tra**.
6. Dịch **cả nội dung một bong bóng một lần**; thay thuật ngữ trước/sau dịch; viết tắt bóng đá (GK, FW, MF, DF, PK, FK, CK)
   được đổi sang dạng đầy đủ để máy dịch hiểu đúng (GK → thủ môn).
7. Gắn nhãn **Cần kiểm tra** khi: OCR có ký tự lạ, các cách đọc OCR mâu thuẫn, bản dịch còn chữ chưa dịch, có từ không phải
   tiếng Việt (ví dụ "buzzma", "ucking"), hoặc thuật ngữ không được giữ. Không có điểm tin cậy giả.

### So sánh bộ dịch miễn phí chạy trên máy

| Phương án | Nhật → Việt | Kích thước / RAM | Giấy phép | Đánh giá |
|---|---|---|---|---|
| **ML Kit Translate** (đang dùng) | Qua tiếng Anh trung gian (Google ghi rõ có thể ảnh hưởng chất lượng) | ~30 MB/ngôn ngữ | Miễn phí | Nhẹ, ổn định; chất lượng "dùng tạm" |
| Opus-MT ja-vi (Helsinki-NLP) | Dịch thẳng | Vài trăm MB, cần ONNX Runtime + SentencePiece | Apache-2.0 | BLEU 20.3 trên Tatoeba — chưa chắc hơn ML Kit, cần thử thực tế |
| NLLB-200 distilled 600M | Dịch thẳng | 600 triệu tham số, nặng với máy 4 GB | CC-BY-NC (phi thương mại), "không dành cho triển khai thực tế" | Không đề xuất |
| LLM nhỏ (1–2 tỷ tham số) | Tốt hơn về văn phong | ≥ 1 GB, chậm trên máy tầm trung | Tùy mô hình | Không đề xuất cho máy 4 GB |

Kết luận: **giữ ML Kit**, nâng chất lượng bằng OCR đúng (nguyên nhân chính của câu vô nghĩa), bảng thuật ngữ và sửa tay.
OCR thay thế đáng cân nhắc tiếp theo: **manga-ocr** (Apache-2.0, chuyên chữ dọc + furigana manga, bản lượng tử hóa cho Android
khoảng 130 MB, tải tùy chọn) — xem mục Lộ trình.

## 5a. Bộ dịch tiếng Nhật (0.2.0) — chọn theo số liệu đo thật

Đo trên trình giả lập Android (CI) với ML Kit thật, trang 006:
- ML Kit dịch Nhật→Việt **qua tiếng Anh** (kết quả trùng 13/13 câu với Nhật→Anh→Việt), và bước Nhật→Anh là chỗ sai:
  黒崎 → "Blacklord", 腕を骨折 → "Arms fracture" → "Giũa vũ khí".
- So sánh 6 bộ dịch offline miễn phí: **Opus-MT Nhật→Anh** (Helsinki-NLP, Apache-2.0) hiểu tiếng Nhật tốt nhất
  ("Kurosaki", "I broke my arm…"). Opus-MT Nhật→Việt trực tiếp, M2M100, NLLB-200, Qwen2.5 0.5B/1.5B đều kém hơn hoặc quá nặng.
- Vì vậy: **Opus-MT Nhật→Anh chạy trong ứng dụng** (ONNX Runtime, int8 ~140 MB, beam search 4) → giữ tên romaji
  bằng dấu QQn → **ML Kit Anh→Việt**. Có thể chọn "Chỉ ML Kit" trong Cài đặt; màn hình "Kiểm tra bộ dịch" so sánh hai cách.
- Bản Kotlin được đối chiếu với bản Python tham chiếu trên thiết bị: tách từ khớp 12/12 câu.
- Giới hạn còn lại: Opus-MT vẫn có thể dịch sai câu dài/khẩu ngữ; ML Kit Anh→Việt văn phong cứng ("phá vỡ cánh tay").
  Địa danh bị dịch nghĩa từng chữ được phát hiện và đánh dấu "Cần kiểm tra".

OCR chữ dọc: ML Kit đọc chữ dọc theo hàng ngang cắt qua các cột (ký tự bị trộn). Nay hướng chữ được xác định bằng
phân tích ảnh, ưu tiên cách tách cột; bản đọc toàn trang chỉ dùng để định vị.

## 5b. Sự cố bản 0.1.2: xóa mất hình vẽ — nguyên nhân và cách sửa (0.1.3)

- **Nguyên nhân**: bong bóng hình sao hở thông với nền trắng của cả khung tranh; bộ tìm bong bóng coi cả khung tranh
  (23% trang) là "bong bóng", bước xóa chữ tô trắng mọi thứ bên trong — gồm cầu thủ, sân, khung thành.
- **Sửa**:
  1. Bong bóng tối đa 6% trang, không quá nửa chiều rộng/cao trang; vùng trắng lớn chạm mép bị loại;
     vùng chứa mảng nét lớn (hình vẽ) KHÔNG bao giờ được coi là bong bóng.
  2. Xóa chữ **mặc định TẮT**. Khi bật: mask chỉ nằm trong khung chữ thực tế (nới 0,6 cỡ chữ), mask bất thường bị từ chối.
  3. Lớp phủ trong bong bóng chỉ tô điểm bên trong bong bóng; vùng chữ ngoài bong bóng nới tối đa 1 cỡ chữ.
  4. Sau mỗi lần vẽ, ảnh xử lý được so với ảnh gốc: điểm ảnh đổi ngoài vùng cho phép được báo ngay.
  5. Chế độ DEBUG tô đỏ mask, viền xanh vùng đặt chữ.
  6. Ảnh gốc không bao giờ bị sửa (luôn vẽ trên bản sao); "Bản gốc" luôn hiện ảnh nguyên vẹn.
- **Bản dịch đáng ngờ** (từ lạ, lặp từ, số lạ, OCR lặp chữ…) mặc định KHÔNG được vẽ lên ảnh cho tới khi người dùng
  kiểm tra và bấm "Lưu bản dịch". Vùng không đủ chỗ cũng không vẽ (không tràn khỏi khung thoại).

## 6. Giới hạn hiện tại (nói thẳng)

- **Chất lượng dịch**: ML Kit dịch từng câu, không hiểu ngữ cảnh, không biết ai nói với ai. Trung/Nhật/Hàn → Việt đi qua tiếng Anh trung gian nên kém tự nhiên hơn Anh → Việt. Đổi xưng hô chỉ là thay thế theo quy tắc. Hãy coi bản dịch là bản nháp để sửa.
- **OCR chữ dọc**: đã cải thiện bằng tách cột + bỏ furigana, nhưng ML Kit vẫn không phải OCR chuyên manga. Chữ cách điệu, viết tay, hiệu ứng âm thanh vẫn có thể sai → xem nhãn *Cần kiểm tra*, dùng *Thêm vùng* và *Sửa chữ Nhật*.
- **Bong bóng hở** (tràn qua khung tranh, dính lề trang, hình sao thông ra nền) không tách được bằng viền → dùng khoảng trắng quanh chữ + lớp phủ.
- **Xóa chữ gốc**: xóa sạch trong bong bóng trắng khép kín; còn lại dùng lớp phủ sáng (độ che chỉnh trong Cài đặt). Inpainting trên nền tranh: Giai đoạn 2.
- **Kết quả dịch chỉ giữ trong bộ nhớ** khi ứng dụng đang mở; ảnh đã lưu thì giữ vĩnh viễn. Lưu/mở lại dự án có ở Giai đoạn 3.
- Font dùng sans-serif hệ thống (Roboto/Noto) — hỗ trợ đầy đủ dấu tiếng Việt; font phong cách truyện tranh sẽ thêm sau.
- Chưa đo tốc độ trên thiết bị thật nên không cam kết thời gian xử lý.

## 7. Kiểm thử

**Đã chạy**: 77 unit test cho phần logic thuần (`app/src/test/`), chạy trên GitHub Actions trước khi build APK:
tìm bong bóng trên trang giả lập, chữ của hai bong bóng không bị ghép, viền bong bóng không bị coi là chữ, thứ tự cột dọc
phải→trái, phát hiện furigana, ký tự nhiều nét (二) đếm là một ô, xếp cột thành hàng, khoảng trắng dừng ở viền bong bóng,
chấm điểm OCR (furigana lẫn vào bị trừ điểm), phát hiện từ lạ trong chính các câu lỗi từ ảnh thử ("buzzma", "ucking"),
chữ chưa dịch, bảng thuật ngữ, GK → ゴールキーパー, cỡ chữ không bị tí hon.
Thuật toán tìm bong bóng / tách cột / dò chữ bỏ sót còn được chạy thử trên ảnh chụp màn hình thật.

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

## 8. Lộ trình

- **Giai đoạn 1 (bản này)**: mở ảnh/thư mục/CBZ, OCR theo bong bóng, dịch, chèn chữ Việt, kiểm tra/sửa OCR, khoanh vùng thủ công, lưu ảnh.
- **Đề xuất 1.5**: tích hợp manga-ocr (tải tùy chọn) làm bộ đọc chữ Nhật thứ hai cho từng bong bóng, chọn theo cùng cơ chế chấm điểm.
- **Giai đoạn 2**: TextMaskGenerator (tách nét chữ khỏi nền bằng ngưỡng thích nghi), OpenCV inpainting (Telea/Navier-Stokes), công cụ sửa mask, hoàn tác, xem trước.
- **Giai đoạn 3**: BatchTranslationManager (WorkManager, tạm dừng/tiếp tục/hủy, dịch lại trang lỗi, lưu tiến trình), Room lưu dự án, xuất cả chương JPG/PNG/CBZ.
- **Giai đoạn 4**: kéo thả/đổi cỡ/xoay khung chữ, chọn font, bảng thuật ngữ & tên nhân vật theo từng bộ truyện, undo/redo.
- **Giai đoạn 5**: tối ưu RAM, xử lý lỗi, khóa ký cố định, APK release.
