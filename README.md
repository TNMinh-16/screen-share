# Real-Time LAN Screen Share (Android -> Windows)

Hệ thống truyền màn hình điện thoại Android lên máy tính Windows theo thời gian thực trong cùng mạng Wi-Fi (LAN) với độ trễ siêu thấp và hình ảnh sắc nét.

---

## 1. Kiến trúc hệ thống & Luồng dữ liệu

```
[Điện thoại Android (Client)]
MediaProjection -> VirtualDisplay -> MediaCodec (H.264 Baseline, CBR) -> TCP Client Socket
                                      │
                         (Mạng Wi-Fi nội bộ - Cổng 8554)
                                      ▼
[Máy tính Windows (Server)]
TCP Server Socket -> Queue -> PyAV (H.264 Low Delay Decoder) -> PySide6 GUI (Hiển thị)
```

---

## 2. Đặc tả Giao thức truyền tin (Wire Protocol)

Mỗi gói tin truyền qua TCP socket có cấu trúc nhị phân (Big-Endian):

```
+---------------------------------------+---------------------+-------------------------------+
|  Payload Length (4 byte, uint32_be)   | Packet Type (1 byte)|  Payload Data (N bytes)       |
+---------------------------------------+---------------------+-------------------------------+
```

> **QUY ƯỚC QUAN TRỌNG:**
> - Trường **Payload Length** (4 byte đầu) **CHỈ TÍNH ĐỘ DÀI CỦA PAYLOAD DATA**, **KHÔNG** bao gồm byte Loại gói (Packet Type).
> - Tổng số byte của một gói tin trên đường truyền mạng là: `4 + 1 + Payload Length`.
> - Kích hoạt cờ `TCP_NODELAY` (tắt thuật toán Nagle) ở cả 2 đầu kết nối để dữ liệu được gửi ngay lập tức.

### Các loại gói tin (Packet Type):

#### 1. Chiều Điện thoại → Máy tính (Video & Trạng thái):
| Loại (Hex/Dec) | Tên gói | Cấu trúc Payload Data | Mô tả |
| :--- | :--- | :--- | :--- |
| **0 (`0x00`)** | **Gói Cấu hình (Config)** | `[4B Width] + [4B Height] + [Dữ liệu SPS/PPS]` | Chứa kích thước màn hình và thông số giải mã H.264 (SPS/PPS). Gửi đầu tiên khi kết nối, gửi lại trước mỗi Keyframe và khi xoay màn hình. |
| **1 (`0x01`)** | **Khung hình chính (Keyframe / I-frame)** | `[Dữ liệu NAL units của IDR Keyframe]` | Khung hình độc lập. Phía nhận chỉ bắt đầu hiển thị sau khi đã nhận gói Loại 0 và gói Loại 1 đầu tiên. |
| **2 (`0x02`)** | **Khung hình thường (Delta / P-frame)** | `[Dữ liệu NAL units của P-frame]` | Khung hình vi phân dựa trên khung hình trước. |
| **3 (`0x03`)** | **Trạng thái điều khiển (CONTROL_STATE)** | `[1 byte cờ (flags)]` | Bit0 (`0x01`): Công tắc cho phép điều khiển đang bật. Bit1 (`0x02`): Dịch vụ Trợ năng đang kết nối. Gửi ngay khi kết nối và mỗi khi trạng thái đổi. |

