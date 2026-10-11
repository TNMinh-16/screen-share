"""
Module: main.py
Mục đích: Điểm khởi động chính của ứng dụng Windows.
          Khởi tạo GUI PySide6, kết nối luồng Receiver và Decoder.
"""

import sys
import queue
import logging

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

from PySide6.QtWidgets import QApplication
from PySide6.QtCore import QObject, Signal

from receiver import StreamReceiver
from decoder import H264Decoder
from viewer import MainWindow

logging.basicConfig(level=logging.INFO, format="[%(asctime)s][%(levelname)s] %(message)s")
logger = logging.getLogger("Main")


class EventBridge(QObject):
    """
    Cầu nối tín hiệu Qt (Signals) để giao tiếp an toàn đa luồng giữa
    Receiver/Decoder (Background Threads) và Viewer (Main GUI Thread).
    """
    client_connected_signal = Signal(str)
    client_disconnected_signal = Signal()
    dimension_changed_signal = Signal(int, int)


def main():
    app = QApplication(sys.argv)
    app.setStyle("Fusion")

    # 1. Hàng đợi trung gian
    # packet_queue: Receiver -> Decoder
    packet_queue = queue.Queue(maxsize=120)
    # display_queue: Decoder -> GUI Viewer (chỉ lưu frame RGB mới nhất)
    display_queue = queue.Queue(maxsize=2)

    # 2. Khởi tạo Cầu nối tín hiệu Qt
    bridge = EventBridge()

    # 3. Khởi tạo Giao diện cửa sổ
    DEFAULT_PORT = 8554
    window = MainWindow(display_queue=display_queue, default_port=DEFAULT_PORT)

    # Kết nối signals từ bridge vào các phương thức cập nhật UI của window
    bridge.client_connected_signal.connect(lambda ip_str: window.update_status(True, ip_str))
    bridge.client_disconnected_signal.connect(lambda: window.update_status(False))
    bridge.dimension_changed_signal.connect(lambda w, h: window.update_resolution(w, h))

    # 4. Khởi tạo và khởi động Decoder Thread
    def on_dimension_changed(w, h):
        bridge.dimension_changed_signal.emit(w, h)

    decoder = H264Decoder(
        packet_queue=packet_queue,
        display_queue=display_queue,
        on_dimension_changed=on_dimension_changed
    )
    decoder.start()

    # 5. Khởi tạo và khởi động Receiver Thread
    def on_client_connected(addr):
        ip_str = f"{addr[0]}:{addr[1]}"
        logger.info(f"Kết nối mới từ: {ip_str}")
        bridge.client_connected_signal.emit(ip_str)

    def on_client_disconnected():
        logger.info("Điện thoại đã ngắt kết nối.")
        bridge.client_disconnected_signal.emit()
        decoder.reset()

    receiver = StreamReceiver(
        host="0.0.0.0",
        port=DEFAULT_PORT,
        packet_queue=packet_queue,
        on_client_connected=on_client_connected,
        on_client_disconnected=on_client_disconnected
    )
    receiver.start()

    # 6. Dọn dẹp tài nguyên khi người dùng tắt cửa sổ
    def cleanup():
        logger.info("Đang tắt ứng dụng và giải phóng tài nguyên...")
        receiver.stop()
        decoder.stop()

    app.aboutToQuit.connect(cleanup)

    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
