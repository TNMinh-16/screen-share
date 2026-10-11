"""
Module: mock_sender.py
Mục đích: Script giả lập một thiết bị Android gửi luồng màn hình H.264 qua TCP socket.
          Dùng để kiểm thử độc lập phần mềm Windows (Bước 1) mà chưa cần điện thoại thật.
          
Tính năng mô phỏng:
- Vẽ hoạt họa đồng hồ thời gian thực (mili-giây), thanh chuyển động mượt mà, đếm số khung hình.
- Tự động mã hóa H.264 (libx264, GOP 30 = 1 giây/keyframe).
- Đóng gói chuẩn giao thức:
  + Gói Loại 0: Width, Height + SPS/PPS (gửi đầu tiên và gửi lại trước mỗi Keyframe).
  + Gói Loại 1: Keyframe (I-frame).
  + Gói Loại 2: Frame thường (P-frame).
- Sau 15 giây tự động mô phỏng XOAY MÀN HÌNH (đổi độ phân giải giữa ngang và dọc)
  để kiểm tra khả năng tự thích ứng của Viewer!
"""

import sys
import time
import socket
import struct
import argparse
import fractions
import numpy as np
import av
import cv2

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass


def extract_nalus(raw_bytes):
    """
    Tách các NAL unit từ byte stream Annex B (phân tách bởi 00 00 00 01 hoặc 00 00 01).
    Trả về: (sps_pps_bytes, slice_bytes)
    """
    start_codes = []
    length = len(raw_bytes)
    for i in range(length - 3):
        if raw_bytes[i:i+4] == b"\x00\x00\x00\x01":
            start_codes.append((i, 4))
        elif raw_bytes[i:i+3] == b"\x00\x00\x01" and (i == 0 or raw_bytes[i-1] != 0):
            start_codes.append((i, 3))

    sps_pps = bytearray()
    slice_data = bytearray()

    for k in range(len(start_codes)):
        start, sc_len = start_codes[k]
        end = start_codes[k+1][0] if k + 1 < len(start_codes) else length
        nalu = raw_bytes[start:end]
        nal_type = nalu[sc_len] & 0x1F

        if nal_type in (7, 8):  # 7: SPS, 8: PPS
            sps_pps.extend(nalu)
        else:
            slice_data.extend(nalu)

    return bytes(sps_pps), bytes(slice_data)


def create_encoder(width, height, fps=30):
    """Khởi tạo bộ mã hóa H.264 qua PyAV."""
    codec = av.CodecContext.create("libx264", "w")
    codec.width = width
    codec.height = height
    codec.pix_fmt = "yuv420p"
    codec.time_base = fractions.Fraction(1, fps)
    codec.gop_size = fps  # 1 keyframe mỗi giây
    codec.options = {
        "preset": "ultrafast",
        "tune": "zerolatency",
        "repeat-headers": "1",  # Lặp lại SPS/PPS trước mỗi keyframe
    }
    codec.open()
    return codec


def draw_test_frame(frame_idx, width, height, fps, is_keyframe, session_start):
    """
    Vẽ khung hình test: đồng hồ mili-giây, thanh quét chuyển động, thông số.
    """
    img = np.zeros((height, width, 3), dtype=np.uint8)

    # Nền gradient nhẹ
    for y in range(height):
        img[y, :, 0] = int(20 + 25 * (y / height))
        img[y, :, 1] = int(25 + 30 * (y / height))
        img[y, :, 2] = int(35 + 40 * (y / height))

    # Thanh trượt chuyển động ngang qua lại để kiểm tra độ mượt
    bar_width = 80
    cycle = 2 * (width - bar_width)
    pos = (frame_idx * 15) % cycle
    if pos > (width - bar_width):
        pos = cycle - pos

    cv2.rectangle(img, (pos, height - 60), (pos + bar_width, height - 20), (0, 220, 100), -1)

    # Viết chữ thông tin
    now_str = time.strftime("%H:%M:%S")
    millis = int((time.time() % 1) * 1000)
    time_display = f"{now_str}.{millis:03d}"
    uptime = f"{time.time() - session_start:.1f}s"

    cv2.putText(img, "ANDROID MOCK SENDER (TEST STREAM)", (30, 50),
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2, cv2.LINE_AA)

    cv2.putText(img, f"Dong ho: {time_display}", (30, 100),
                cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2, cv2.LINE_AA)

    cv2.putText(img, f"Do phan giai: {width}x{height} | Frame: {frame_idx:05d}", (30, 140),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (200, 200, 200), 1, cv2.LINE_AA)

    cv2.putText(img, f"Thoi gian chay: {uptime}", (30, 175),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (180, 180, 255), 1, cv2.LINE_AA)

    if is_keyframe:
        cv2.putText(img, "[KEYFRAME / I-FRAME]", (30, 220),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (50, 50, 255), 2, cv2.LINE_AA)

    return img