#### 2. Chiều Máy tính → Điện thoại (Lệnh điều khiển - Tọa độ chuẩn hóa uint16 0..65535):
| Loại (Hex) | Tên lệnh | Cấu trúc Payload Data | Mô tả |
| :--- | :--- | :--- | :--- |
| **`0x10`** | **TOUCH_DOWN** | `[2B x (u16)] + [2B y (u16)]` | Nhấn chuột trái (bắt đầu ngón tay chạm màn hình). |
| **`0x11`** | **TOUCH_MOVE** | `[2B x (u16)] + [2B y (u16)]` | Rê chuột trái (ngón tay di chuyển theo thời gian thực ~60Hz, tự gộp lệnh chờ). |
| **`0x12`** | **TOUCH_UP** | `[2B x (u16)] + [2B y (u16)]` | Nhả chuột trái (kết thúc nét chạm / vuốt). |
| **`0x13`** | **SCROLL** | `[2B x (u16)] + [2B y (u16)] + [2B dx (i16)] + [2B dy (i16)]` | Cuộn con lăn chuột (120 = 1 nấc con lăn Qt, tự động đổi thành cử chỉ vuốt). |
| **`0x14`** | **PINCH** | `[2B x (u16)] + [2B y (u16)] + [1B dir (i8)]` | Thu phóng 2 ngón tay (Ctrl + con lăn: +1 phóng to, -1 thu nhỏ). |
| **`0x15`** | **TEXT** | `[Chuỗi UTF-8 (N bytes)]` | Gõ văn bản có dấu tại con trỏ (hỗ trợ Unikey và Telex Windows). |
| **`0x16`** | **KEY** | `[1 byte keyCode]` | Phím điều hướng & biên tập (1: Backspace, 2: Delete, 3: Left, 4: Right, 5: Home, 6: End, 7: Enter, 8: Select All). |
| **`0x17`** | **NAV** | `[1 byte navCode]` | Phím hệ thống Android (1: Back / Chuột phải, 2: Home / Chuột giữa, 3: Recents). |

---

## 3. Quy tắc giải mã và triệt tiêu độ trễ phía PC (Windows)

1. **Không drop gói H.264 tùy tiện:** Luôn giải mã đầy đủ mọi frame hợp lệ để duy trì chuỗi tham chiếu. Chỉ bỏ qua việc vẽ các khung cũ trên UI, luôn vẽ khung mới nhất.
2. **Xử lý khi giật lag nặng:** Nếu hàng đợi mạng dồn quá ngưỡng (> 50 gói ở 60 FPS do mạng chập chờn hoặc máy bận), decoder chuyển sang trạng thái bỏ qua các khung thường (P-frame) và nhảy thẳng đến Keyframe tiếp theo để khôi phục ngay độ trễ thấp nhất mà không bị vỡ hình.
3. **Đồng bộ bắt đầu:** Bộ giải mã chỉ xuất hình sau khi nhận đủ gói cấu hình (Type 0) và Keyframe đầu tiên (Type 1).

### Cấu hình hiệu năng Video (60 FPS vs 30 FPS)

- **Thông số mặc định hiện tại (60 FPS siêu mượt):**
  - **Tốc độ khung hình (FRAME_RATE):** `60 FPS` (file `VideoConfig.kt`)
  - **Bitrate (BIT_RATE):** `8 Mbps` (`8_000_000 bps`, file `VideoConfig.kt`)
  - **H.264 Level:** `AVCLevel4` (file `ScreenEncoder.kt`, hỗ trợ 720p tại 60 FPS)
  - **Ngưỡng chống lag (LAG_PACKET_THRESHOLD):** `50 gói` (file `pc/decoder.py`)

- **Cách quay lại chế độ 30 FPS (nếu điện thoại nóng máy, tốn pin hoặc mạng Wi-Fi yếu):**
  1. Mở file `android/app/src/main/java/com/minh/screenshare/config/VideoConfig.kt`:
     - Đổi `FRAME_RATE = 30`
     - Đổi `BIT_RATE = 5_000_000` (5 Mbps)
  2. Mở file `android/app/src/main/java/com/minh/screenshare/encoder/ScreenEncoder.kt`:
     - Đổi `AVCLevel4` thành `MediaCodecInfo.CodecProfileLevel.AVCLevel31`
  3. Mở file `pc/decoder.py`:
     - Đổi `LAG_PACKET_THRESHOLD = 25`
  4. Build lại APK debug bằng lệnh: `.\gradlew.bat assembleDebug`

---

## 4. Hướng dẫn chạy và Kiểm thử Bước 1 (Trên Windows)

### Bước 1.1: Cài đặt môi trường Python
Yêu cầu Python 3.10 trở lên (khuyên dùng Python 3.11).

