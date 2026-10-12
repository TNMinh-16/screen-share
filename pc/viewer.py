"""
Module: viewer.py
Mục đích: Giao diện đồ họa hiển thị luồng màn hình điện thoại bằng PySide6 và điều khiển hai chiều.
          Hỗ trợ:
          - Tự động co giãn theo kích thước cửa sổ nhưng luôn giữ nguyên tỉ lệ màn hình (Aspect Ratio).
          - Hiển thị FPS thực tế, trạng thái kết nối, độ phân giải.
          - Điều khiển chuột thời gian thực: chạm, chạm giữ, kéo thả (live drag), cuộn, pinch zoom.
          - Chuột phải: Back, Chuột giữa: Home.
          - Thanh công cụ: nút Back, Home, Recents, hộp chọn bật/tắt điều khiển.
          - Bàn phím: gõ văn bản tiếng Việt (Unikey/Telex), phím mũi tên, xóa, chọn tất cả.
"""

import time
import socket
import queue
import logging
import numpy as np
from PySide6.QtCore import Qt, QTimer, QRect, QPoint
from PySide6.QtGui import QImage, QPainter, QColor, QFont, QPen, QKeyEvent, QMouseEvent, QWheelEvent
from PySide6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QPushButton, QCheckBox
)

from control import (
    CMD_TOUCH_DOWN, CMD_TOUCH_MOVE, CMD_TOUCH_UP, CMD_SCROLL, CMD_PINCH,
    CMD_TEXT, CMD_KEY, CMD_NAV,
    KEY_BACKSPACE, KEY_DELETE, KEY_LEFT, KEY_RIGHT, KEY_HOME_CURSOR, KEY_END_CURSOR,
    KEY_ENTER, KEY_SELECT_ALL,
    NAV_BACK, NAV_HOME, NAV_RECENTS,
    FLAG_CONTROL_ENABLED, FLAG_ACCESSIBILITY_CONNECTED,
    build_touch_down, build_touch_move, build_touch_up, build_scroll, build_pinch,
    build_text, build_key, build_nav,
    map_mouse_to_normalized, clamp_mouse_to_normalized
)

logger = logging.getLogger("Viewer")

# Cấu hình bộ đệm hiển thị:
# 0 = Hiển thị ngay khung mới nhất (độ trễ thấp nhất)
# 1 = Mặc định: bộ đệm 1 khung hình giúp nhịp hiển thị đều đặn 60 FPS
# 2 = Mượt hơn nhưng trễ thêm 1 khung hình (~16 ms)
PRESENT_BUFFER_FRAMES = 1


