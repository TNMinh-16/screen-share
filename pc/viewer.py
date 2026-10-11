"""
Module: viewer.py
Mục đích: Giao diện đồ họa hiển thị luồng màn hình điện thoại bằng PySide6.
          Hỗ trợ:
          - Tự động co giãn theo kích thước cửa sổ nhưng luôn giữ nguyên tỉ lệ màn hình (Aspect Ratio).
          - Hiển thị FPS thực tế, trạng thái kết nối, độ phân giải.
          - Hiển thị IP của máy tính trong mạng Wi-Fi và Port để người dùng nhập vào app Android.
"""

import time
import socket
import queue
import numpy as np
from PySide6.QtCore import Qt, QTimer, QRect
from PySide6.QtGui import QImage, QPainter, QColor, QFont, QPen
from PySide6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame
)


def get_local_ip():
    """
    Tự động dò tìm địa chỉ IP nội bộ (LAN) của máy tính.
    Thực hiện bằng cách mở socket UDP kết nối giả định ra ngoài (không gửi dữ liệu thực).
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Không cần kết nối internet thực sự, chỉ để OS chọn interface mạng LAN chính xác
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


class VideoRenderWidget(QWidget):
    """
    Widget hiển thị khung hình video H.264.
    Sử dụng QPainter để vẽ QImage trực tiếp với tỉ lệ chính xác, không giật hình.
    """
    def __init__(self, parent=None):
        super().__init__(parent)
        self.current_qimage = None
        self.video_width = 0
        self.video_height = 0
        self.setStyleSheet("background-color: #121212;")

    def set_frame(self, rgb_array):
        """Nhận mảng numpy RGB24 và tạo QImage để vẽ."""
        h, w, c = rgb_array.shape
        self.video_width = w
        self.video_height = h

        # Tạo QImage từ buffer dữ liệu RGB (Format_RGB888)
        # Giữ một bản copy để đảm bảo vùng nhớ an toàn khi render
        qimg = QImage(rgb_array.data, w, h, 3 * w, QImage.Format.Format_RGB888).copy()
        self.current_qimage = qimg
        self.update()  # Yêu cầu Qt vẽ lại widget

    def clear_frame(self):
        """Xóa khung hình khi ngắt kết nối."""
        self.current_qimage = None
        self.video_width = 0
        self.video_height = 0
        self.update()

    def paintEvent(self, event):
        """Phương thức vẽ lại giao diện của Qt."""
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)

        # Vẽ nền tối
        painter.fillRect(self.rect(), QColor("#121212"))

        if self.current_qimage is not None and not self.current_qimage.isNull():
            # Tính toán hình chữ nhật căn giữa và giữ nguyên tỉ lệ (Aspect Ratio)
            widget_w = self.width()
            widget_h = self.height()
            img_w = self.video_width
            img_h = self.video_height

            if img_w > 0 and img_h > 0:
                scale = min(widget_w / img_w, widget_h / img_h)
                draw_w = int(img_w * scale)
                draw_h = int(img_h * scale)
                draw_x = int((widget_w - draw_w) / 2)
                draw_y = int((widget_h - draw_h) / 2)

                target_rect = QRect(draw_x, draw_y, draw_w, draw_h)
                painter.drawImage(target_rect, self.current_qimage)
        else:
            # Khi chưa có video: Hiển thị thông báo chờ kết nối
            painter.setPen(QColor("#888888"))
            painter.setFont(QFont("Segoe UI", 13))
            text = "Đang chờ kết nối từ điện thoại Android...\nHãy nhập IP và Port trên điện thoại rồi bấm 'Bắt đầu'"
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, text)

        painter.end()


class MainWindow(QMainWindow):
    """Cửa sổ chính của ứng dụng Windows."""
    def __init__(self, display_queue, default_port=8554):
        super().__init__()
        self.display_queue = display_queue
        self.default_port = default_port

        self.local_ip = get_local_ip()
        self.frame_count = 0
        self.fps_timer_start = time.time()
        self.current_fps = 0.0

        self.init_ui()

        # Timer lấy khung hình từ hàng đợi (chạy mỗi 5ms ~ 200Hz để không trễ)
        self.render_timer = QTimer(self)
        self.render_timer.timeout.connect(self.check_new_frame)
        self.render_timer.start(5)

        # Timer tính toán FPS mỗi 1 giây
        self.fps_calc_timer = QTimer(self)
        self.fps_calc_timer.timeout.connect(self.calculate_fps)
        self.fps_calc_timer.start(1000)

    def init_ui(self):
        """Khởi tạo bố cục giao diện."""
        self.setWindowTitle("Bộ xem màn hình Android (Real-time LAN Screen Share)")
        self.resize(800, 750)
        self.setMinimumSize(400, 400)

        # Widget trung tâm
        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(10, 10, 10, 10)
        main_layout.setSpacing(8)

        # 1. Thanh thông tin kết nối (Header Bar)
        header_frame = QFrame()
        header_frame.setStyleSheet("""
            QFrame {
                background-color: #1e1e24;
                border-radius: 8px;
                padding: 6px;
            }
            QLabel {
                color: #e0e0e0;
                font-family: 'Segoe UI', Arial;
                font-size: 13px;
            }
        """)
        header_layout = QHBoxLayout(header_frame)
        header_layout.setContentsMargins(12, 6, 12, 6)

        self.lbl_ip_info = QLabel(f"💻 IP Máy tính: <b style='color:#4CAF50;'>{self.local_ip}</b> | Cổng: <b style='color:#4CAF50;'>{self.default_port}</b>")
        self.lbl_status = QLabel("Trạng thái: <span style='color:#FFC107;'>Đang chờ kết nối...</span>")
        self.lbl_fps = QLabel("FPS: <b>0.0</b>")
        self.lbl_resolution = QLabel("Độ phân giải: <b>---</b>")

        header_layout.addWidget(self.lbl_ip_info)
        header_layout.addStretch()
        header_layout.addWidget(self.lbl_resolution)
        header_layout.addSpacing(15)
        header_layout.addWidget(self.lbl_fps)
        header_layout.addSpacing(15)
        header_layout.addWidget(self.lbl_status)

        main_layout.addWidget(header_frame)

        # 2. Vùng hiển thị video chính
        self.video_widget = VideoRenderWidget(self)
        main_layout.addWidget(self.video_widget, stretch=1)

    def check_new_frame(self):
        """Kiểm tra và lấy khung hình mới nhất từ display_queue."""
        latest_frame = None
        # Luôn lấy khung hình cuối cùng nhất (bỏ qua các khung cũ nếu có)
        while not self.display_queue.empty():
            try:
                latest_frame = self.display_queue.get_nowait()
            except queue.Empty:
                break

        if latest_frame is not None:
            self.video_widget.set_frame(latest_frame)
            self.frame_count += 1

    def calculate_fps(self):
        """Tính FPS thực tế mỗi giây và hiển thị."""
        now = time.time()
        elapsed = now - self.fps_timer_start
        if elapsed > 0:
            self.current_fps = self.frame_count / elapsed
            self.lbl_fps.setText(f"FPS: <b>{self.current_fps:.1f}</b>")

        self.frame_count = 0
        self.fps_timer_start = now

    def update_status(self, connected, client_ip=None):
        """Cập nhật nhãn trạng thái kết nối từ client."""
        if connected:
            self.lbl_status.setText(f"Trạng thái: <span style='color:#4CAF50;'>Đã kết nối ({client_ip})</span>")
        else:
            self.lbl_status.setText("Trạng thái: <span style='color:#FFC107;'>Đang chờ kết nối...</span>")
            self.lbl_fps.setText("FPS: <b>0.0</b>")
            self.lbl_resolution.setText("Độ phân giải: <b>---</b>")
            self.video_widget.clear_frame()

    def update_resolution(self, width, height):
        """Cập nhật nhãn độ phân giải khi nhận gói cấu hình hoặc xoay màn hình."""
        self.lbl_resolution.setText(f"Độ phân giải: <b>{width}x{height}</b>")