```bash
cd pc
pip install -r requirements.txt
```

### Bước 1.2: Chạy phần mềm nhận màn hình trên Windows
```bash
python main.py
```
- Cửa sổ ứng dụng sẽ mở lên.
- Bạn sẽ thấy địa chỉ **IP của máy tính** (ví dụ: `192.168.1.15`) và **Cổng** `8554` hiển thị ngay trên thanh trạng thái.
- Trạng thái ban đầu: `Đang chờ kết nối...`

### Bước 1.3: Chạy script giả lập để kiểm tra (Mock Sender)
Mở một cửa sổ Terminal (PowerShell / Command Prompt) thứ hai:

```bash
cd pc
python mock_sender.py
```

**Kết quả quan sát được:**
- Cửa sổ Windows lập tức chuyển sang trạng thái: `Đã kết nối (127.0.0.1:xxxxx)`.
- Video test hiển thị ngay lập tức với đồng hồ đếm thời gian thực (chính xác đến mili-giây), thanh quét chuyển động mượt mà 30 FPS.
- Cột FPS hiển thị `~30.0`.
- Sau mỗi 12 giây, script tự động giả lập xoay màn hình (từ ngang 640x360 sang dọc 360x640), cửa sổ lập tức co giãn giữ đúng tỉ lệ khung hình.

---

## 5. Hướng dẫn chạy và Kiểm thử Bước 2 (Ứng dụng Android)

### Bước 2.1: Mở dự án trong Android Studio
1. Mở **Android Studio**.
2. Chọn **Open** và duyệt tới thư mục `android/` (đường dẫn: `c:\minh\app\truyen_anh\android`).
3. Đợi Android Studio đồng bộ Gradle (Sync Project with Gradle Files).

### Bước 2.2: Bật Gỡ lỗi USB trên điện thoại Oppo (ColorOS)
1. Mở **Cài đặt** (Settings) -> **Giới thiệu thiết bị** (About Device) -> **Phiên bản** (Version).
2. Chạm liên tục 7 lần vào dòng **Số bản dựng** (Build number) cho đến khi hiện thông báo *"Bạn đã là nhà phát triển"*.
3. Quay lại **Cài đặt** -> **Cài đặt bổ sung** (Additional settings) -> **Tùy chọn cho nhà phát triển** (Developer options).
4. Bật 2 mục sau:
   - **Gỡ lỗi USB** (USB debugging).
   - **Cài đặt qua USB** (Install via USB) và **Xác minh ứng dụng qua USB**.
5. Cắm cáp USB nối điện thoại với máy tính, khi điện thoại hiện hộp thoại hỏi *"Cho phép gỡ lỗi USB?"*, tích chọn *"Luôn cho phép từ máy tính này"* và bấm **Cho phép**.

### Bước 2.3: Build và Cài đặt file APK
**Cách 1 (Từ Android Studio):**
- Bấm nút **Run 'app'** (tam giác màu xanh) trên thanh công cụ để Android Studio tự động nạp app lên điện thoại.

**Cách 2 (Từ dòng lệnh Terminal / PowerShell):**
```powershell
cd android
# 1. Chạy Unit Test kiểm tra giao thức
.\gradlew.bat test

# 2. Build file APK Debug
.\gradlew.bat assembleDebug

# 3. Cài trực tiếp lên điện thoại qua ADB (nếu cắm cáp USB)
C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```
*(File APK đã build xong nằm tại: `android/app/build/outputs/apk/debug/app-debug.apk`)*

---

## 6. Lưu ý quan trọng cho môi trường mạng & thiết bị

1. **Tường lửa Windows (Windows Firewall):**
   - Khi chạy `python main.py` lần đầu, Windows có thể hiện popup hỏi quyền truy cập mạng. Hãy tích chọn **Private networks** và bấm **Allow access**.
   - Hoặc mở cổng 8554 thủ công trong Windows Defender Firewall.
2. **Tính năng cách ly mạng Wi-Fi (AP Isolation):**
   - Một số modem Wi-Fi bật tính năng *AP Isolation* (cách ly các thiết bị kết nối vào Wi-Fi không nhìn thấy nhau). Hãy kiểm tra router và tắt tính năng này nếu điện thoại không kết nối được tới IP máy tính.
