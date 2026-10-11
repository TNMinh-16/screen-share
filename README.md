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

| Loại (Hex/Dec) | Tên gói | Cấu trúc Payload Data | Mô tả |
| :--- | :--- | :--- | :--- |
| **0 (`0x00`)** | **Gói Cấu hình (Config)** | `[4 byte Width (uint32)] + [4 byte Height (uint32)] + [Dữ liệu SPS/PPS]` | Chứa kích thước màn hình và thông số giải mã H.264 (SPS/PPS). Gửi đầu tiên khi kết nối, gửi lại trước mỗi Keyframe và khi xoay màn hình. |
| **1 (`0x01`)** | **Khung hình chính (Keyframe / I-frame)** | `[Dữ liệu NAL units của IDR Keyframe]` | Khung hình độc lập. Phía nhận chỉ bắt đầu hiển thị sau khi đã nhận gói Loại 0 và gói Loại 1 đầu tiên. |
| **2 (`0x02`)** | **Khung hình thường (Delta / P-frame)** | `[Dữ liệu NAL units của P-frame]` | Khung hình vi phân dựa trên khung hình trước. |

---

## 3. Quy tắc giải mã và triệt tiêu độ trễ phía PC (Windows)

1. **Không drop gói H.264 tùy tiện:** Luôn giải mã đầy đủ mọi frame hợp lệ để duy trì chuỗi tham chiếu. Chỉ bỏ qua việc vẽ các khung cũ trên UI, luôn vẽ khung mới nhất.
2. **Xử lý khi giật lag nặng:** Nếu hàng đợi mạng dồn quá ngưỡng (> 25 gói do mạng chập chờn hoặc máy bận), decoder chuyển sang trạng thái bỏ qua các khung thường (P-frame) và nhảy thẳng đến Keyframe tiếp theo để khôi phục ngay độ trễ thấp nhất mà không bị vỡ hình.
3. **Đồng bộ bắt đầu:** Bộ giải mã chỉ xuất hình sau khi nhận đủ gói cấu hình (Type 0) và Keyframe đầu tiên (Type 1).

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

## 5. Lưu ý quan trọng cho môi trường mạng & thiết bị

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

## 6. Hướng dẫn đo độ trễ (Latency Test)

1. Mở một trang web đồng hồ bấm giờ trực tuyến (ví dụ [stopwatch.onlineclock.net](https://stopwatch.onlineclock.net) hoặc đồng hồ mili-giây) trên màn hình máy tính hoặc điện thoại.
2. Để điện thoại quay vào màn hình máy tính hoặc hiển thị đồng hồ trên điện thoại.
3. Chụp một bức ảnh bao gồm cả màn hình máy tính và màn hình điện thoại trong cùng 1 bức ảnh.
4. Lấy số mili-giây trên màn hình máy tính trừ đi số mili-giây trên điện thoại: đó chính là độ trễ thực tế từ đầu tới cuối (End-to-End Latency). Mục tiêu đạt được: dưới **100ms - 150ms** trên mạng Wi-Fi tiêu chuẩn.