def get_local_ip():
    """Tự động dò tìm địa chỉ IP nội bộ (LAN) của máy tính."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


class VideoRenderWidget(QWidget):
    """
    Widget hiển thị khung hình video H.264 và tiếp nhận sự kiện chuột/phím để điều khiển.
    """
    def __init__(self, parent=None):
        super().__init__(parent)
        self.current_qimage = None
        self.video_width = 0
        self.video_height = 0
        self.setStyleSheet("background-color: #121212;")

        self.control_sender = None
        self.control_enabled = False

        self.is_dragging = False
        self.last_drag_norm = (0, 0)
        self.last_move_time = 0.0

        # Callback thông báo kích thước vùng vẽ thay đổi cho Decoder swscale
        self.on_target_size_changed = None
        self._last_notified_w = 0
        self._last_notified_h = 0

        # Thống kê thời gian vẽ (paint time)
        self.total_paint_time = 0.0
        self.paint_samples = 0

        # Cấu hình để tiếp nhận sự kiện bàn phím và bộ gõ IME (Unikey/Telex)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self.setAttribute(Qt.WidgetAttribute.WA_InputMethodEnabled, True)

    def set_control_sender(self, control_sender):
        """Thiết lập đối tượng gửi lệnh điều khiển."""
        self.control_sender = control_sender

    def set_control_enabled(self, enabled: bool):
        """Bật hoặc tắt trạng thái điều khiển từ widget."""
        if self.control_enabled and not enabled and self.is_dragging:
            # Nếu đang kéo mà bị tắt điều khiển -> gửi ngay TOUCH_UP để không kẹt ngón tay
            self._send_touch_up(*self.last_drag_norm)
            self.is_dragging = False
        self.control_enabled = enabled
        self.update()

    def set_frame(self, frame_item):
        """Nhận khung hình (tuple (QImage, w, h), QImage hoặc ndarray) để vẽ."""
        if isinstance(frame_item, tuple):
            qimg, orig_w, orig_h = frame_item
            self.video_width = orig_w
            self.video_height = orig_h
            self.current_qimage = qimg
        elif isinstance(frame_item, QImage):
            self.current_qimage = frame_item
            if self.video_width == 0:
                self.video_width = frame_item.width()
                self.video_height = frame_item.height()
        elif isinstance(frame_item, np.ndarray):
            h, w, c = frame_item.shape
            self.video_width = w
            self.video_height = h
            self.current_qimage = QImage(frame_item.data, w, h, 3 * w, QImage.Format.Format_RGB888).copy()
        self._notify_target_size()
        self.update()

    def clear_frame(self):
        """Xóa khung hình khi ngắt kết nối."""
        if self.is_dragging:
            self._send_touch_up(*self.last_drag_norm)
            self.is_dragging = False
        self.current_qimage = None
        self.video_width = 0
        self.video_height = 0
        self.update()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._notify_target_size()

    def _notify_target_size(self):
        """Tính toán kích thước vẽ thực tế và thông báo cho decoder swscale."""
        if self.video_width > 0 and self.video_height > 0 and self.on_target_size_changed:
            widget_w = self.width()
            widget_h = self.height()
            scale = min(widget_w / self.video_width, widget_h / self.video_height)
            draw_w = int(self.video_width * scale)
            draw_h = int(self.video_height * scale)
            if abs(draw_w - self._last_notified_w) > 16 or abs(draw_h - self._last_notified_h) > 16:
                self._last_notified_w = draw_w
                self._last_notified_h = draw_h
                self.on_target_size_changed(draw_w, draw_h)

    def get_and_reset_paint_stats(self) -> float:
        """Lấy và đặt lại thời gian vẽ trung bình (ms)."""
        if self.paint_samples > 0:
            avg_ms = self.total_paint_time / self.paint_samples
        else:
            avg_ms = 0.0
        self.total_paint_time = 0.0
        self.paint_samples = 0
        return avg_ms

    def paintEvent(self, event):
        """Phương thức vẽ lại giao diện của Qt."""
        t0 = time.perf_counter()
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor("#121212"))

        if self.current_qimage is not None and not self.current_qimage.isNull():
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

                # Nếu QImage đã được swscale co giãn đúng kích thước vẽ, vẽ trực tiếp cực nhanh
                if self.current_qimage.width() == draw_w and self.current_qimage.height() == draw_h:
                    painter.drawImage(QPoint(draw_x, draw_y), self.current_qimage)
                else:
                    painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
                    painter.drawImage(target_rect, self.current_qimage)

                # Vẽ viền xanh tinh tế quanh vùng màn hình nếu đang bật điều khiển
                if self.control_enabled:
                    pen = QPen(QColor(76, 175, 80, 160), 1)
                    painter.setPen(pen)
                    painter.drawRect(target_rect)
        else:
            painter.setPen(QColor("#888888"))
            painter.setFont(QFont("Segoe UI", 13))
            text = "Đang chờ kết nối từ điện thoại Android...\nHãy nhập IP và Port trên điện thoại rồi bấm 'Bắt đầu'"
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, text)

        painter.end()
        t1 = time.perf_counter()
        self.total_paint_time += (t1 - t0) * 1000.0
        self.paint_samples += 1

    # ------------------ SỰ KIỆN CHUỘT (TOUCH) ------------------

    def mousePressEvent(self, event: QMouseEvent):
        self.setFocus()
        if not self.control_enabled or not self.control_sender:
            return

        pos = event.position()
        mx, my = pos.x(), pos.y()

        # Chuột phải: Phím BACK hệ thống
        if event.button() == Qt.MouseButton.RightButton:
            self.control_sender.enqueue(CMD_NAV, build_nav(NAV_BACK))
            return

        # Chuột giữa: Phím HOME hệ thống
        if event.button() == Qt.MouseButton.MiddleButton:
            self.control_sender.enqueue(CMD_NAV, build_nav(NAV_HOME))
            return

        # Chuột trái: TOUCH_DOWN
        if event.button() == Qt.MouseButton.LeftButton:
            norm = map_mouse_to_normalized(
                mx, my, self.width(), self.height(),
                self.video_width, self.video_height
            )
            if norm is not None:
                self.is_dragging = True
                self.last_drag_norm = norm
                self.last_move_time = time.time()
                self.control_sender.enqueue(CMD_TOUCH_DOWN, build_touch_down(*norm))

    def mouseMoveEvent(self, event: QMouseEvent):
        if not self.control_enabled or not self.control_sender or not self.is_dragging:
            return

        now = time.time()
        # Giới hạn tần suất gửi TOUCH_MOVE ~60 lần/giây (16ms) để tránh nghẽn socket
        if now - self.last_move_time < 0.016:
            return

        pos = event.position()
        norm = clamp_mouse_to_normalized(
            pos.x(), pos.y(), self.width(), self.height(),
            self.video_width, self.video_height
        )
        if norm is not None:
            self.last_drag_norm = norm
            self.last_move_time = now
            self.control_sender.enqueue(CMD_TOUCH_MOVE, build_touch_move(*norm))

    def mouseReleaseEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.LeftButton and self.is_dragging:
            pos = event.position()
            norm = clamp_mouse_to_normalized(
                pos.x(), pos.y(), self.width(), self.height(),
                self.video_width, self.video_height
            )
            final_norm = norm if norm is not None else self.last_drag_norm
            self._send_touch_up(*final_norm)
            self.is_dragging = False

    def leaveEvent(self, event):
        """Chuột rời khỏi cửa sổ khi đang kéo -> nhả phím để không kẹt ngón tay."""
        if self.is_dragging:
            self._send_touch_up(*self.last_drag_norm)
            self.is_dragging = False

    def focusOutEvent(self, event):
        """Cửa sổ mất focus khi đang kéo -> nhả phím."""
        if self.is_dragging:
            self._send_touch_up(*self.last_drag_norm)
            self.is_dragging = False

    def _send_touch_up(self, x: int, y: int):
        if self.control_sender:
            self.control_sender.enqueue(CMD_TOUCH_UP, build_touch_up(x, y))

    def wheelEvent(self, event: QWheelEvent):
        """Con lăn chuột: Cuộn trang (Scroll) hoặc Phóng to/Thu nhỏ (Pinch zoom với Ctrl)."""
        if not self.control_enabled or not self.control_sender:
            return

        pos = event.position()
        norm = map_mouse_to_normalized(
            pos.x(), pos.y(), self.width(), self.height(),
            self.video_width, self.video_height
        )
        if norm is None:
            return

        delta = event.angleDelta()
        dy = delta.y()
        dx = delta.x()

        # Giữ Ctrl + Con lăn chuột: PINCH ZOOM hai ngón
        if event.modifiers() & Qt.KeyboardModifier.ControlModifier:
            if dy != 0:
                direction = 1 if dy > 0 else -1
                self.control_sender.enqueue(CMD_PINCH, build_pinch(norm[0], norm[1], direction))
        else:
            # Con lăn chuột thường: SCROLL
            if dx != 0 or dy != 0:
                self.control_sender.enqueue(CMD_SCROLL, build_scroll(norm[0], norm[1], dx, dy))

    # ------------------ SỰ KIỆN BÀN PHÍM ------------------

    def keyPressEvent(self, event: QKeyEvent):
        if not self.control_enabled or not self.control_sender:
            super().keyPressEvent(event)
            return

        key = event.key()
        modifiers = event.modifiers()

        # 1. Các tổ hợp phím đặc biệt
        if (modifiers & Qt.KeyboardModifier.ControlModifier) and key == Qt.Key.Key_A:
            self.control_sender.enqueue(CMD_KEY, build_key(KEY_SELECT_ALL))
            return

        # 2. Phím chức năng riêng lẻ
        key_map = {
            Qt.Key.Key_Backspace: KEY_BACKSPACE,
            Qt.Key.Key_Delete: KEY_DELETE,
            Qt.Key.Key_Left: KEY_LEFT,
            Qt.Key.Key_Right: KEY_RIGHT,
            Qt.Key.Key_Home: KEY_HOME_CURSOR,
            Qt.Key.Key_End: KEY_END_CURSOR,
            Qt.Key.Key_Return: KEY_ENTER,
            Qt.Key.Key_Enter: KEY_ENTER,
        }

        if key in key_map:
            self.control_sender.enqueue(CMD_KEY, build_key(key_map[key]))
            return

        # 3. Ký tự in được thông thường (không kèm Ctrl / Alt)
        if not (modifiers & (Qt.KeyboardModifier.ControlModifier | Qt.KeyboardModifier.AltModifier)):
            text = event.text()
            # Lọc các ký tự in được (mã >= 32)
            printable = "".join(ch for ch in text if ord(ch) >= 32)
            if printable:
                self.control_sender.enqueue(CMD_TEXT, build_text(printable))
                return

        super().keyPressEvent(event)

    def inputMethodEvent(self, event):
        """Hỗ trợ bộ gõ tiếng Việt Telex của Windows (commitString)."""
        if not self.control_enabled or not self.control_sender:
            super().inputMethodEvent(event)
            return

        commit_text = event.commitString()
        if commit_text:
            self.control_sender.enqueue(CMD_TEXT, build_text(commit_text))


class MainWindow(QMainWindow):
    """Cửa sổ chính của ứng dụng Windows kèm thanh điều khiển."""
    def __init__(self, display_queue, default_port=8554, control_sender=None, receiver=None, decoder=None):
        super().__init__()
        self.display_queue = display_queue
        self.default_port = default_port
        self.control_sender = control_sender
        self.receiver = receiver
        self.decoder = decoder

        self.local_ip = get_local_ip()
        self.frame_count = 0
        self.viewer_dropped_count = 0
        self.fps_timer_start = time.time()
        self.current_fps = 0.0
        self.is_connected = False
        self.control_flags = 0
        self.stats_log_counter = 0

        self.init_ui()

        # Kết nối callback kích thước đích tới decoder
        self.video_widget.on_target_size_changed = self._on_target_size_changed

        # Timer lấy khung hình từ hàng đợi với nhịp đều 16ms (PreciseTimer ~60 FPS)
        self.render_timer = QTimer(self)
        self.render_timer.setTimerType(Qt.TimerType.PreciseTimer)
        self.render_timer.timeout.connect(self.check_new_frame)
        self.render_timer.start(16)

        # Timer tính toán thống kê mỗi 1 giây
        self.stats_calc_timer = QTimer(self)
        self.stats_calc_timer.timeout.connect(self.update_statistics)
        self.stats_calc_timer.start(1000)

    def _on_target_size_changed(self, w: int, h: int):
        if self.decoder:
            self.decoder.set_target_resolution(w, h)

    def init_ui(self):
        """Khởi tạo bố cục giao diện."""
        self.setWindowTitle("Screen Share & Remote Control - Android to PC")
        self.resize(850, 800)
        self.setMinimumSize(450, 450)

        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(10, 10, 10, 10)
        main_layout.setSpacing(6)

        # 1. Thanh thông tin kết nối (Header Bar)
        header_frame = QFrame()
        header_frame.setStyleSheet("""
            QFrame {
                background-color: #1e1e24;
                border-radius: 8px;
                padding: 4px;
            }
            QLabel {
                color: #e0e0e0;
                font-family: 'Segoe UI', Arial;
                font-size: 13px;
            }
        """)
        header_layout = QHBoxLayout(header_frame)
        header_layout.setContentsMargins(12, 4, 12, 4)

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
        if self.control_sender:
            self.video_widget.set_control_sender(self.control_sender)
        main_layout.addWidget(self.video_widget, stretch=1)

        # 3. Bảng thống kê chi tiết (có thể bật/tắt)
        self.stats_frame = QFrame()
        self.stats_frame.setStyleSheet("""
            QFrame {
                background-color: #16161f;
                border: 1px solid #2e2e3e;
                border-radius: 6px;
                padding: 4px;
            }
            QLabel {
                color: #00e5ff;
                font-family: 'Consolas', 'Segoe UI', monospace;
                font-size: 12px;
            }
        """)
        stats_layout = QHBoxLayout(self.stats_frame)
        stats_layout.setContentsMargins(10, 4, 10, 4)
        self.lbl_stats_detail = QLabel("Đang thu thập thống kê...")
        stats_layout.addWidget(self.lbl_stats_detail)
        self.stats_frame.setVisible(False)
        main_layout.addWidget(self.stats_frame)

        # 4. Thanh công cụ điều khiển (Control Toolbar)
        control_frame = QFrame()
        control_frame.setStyleSheet("""
            QFrame {
                background-color: #1e1e24;
                border-radius: 8px;
                padding: 4px;
            }
            QPushButton {
                background-color: #2d2d38;
                color: #ffffff;
                border: 1px solid #444454;
                border-radius: 4px;
                padding: 6px 14px;
                font-weight: bold;
                font-size: 13px;
            }
            QPushButton:hover {
                background-color: #3e3e4e;
            }
            QPushButton:pressed {
                background-color: #1f1f28;
            }
            QPushButton:disabled {
                color: #666666;
                border-color: #333333;
                background-color: #1a1a20;
            }
            QCheckBox {
                color: #ffffff;
                font-weight: bold;
                font-size: 13px;
                spacing: 6px;
            }
            QCheckBox:disabled {
                color: #666666;
            }
            QLabel {
                color: #cccccc;
                font-size: 12px;
            }
        """)
        control_layout = QHBoxLayout(control_frame)
        control_layout.setContentsMargins(12, 4, 12, 4)

        # Các nút điều hướng hệ thống
        self.btn_back = QPushButton("◀ Back")
        self.btn_home = QPushButton("⌂ Home")
        self.btn_recents = QPushButton("▢ Recents")

        self.btn_back.clicked.connect(lambda: self._send_nav(NAV_BACK))
        self.btn_home.clicked.connect(lambda: self._send_nav(NAV_HOME))
        self.btn_recents.clicked.connect(lambda: self._send_nav(NAV_RECENTS))

        control_layout.addWidget(self.btn_back)
        control_layout.addWidget(self.btn_home)
        control_layout.addWidget(self.btn_recents)
        control_layout.addSpacing(15)

        # Hộp chọn bật/tắt điều khiển
        self.chk_control = QCheckBox("Bật điều khiển từ máy tính")
        self.chk_control.setEnabled(False)
        self.chk_control.toggled.connect(self.on_control_toggled)
        control_layout.addWidget(self.chk_control)

        # Hộp chọn Hiện thống kê
        self.chk_stats = QCheckBox("Hiện thống kê")
        self.chk_stats.toggled.connect(self.on_stats_toggled)
        control_layout.addWidget(self.chk_stats)

        # Nhãn hiển thị trạng thái và lý do
        self.lbl_control_status = QLabel("Điều khiển: <span style='color:#FFC107;'>TẮT (Chưa kết nối điện thoại)</span>")
        control_layout.addSpacing(10)
        control_layout.addWidget(self.lbl_control_status)
        control_layout.addStretch()

        main_layout.addWidget(control_frame)

    def _send_nav(self, nav_code: int):
        if self.control_sender and self.video_widget.control_enabled:
            self.control_sender.enqueue(CMD_NAV, build_nav(nav_code))

    def on_control_toggled(self, checked: bool):
        self.video_widget.set_control_enabled(checked)
        if checked:
            self.lbl_control_status.setText("<b style='color:#4CAF50;'>Điều khiển: BẬT</b>")
            self.video_widget.setFocus()
        else:
            self.lbl_control_status.setText("<span style='color:#FFC107;'>Điều khiển: TẮT</span>")

    def on_stats_toggled(self, checked: bool):
        self.stats_frame.setVisible(checked)

    def update_control_state(self, flags: int):
        """
        Cập nhật trạng thái điều khiển từ gói Loại 3 (CONTROL_STATE) do điện thoại gửi lên:
        - Bit 0: Công tắc cho phép điều khiển trong app đang bật
        - Bit 1: Dịch vụ Trợ năng Accessibility đang kết nối
        """
        self.control_flags = flags
        is_enabled = bool(flags & FLAG_CONTROL_ENABLED)
        is_acc = bool(flags & FLAG_ACCESSIBILITY_CONNECTED)

        if is_enabled and is_acc:
            self.chk_control.setEnabled(True)
            if self.chk_control.isChecked():
                self.video_widget.set_control_enabled(True)
                self.lbl_control_status.setText("<b style='color:#4CAF50;'>Điều khiển: BẬT</b>")
            else:
                self.video_widget.set_control_enabled(False)
                self.lbl_control_status.setText("<span style='color:#4CAF50;'>Điện thoại sẵn sàng (Hãy tick chọn để điều khiển)</span>")
        else:
            self.chk_control.setChecked(False)
            self.chk_control.setEnabled(False)
            self.video_widget.set_control_enabled(False)

            if not self.is_connected:
                reason = "Chưa kết nối điện thoại"
            elif not is_acc and not is_enabled:
                reason = "Chưa bật công tắc & Trợ năng trên điện thoại"
            elif not is_acc:
                reason = "Chưa bật dịch vụ Trợ năng trên điện thoại"
            else:
                reason = "Công tắc trên điện thoại đang Tắt"

            self.lbl_control_status.setText(f"<span style='color:#FFC107;'>Điều khiển: TẮT ({reason})</span>")

    def check_new_frame(self):
        """Kiểm tra và lấy khung hình từ display_queue theo cấu hình PRESENT_BUFFER_FRAMES."""
        if PRESENT_BUFFER_FRAMES <= 0:
            latest_frame = None
            while not self.display_queue.empty():
                try:
                    latest_frame = self.display_queue.get_nowait()
                except queue.Empty:
                    break

            if latest_frame is not None:
                self.video_widget.set_frame(latest_frame)
                self.frame_count += 1
        else:
            # Lấy khung hình theo thứ tự FIFO
            try:
                frame_item = self.display_queue.get_nowait()
                self.video_widget.set_frame(frame_item)
                self.frame_count += 1
            except queue.Empty:
                pass

            # Nếu hàng đợi bị dồn quá dung lượng đệm, xả bớt khung cũ nhất
            while self.display_queue.qsize() > PRESENT_BUFFER_FRAMES + 2:
                try:
                    self.display_queue.get_nowait()
                    self.viewer_dropped_count += 1
                except queue.Empty:
                    break

    def update_statistics(self):
        """Tính toán thống kê mỗi giây và hiển thị trên UI / ghi log mỗi 5s."""
        now = time.time()
        elapsed = now - self.fps_timer_start
        self.fps_timer_start = now

        # 1. FPS Hiển thị (Render FPS)
        render_fps = (self.frame_count / elapsed) if elapsed > 0 else 0.0
        self.current_fps = render_fps
        self.lbl_fps.setText(f"FPS: <b>{self.current_fps:.1f}</b>")
        self.frame_count = 0

        # 2. FPS Nhận từ Receiver
        rx_fps = 0.0
        if self.receiver:
            rx_count = self.receiver.get_and_reset_rx_stats()
            rx_fps = (rx_count / elapsed) if elapsed > 0 else 0.0

        # 3. FPS và chỉ số từ Decoder
        dec_fps = 0.0
        dec_dropped = 0
        avg_dec_ms = 0.0
        resync_count = 0
        if self.decoder:
            dec_cnt, d_drop, d_time, d_resync = self.decoder.get_and_reset_stats()
            dec_fps = (dec_cnt / elapsed) if elapsed > 0 else 0.0
            dec_dropped = d_drop
            avg_dec_ms = d_time
            resync_count = d_resync

        # 4. Thời gian vẽ trung bình từ VideoRenderWidget
        avg_paint_ms = self.video_widget.get_and_reset_paint_stats()

        # 5. Hàng đợi
        pkt_q_len = self.decoder.packet_queue.qsize() if self.decoder else 0
        disp_q_len = self.display_queue.qsize()
        total_dropped = dec_dropped + self.viewer_dropped_count
        self.viewer_dropped_count = 0

        # Hiển thị trên UI nếu bật checkbox
        if self.chk_stats.isChecked():
            stats_text = (
                f"📥 Nhận: <b>{rx_fps:.1f}</b> FPS | "
                f"⚙️ Giải mã: <b>{dec_fps:.1f}</b> FPS ({avg_dec_ms:.1f} ms) | "
                f"🖥️ Hiển thị: <b>{render_fps:.1f}</b> FPS ({avg_paint_ms:.1f} ms) | "
                f"📦 Hàng đợi: <b>{pkt_q_len}</b> gói / <b>{disp_q_len}</b> khung | "
                f"⚠️ Bỏ: <b>{total_dropped}</b> | "
                f"🔄 Nhảy Keyframe: <b>{resync_count}</b>"
            )
            self.lbl_stats_detail.setText(stats_text)

        # Ghi log mỗi 5 giây
        self.stats_log_counter += 1
        if self.stats_log_counter >= 5:
            self.stats_log_counter = 0
            if self.is_connected:
                logger.info(
                    f"[THỐNG KÊ 5S] RX: {rx_fps:.1f} FPS | "
                    f"Dec: {dec_fps:.1f} FPS (avg {avg_dec_ms:.1f}ms) | "
                    f"Render: {render_fps:.1f} FPS (avg {avg_paint_ms:.1f}ms) | "
                    f"Queue: {pkt_q_len} pkts / {disp_q_len} frames | "
                    f"Dropped: {total_dropped} | ResyncToKF: {resync_count}"
                )

    def update_status(self, connected, client_ip=None):
        """Cập nhật nhãn trạng thái kết nối từ client."""
        self.is_connected = connected
        if connected:
            self.lbl_status.setText(f"Trạng thái: <span style='color:#4CAF50;'>Đã kết nối ({client_ip})</span>")
        else:
            self.lbl_status.setText("Trạng thái: <span style='color:#FFC107;'>Đang chờ kết nối...</span>")
            self.lbl_fps.setText("FPS: <b>0.0</b>")
            self.lbl_resolution.setText("Độ phân giải: <b>---</b>")
            self.video_widget.clear_frame()
            self.update_control_state(0)

    def update_resolution(self, width, height):
        """Cập nhật nhãn độ phân giải khi nhận gói cấu hình hoặc xoay màn hình."""
        self.lbl_resolution.setText(f"Độ phân giải: <b>{width}x{height}</b>")