3. **Cài đặt tiết kiệm pin trên Oppo (ColorOS):**
   - Điện thoại Oppo thường tự đóng ứng dụng chạy nền sau vài phút. Vào *Cài đặt* -> *Pin* -> *Quản lý pin cho ứng dụng* -> chọn App Screen Share -> bật *Cho phép hoạt động dưới nền* và *Tắt tối ưu hóa pin*.
4. **Màn hình bảo mật:**
   - Khi mở app ngân hàng hoặc màn hình nhập mật khẩu, Android sẽ tự động xuất khung đen để bảo vệ dữ liệu người dùng. Đây là chính sách an ninh của hệ điều hành.

---

---

## 7. Tính năng Điều khiển điện thoại từ máy tính (Remote Control)

Hệ thống cho phép bạn tương tác trực tiếp với điện thoại Oppo thông qua chuột và bàn phím máy tính trên cùng kết nối TCP hai chiều:

### 7.1. Các thao tác điều khiển được hỗ trợ:
- **Chuột trái:**
  - Nhấp chuột nhanh: Chạm (Tap / Click).
  - Nhấn giữ lâu: Chạm giữ (Long press).
  - Nhấn giữ và rê chuột: Kéo thả, lướt màn hình theo thời gian thực (Live Drag ~60Hz bám sát chuột). Tự động gộp lệnh (coalesce) để không tích tụ độ trễ.
- **Con lăn chuột:** Cuộn trang web / danh sách (Scroll dọc/ngang).
- **Ctrl + Con lăn chuột:** Phóng to / Thu nhỏ 2 ngón tay (Pinch Zoom) quanh tâm con trỏ chuột.
- **Chuột phải:** Quay lại (Back).
- **Chuột giữa:** Về màn hình chính (Home).
- **Thanh công cụ trên PC:** Các nút Back, Home, Recents (Đa nhiệm) và hộp chọn "Bật điều khiển".
- **Bàn phím máy tính (Gõ chữ & Phím tắt):**
  - Gõ văn bản tiếng Việt có dấu: Hỗ trợ cả **Unikey** (bộ mã Unicode dựng sẵn) lẫn **bộ gõ Telex mặc định của Windows** (thông qua IME commitString).
  - Phím xóa & điều hướng: Backspace, Delete, Mũi tên Trái / Phải, Home / End (chuyển con trỏ về đầu/cuối dòng).
  - Phím tắt biên tập: `Ctrl + A` (chọn tất cả văn bản trong ô).
  - Phím `Enter`: Chèn ký tự xuống dòng (`\n`).

### 7.2. Chính sách Bảo mật & Quyền riêng tư:
- **Mặc định TẮT:** Tính năng điều khiển luôn ở trạng thái tắt khi mới khởi động hoặc khi ngắt kết nối.
- **Điều kiện kép:** Điều khiển chỉ thực sự hoạt động khi:
  1. Người dùng bật công tắc *"Cho phép điều khiển từ máy tính"* trong app Android.
  2. Dịch vụ Trợ năng (*Remote Control Accessibility Service*) đã được kích hoạt trong Cài đặt hệ thống.
- **Tự động ngắt:** Mỗi khi phiên truyền dừng lại, công tắc điều khiển tự động trở về TẮT.
- **Thông báo rõ ràng:** Khi đang cho phép điều khiển, Notification của app hiển thị: *"Đang cho phép điều khiển từ máy tính"*.
- **Tuyệt đối an toàn:** Không bao giờ ghi nội dung văn bản người dùng gõ vào file log (chỉ log độ dài ký tự).

