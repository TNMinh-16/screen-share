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
    # Đặt ưu tiên tiến trình Windows cao hơn bình thường (ABOVE_NORMAL) để giảm jitter
    try:
        import ctypes
        ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000
        handle = ctypes.windll.kernel32.GetCurrentProcess()
        ctypes.windll.kernel32.SetPriorityClass(handle, ABOVE_NORMAL_PRIORITY_CLASS)
        logger.info("Đã đặt ưu tiên tiến trình Windows: ABOVE_NORMAL")
    except Exception as e:
        logger.warning(f"Không thể đặt ưu tiên tiến trình Windows: {e}")

from PySide6.QtWidgets import QApplication
from PySide6.QtCore import QObject, Signal

from receiver import StreamReceiver
from decoder import H264Decoder
from viewer import MainWindow, PRESENT_BUFFER_FRAMES
from control import ControlSender

logging.basicConfig(level=logging.INFO, format="[%(asctime)s][%(levelname)s] %(message)s")
logger = logging.getLogger("Main")


class EventBridge(QObject):
    """
    Cầu nối tín hiệu Qt (Signals) để giao tiếp an toàn đa luồng giữa
    Receiver/Decoder/Control (Background Threads) và Viewer (Main GUI Thread).
    """
    client_connected_signal = Signal(str)
    client_disconnected_signal = Signal()
    dimension_changed_signal = Signal(int, int)
    control_state_signal = Signal(int)


def main():
    app = QApplication(sys.argv)
    app.setStyle("Fusion")

    # 1. Hàng đợi trung gian
    # packet_queue: Receiver -> Decoder
    packet_queue = queue.Queue(maxsize=120)
    # display_queue: Decoder -> GUI Viewer (kích thước phù hợp theo PRESENT_BUFFER_FRAMES)
    display_queue_size = max(10, PRESENT_BUFFER_FRAMES + 4)
    display_queue = queue.Queue(maxsize=display_queue_size)

    # 2. Khởi tạo Cầu nối tín hiệu Qt
    bridge = EventBridge()

    DEFAULT_PORT = 8554

    # 3. Khởi tạo Decoder Thread
    def on_dimension_changed(w, h):
        bridge.dimension_changed_signal.emit(w, h)

    decoder = H264Decoder(
        packet_queue=packet_queue,
        display_queue=display_queue,
        on_dimension_changed=on_dimension_changed
    )

    # 4. Khởi tạo và khởi động Receiver Thread
    def on_client_connected(addr):
        ip_str = f"{addr[0]}:{addr[1]}"
        logger.info(f"Kết nối mới từ: {ip_str}")
        bridge.client_connected_signal.emit(ip_str)

    def on_client_disconnected():
        logger.info("Điện thoại đã ngắt kết nối.")
        bridge.client_disconnected_signal.emit()
        decoder.reset()

    def on_control_state_changed(flags):
        bridge.control_state_signal.emit(flags)

    receiver = StreamReceiver(
        host="0.0.0.0",
        port=DEFAULT_PORT,
        packet_queue=packet_queue,
        on_client_connected=on_client_connected,
        on_client_disconnected=on_client_disconnected,
        on_control_state_changed=on_control_state_changed
    )
    receiver.start()
    decoder.start()

    # 5. Khởi tạo và khởi động ControlSender
    control_sender = ControlSender(receiver.send_bytes)
    control_sender.start()

    # 6. Khởi tạo Giao diện cửa sổ (truyền receiver và decoder cho thống kê và auto-scale)
    window = MainWindow(
        display_queue=display_queue,
        default_port=DEFAULT_PORT,
        control_sender=control_sender,
        receiver=receiver,
        decoder=decoder
    )

    # Kết nối signals từ bridge vào các phương thức cập nhật UI của window
    bridge.client_connected_signal.connect(lambda ip_str: window.update_status(True, ip_str))
    bridge.client_disconnected_signal.connect(lambda: window.update_status(False))
    bridge.dimension_changed_signal.connect(lambda w, h: window.update_resolution(w, h))
    bridge.control_state_signal.connect(window.update_control_state)

    # 7. Dọn dẹp tài nguyên khi người dùng tắt cửa sổ
    def cleanup():
        logger.info("Đang tắt ứng dụng và giải phóng tài nguyên...")
        control_sender.stop()
        receiver.stop()
        decoder.stop()

    app.aboutToQuit.connect(cleanup)

    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
