"""
Kịch bản kiểm tra tự động pipeline Bước 1 (Receiver + Decoder + Mock Sender)
Không cần mở cửa sổ đồ họa, kiểm tra tính toàn vẹn của luồng dữ liệu, bóc tách gói và giải mã frame.
"""

import time
import queue
import threading
import subprocess
import sys

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
from receiver import StreamReceiver
from decoder import H264Decoder


def test_pipeline():
    print("[TEST] Bắt đầu kiểm tra pipeline Bước 1...")

    packet_queue = queue.Queue(maxsize=120)
    display_queue = queue.Queue(maxsize=10)

    dim_received = []

    def on_dim(w, h):
        print(f"[TEST] Nhận sự kiện đổi kích thước: {w}x{h}")
        dim_received.append((w, h))

    connected_events = []

    def on_conn(addr):
        print(f"[TEST] Client kết nối từ: {addr}")
        connected_events.append(addr)

    disconnected_events = []

    def on_disconn():
        print("[TEST] Client đã ngắt kết nối.")
        disconnected_events.append(True)

    # Khởi động Receiver
    receiver = StreamReceiver(
        host="127.0.0.1",
        port=8554,
        packet_queue=packet_queue,
        on_client_connected=on_conn,
        on_client_disconnected=on_disconn
    )
    receiver.start()

    # Khởi động Decoder
    decoder = H264Decoder(
        packet_queue=packet_queue,
        display_queue=display_queue,
        on_dimension_changed=on_dim
    )
    decoder.start()

    time.sleep(0.5)

    # Chạy mock_sender gửi video test trong tiến trình con
    print("[TEST] Khởi chạy mock_sender...")
    import os
    mock_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock_sender.py")
    cmd = [sys.executable, mock_path, "--host", "127.0.0.1", "--port", "8554", "--fps", "30", "--no-rotation"]
    sender_proc = subprocess.Popen(cmd)

    frames_received = 0
    t0 = time.time()

    try:
        while time.time() - t0 < 4.0:
            try:
                frame = display_queue.get(timeout=0.2)
                frames_received += 1
            except queue.Empty:
                pass
    finally:
        print(f"[TEST] Đang dừng mock_sender (đã nhận {frames_received} frames)...")
        sender_proc.terminate()
        sender_proc.wait(timeout=2)
        receiver.stop()
        decoder.stop()

    print(f"[TEST] KẾT QUẢ KIỂM TRA:")
    print(f"- Số frame giải mã thành công: {frames_received}")
    print(f"- Sự kiện kết nối: {len(connected_events)}")
    print(f"- Kích thước video: {dim_received}")

    assert len(connected_events) > 0, "Không nhận được sự kiện kết nối!"
    assert len(dim_received) > 0, "Không nhận được kích thước video!"
    assert frames_received >= 20, f"Số frame giải mã quá ít: {frames_received}"
    print("[TEST] >>> TẤT CẢ CÁC BÀI TEST BƯỚC 1 ĐỀU THÀNH CÔNG RỰC RỠ! <<<")


if __name__ == "__main__":
    test_pipeline()