### 7.3. Cách bật Dịch vụ Trợ năng trên điện thoại Android / Oppo:
1. Mở app **Screen Share** trên điện thoại.
2. Tại mục *Điều khiển từ máy tính*, bấm nút **"Mở cài đặt Trợ năng"**.
3. Hệ thống sẽ mở màn hình Cài đặt Trợ năng:
   - Trên Android thuần: Chọn **Ứng dụng đã tải xuống** -> Chọn **Screen Share Remote Control** -> Bật công tắc.
   - Trên Oppo / ColorOS: Chọn **Dịch vụ được cài đặt** hoặc tìm mục **Screen Share Remote Control** -> Bật công tắc -> Bấm **Cho phép** khi hệ thống hỏi quyền kiểm soát màn hình.
4. Trạng thái trong app sẽ chuyển sang: `Trợ năng: ĐÃ BẬT` (màu xanh lá).
5. Bật công tắc *"Cho phép điều khiển từ máy tính"* và bắt đầu truyền màn hình.

### 7.4. Cấu hình chế độ kéo: Live Drag vs Dự phòng (Fallback):
- Mặc định: Kích hoạt chế độ **Live Drag** (`LIVE_DRAG_ENABLED = true` trong `ControlConfig.kt`), dùng chuỗi nét liên tục (`continueStroke`, API 26+) cho cảm giác bám chuột tức thì theo thời gian thực.
- Nếu điện thoại gặp hiện tượng lag hoặc hệ điều hành hạn chế chuỗi cử chỉ nhanh, bạn có thể chuyển sang **Chế độ kéo dự phòng**:
  1. Mở file `android/app/src/main/java/com/minh/screenshare/config/ControlConfig.kt`.
  2. Sửa dòng: `const val LIVE_DRAG_ENABLED = false`.
  3. Ở chế độ này, app sẽ ghi nhận tọa độ từ lúc nhấn đến khi thả chuột, sau đó phát 1 cử chỉ duy nhất khi bạn nhả chuột.

### 7.5. Các giới hạn đã biết (Known Limitations):
1. **Hạn chế IME Action trên Android 8 đến 10:**
   - Dịch vụ Trợ năng của Android không cung cấp API để kích hoạt nút "Tìm kiếm" hoặc "Gửi" của bàn phím ảo (IME Action). Phím Enter từ bàn phím PC chỉ chèn ký tự xuống dòng (`\n`). Để gửi tin nhắn hoặc tìm kiếm, người dùng click chuột trực tiếp vào nút Tìm kiếm/Gửi trên màn hình điện thoại.
2. **Màn hình bảo mật (Mật khẩu, Ứng dụng ngân hàng):**
   - Hệ điều hành Android tự động ẩn nội dung màn hình và chặn mọi thao tác trợ năng khi người dùng nhập mật khẩu hệ thống hoặc mở các ứng dụng bảo mật cao.
3. **Quản lý chạy nền trên Oppo / ColorOS:**
   - Hệ điều hành ColorOS có thể tự thu hồi quyền Trợ năng nếu ứng dụng bị dọn dẹp bộ nhớ RAM. Cần đảm bảo đã tắt tối ưu hóa pin và khóa app trong màn hình đa nhiệm (Recent Apps).

---

## 8. Danh sách kiểm tra khi chạy thử (Checklist trên điện thoại thật)

### A. Kiểm tra Truyền màn hình (Video):
- [ ] Mở máy tính: `python pc/main.py`.
- [ ] Nhập IP vào app Android và bấm **Bắt đầu**.
- [ ] Hình ảnh truyền lên máy tính mượt mà ở 60 FPS (hoặc 30 FPS).
- [ ] Thử xoay điện thoại dọc/ngang: cửa sổ PC co giãn giữ đúng tỉ lệ, không méo hình.
- [ ] Bấm Dừng và Bắt đầu lại: kết nối lại nhanh chóng.