def send_packet(sock, packet_type, payload):
    """
    Gửi gói tin theo chuẩn giao thức:
    [4 byte độ dài payload (big-endian)] [1 byte loại gói] [payload]
    """
    header = struct.pack(">IB", len(payload), packet_type)
    sock.sendall(header + payload)


def run_mock_sender(host="127.0.0.1", port=8554, fps=30, test_rotation=True):
    print(f"=== KHỞI ĐỘNG MOCK SENDER ===")
    print(f"Đang kết nối tới máy chủ Windows tại {host}:{port}...")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    try:
        sock.connect((host, port))
        print("-> Kết nối TCP thành công! Bắt đầu phát luồng video...\n")
    except ConnectionRefusedError:
        print(f"-> LỖI: Không thể kết nối tới {host}:{port}!")
        print("-> Hãy chắc chắn bạn đã chạy 'python main.py' trước khi chạy script này.")
        return

    # Kích thước ban đầu (màn hình ngang)
    cur_w, cur_h = 640, 360
    encoder = create_encoder(cur_w, cur_h, fps)

    frame_interval = 1.0 / fps
    frame_idx = 0
    cached_sps_pps = None
    session_start = time.time()
    last_rotation_time = time.time()
    is_landscape = True

    try:
        while True:
            t_start = time.time()

            # Mô phỏng xoay màn hình mỗi 12 giây để kiểm tra
            if test_rotation and (time.time() - last_rotation_time > 12):
                is_landscape = not is_landscape
                if is_landscape:
                    cur_w, cur_h = 640, 360
                else:
                    cur_w, cur_h = 360, 640

                print(f"\n[XOAY MÀN HÌNH] Đổi kích thước thành {cur_w}x{cur_h}...")
                encoder = create_encoder(cur_w, cur_h, fps)
                cached_sps_pps = None
                last_rotation_time = time.time()

            # Xác định frame này có phải là keyframe không (định kỳ mỗi 1 giây)
            is_keyframe = (frame_idx % fps == 0)

            # Vẽ khung hình
            frame_img = draw_test_frame(frame_idx, cur_w, cur_h, fps, is_keyframe, session_start)

            # Chuyển đổi sang PyAV VideoFrame và mã hóa
            av_frame = av.VideoFrame.from_ndarray(frame_img, format="bgr24")
            packets = encoder.encode(av_frame)

            for pkt in packets:
                raw_bytes = bytes(pkt)
                sps_pps, slice_data = extract_nalus(raw_bytes)

                if sps_pps:
                    cached_sps_pps = sps_pps

                # Nếu là keyframe hoặc chưa có config nào được gửi:
                if pkt.is_keyframe:
                    # Gửi gói Loại 0 (Cấu hình: Width, Height + SPS/PPS)
                    if cached_sps_pps:
                        config_payload = struct.pack(">II", cur_w, cur_h) + cached_sps_pps
                        send_packet(sock, 0, config_payload)

                    # Gửi gói Loại 1 (Keyframe)
                    payload_to_send = slice_data if slice_data else raw_bytes
                    send_packet(sock, 1, payload_to_send)
                else:
                    # Gửi gói Loại 2 (Frame thường / P-frame)
                    payload_to_send = slice_data if slice_data else raw_bytes
                    send_packet(sock, 2, payload_to_send)

            frame_idx += 1

            if frame_idx % (fps * 2) == 0:
                elapsed = time.time() - session_start
                actual_fps = frame_idx / elapsed
                print(f"Đã gửi {frame_idx} khung hình (FPS thực tế: {actual_fps:.1f})")

            # Điều hòa tốc độ để giữ đúng 30 FPS
            elapsed_frame = time.time() - t_start
            sleep_time = frame_interval - elapsed_frame
            if sleep_time > 0:
                time.sleep(sleep_time)

    except (ConnectionResetError, BrokenPipeError):
        print("\n-> Kết nối bị đóng từ phía server (cửa sổ Windows đã tắt).")
    except KeyboardInterrupt:
        print("\n-> Người dùng dừng gửi (Ctrl+C).")
    finally:
        sock.close()
        print("-> Đã đóng socket.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Mock Sender truyền màn hình H.264 qua TCP")
    parser.add_argument("--host", default="127.0.0.1", help="Địa chỉ IP server (mặc định 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8554, help="Cổng TCP (mặc định 8554)")
    parser.add_argument("--fps", type=int, default=30, help="Số khung hình/giây (mặc định 30)")
    parser.add_argument("--no-rotation", action="store_true", help="Tắt tính năng mô phỏng xoay màn hình")

    args = parser.parse_args()
    run_mock_sender(host=args.host, port=args.port, fps=args.fps, test_rotation=not args.no_rotation)