### B. Kiểm tra Điều khiển từ xa (Remote Control):
- [ ] Bật dịch vụ Trợ năng trong cài đặt máy: app báo `Trợ năng: ĐÃ BẬT`.
- [ ] Bật công tắc "Cho phép điều khiển": trên PC hộp chọn "Bật điều khiển" cho phép tích chọn và chuyển sang `BẬT`.
- [ ] **Chạm (Click):** Nhấp chuột trái để mở ứng dụng, chọn icon trên màn hình.
- [ ] **Chạm giữ (Long Press):** Nhấn giữ chuột trái tại icon màn hình chính -> menu ngữ cảnh xuất hiện.
- [ ] **Kéo thả / Vuốt (Live Drag):** Nhấn giữ chuột và vuốt qua lại giữa các trang ứng dụng -> màn hình điện thoại bám theo con trỏ chuột thời gian thực.
- [ ] **Cuộn (Scroll):** Mở trình duyệt web hoặc danh sách cài đặt, lăn chuột lên/xuống -> trang cuộn mượt mà.
- [ ] **Pinch Zoom:** Mở ứng dụng Ảnh hoặc Bản đồ, nhấn giữ `Ctrl` + lăn chuột -> phóng to/thu nhỏ 2 ngón quanh vị trí chuột.
- [ ] **Phím hệ thống:**
  - Nhấp chuột phải -> điện thoại thực hiện Back.
  - Nhấp chuột giữa (hoặc nút Home trên toolbar) -> điện thoại về màn hình Home.
  - Bấm nút Recents trên toolbar -> điện thoại mở màn hình Đa nhiệm.
- [ ] **Gõ chữ Tiếng Việt:**
  - Mở ứng dụng Ghi chú hoặc ô tìm kiếm.
  - Gõ văn bản tiếng Việt có dấu: thử gõ chuỗi `"Cộng hòa Xã hội Chủ nghĩa Việt Nam"` bằng cả bộ gõ **Telex Windows** và **Unikey**.
  - Kiểm tra các phím `Backspace`, `Delete`, mũi tên trái/phải di chuyển con trỏ, `Home`, `End`.
  - Nhấn `Ctrl + A` trên bàn phím -> toàn bộ văn bản trong ô được bôi đen.
- [ ] **Kiểm tra An toàn & Edge cases:**
  - Nhấn giữ chuột và kéo ra ngoài cửa sổ PC -> ngón tay tự nhả (`TOUCH_UP`), không bị kẹt cảm ứng trên điện thoại.
  - Rút cáp mạng hoặc tắt cửa sổ PC trong khi đang kéo -> Watchdog 15 giây hoặc sự kiện ngắt kết nối tự động giải phóng cảm ứng, không bị đơ điện thoại.
  - Tắt công tắc "Cho phép điều khiển" trong app khi đang truyền -> mọi thao tác chuột từ PC bị bỏ qua hoàn toàn.

---

## 9. Những điểm CHƯA kiểm tra được trên thiết bị thật (Cần người dùng thử nghiệm)

Các phần code đã được kiểm thử tự động 100% qua Unit Test JVM và kiểm tra luồng video mock_sender. Tuy nhiên, các điểm sau cần được kiểm tra trực tiếp trên phần cứng thật (Oppo A15s chạy Android 8-10):
1. **Độ nhạy cử chỉ Live Drag trên giao diện ColorOS:** Mức độ mượt mà khi vuốt liên tục 60 FPS qua `continueStroke` tùy thuộc vào driver cảm ứng của vi xử lý MediaTek Helio P35 trên Oppo A15s. Nếu thấy cảm ứng bị ngắt quãng, người dùng có thể đổi cờ `LIVE_DRAG_ENABLED = false`.
2. **Khả năng dán văn bản Clipboard với bàn phím tùy biến thứ ba:** Đã triển khai cơ chế ưu tiên dán Clipboard (nhanh, chuẩn dấu tiếng Việt) và cơ chế dự phòng `ACTION_SET_TEXT`. Cần thử nghiệm trên các bàn phím cụ thể như LabanKey, Gboard, bàn phím mặc định Oppo để đánh giá độ tương thích tối đa.
3. **Cơ chế giữ dịch vụ Trợ năng của ColorOS:** ColorOS có thể tự ngắt dịch vụ Trợ năng khi máy vào trạng thái ngủ sâu (Deep Sleep). Người dùng cần cấp quyền *"Tự khởi chạy"* (Auto-launch) và *"Khóa ứng dụng"* trong danh sách đa nhiệm.

