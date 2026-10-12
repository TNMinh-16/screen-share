"""
Module: control.py
Mục đích: Đóng gói và quản lý luồng gửi lệnh điều khiển từ Windows xuống điện thoại Android.
          Giao thức nhị phân: [4 byte payload_length, big-endian] [1 byte cmd_type] [payload]
"""

import struct
import threading
import queue
import logging
from typing import Optional, Tuple

logger = logging.getLogger("Control")

# Định nghĩa các mã lệnh điều khiển (PC -> Android)
CMD_TOUCH_DOWN = 0x10   # 16: x(u16), y(u16)
CMD_TOUCH_MOVE = 0x11   # 17: x(u16), y(u16)
CMD_TOUCH_UP   = 0x12   # 18: x(u16), y(u16)
CMD_SCROLL     = 0x13   # 19: x(u16), y(u16), dx(i16), dy(i16)
CMD_PINCH      = 0x14   # 20: x(u16), y(u16), direction(i8: +1 zoom in, -1 zoom out)
CMD_TEXT       = 0x15   # 21: UTF-8 string bytes
CMD_KEY        = 0x16   # 22: 1 byte mã phím
CMD_NAV        = 0x17   # 23: 1 byte mã điều hướng

# Mã phím (KEY)
KEY_BACKSPACE   = 1
KEY_DELETE      = 2
KEY_LEFT        = 3
KEY_RIGHT       = 4
KEY_HOME_CURSOR = 5
KEY_END_CURSOR  = 6
KEY_ENTER       = 7
KEY_SELECT_ALL  = 8

# Mã điều hướng (NAV)
NAV_BACK    = 1
NAV_HOME    = 2
NAV_RECENTS = 3

# Gói trạng thái điều khiển từ điện thoại lên PC (Android -> PC)
PACKET_TYPE_CONTROL_STATE = 3
FLAG_CONTROL_ENABLED = 0x01      # Bit 0: Công tắc cho phép điều khiển trong app đang bật
FLAG_ACCESSIBILITY_CONNECTED = 0x02  # Bit 1: Dịch vụ Trợ năng Accessibility đang hoạt động


def build_packet(cmd_type: int, payload: bytes) -> bytes:
    """
    Đóng gói dữ liệu theo chuẩn giao thức:
    [4 byte payload length, big-endian] [1 byte cmd_type] [payload]
    """
    length = len(payload)
    return struct.pack(f">IB{length}s", length, cmd_type, payload)


def build_touch_down(x: int, y: int) -> bytes:
    """Tạo gói TOUCH_DOWN với tọa độ chuẩn hóa 0..65535."""
    x = max(0, min(65535, int(x)))
    y = max(0, min(65535, int(y)))
    payload = struct.pack(">HH", x, y)
    return build_packet(CMD_TOUCH_DOWN, payload)


def build_touch_move(x: int, y: int) -> bytes:
    """Tạo gói TOUCH_MOVE với tọa độ chuẩn hóa 0..65535."""
    x = max(0, min(65535, int(x)))
    y = max(0, min(65535, int(y)))
    payload = struct.pack(">HH", x, y)
    return build_packet(CMD_TOUCH_MOVE, payload)


def build_touch_up(x: int, y: int) -> bytes:
    """Tạo gói TOUCH_UP với tọa độ chuẩn hóa 0..65535."""
    x = max(0, min(65535, int(x)))
    y = max(0, min(65535, int(y)))
    payload = struct.pack(">HH", x, y)
    return build_packet(CMD_TOUCH_UP, payload)


def build_scroll(x: int, y: int, dx: int, dy: int) -> bytes:
    """
    Tạo gói SCROLL với tọa độ và độ lăn chuột (angleDelta của Qt).
    dx, dy là số nguyên có dấu 16-bit (-32768..32767).
    """
    x = max(0, min(65535, int(x)))
    y = max(0, min(65535, int(y)))
    dx = max(-32768, min(32767, int(dx)))
    dy = max(-32768, min(32767, int(dy)))
    payload = struct.pack(">HHhh", x, y, dx, dy)
    return build_packet(CMD_SCROLL, payload)


def build_pinch(x: int, y: int, direction: int) -> bytes:
    """
    Tạo gói PINCH: x(u16), y(u16), direction(i8: +1 phóng to, -1 thu nhỏ).
    """
    x = max(0, min(65535, int(x)))
    y = max(0, min(65535, int(y)))
    dir_byte = 1 if direction >= 0 else -1
    payload = struct.pack(">HHb", x, y, dir_byte)
    return build_packet(CMD_PINCH, payload)


def build_text(text: str) -> bytes:
    """
    Tạo gói TEXT chứa chuỗi UTF-8.
    TUYỆT ĐỐI không ghi nội dung văn bản vào log (chỉ log độ dài).
    """
    utf8_bytes = text.encode("utf-8")
    return build_packet(CMD_TEXT, utf8_bytes)


def build_key(key_code: int) -> bytes:
    """Tạo gói KEY chứa 1 byte mã phím chức năng."""
    payload = struct.pack("B", key_code)
    return build_packet(CMD_KEY, payload)


def build_nav(nav_code: int) -> bytes:
    """Tạo gói NAV chứa 1 byte mã điều hướng hệ thống (Back/Home/Recents)."""
    payload = struct.pack("B", nav_code)
    return build_packet(CMD_NAV, payload)


def map_mouse_to_normalized(
    mx: float, my: float,
    widget_w: int, widget_h: int,
    video_w: int, video_h: int
) -> Optional[Tuple[int, int]]:
    """
    Chuyển đổi tọa độ chuột trên Widget sang tọa độ chuẩn hóa (0..65535) của video.
    Có tính đến vùng viền đen do giữ nguyên tỉ lệ màn hình (Aspect Ratio).
    Nếu chuột nằm ngoài vùng video thực tế, trả về None.
    """
    if widget_w <= 0 or widget_h <= 0 or video_w <= 0 or video_h <= 0:
        return None

    scale = min(widget_w / video_w, widget_h / video_h)
    draw_w = int(video_w * scale)
    draw_h = int(video_h * scale)
    draw_x = int((widget_w - draw_w) / 2)
    draw_y = int((widget_h - draw_h) / 2)

    if draw_w <= 0 or draw_h <= 0:
        return None

    # Kiểm tra xem chuột có nằm trong vùng hình ảnh hay không
    if mx < draw_x or mx > draw_x + draw_w or my < draw_y or my > draw_y + draw_h:
        return None

    rel_x = mx - draw_x
    rel_y = my - draw_y

    norm_x = int((rel_x / draw_w) * 65535)
    norm_y = int((rel_y / draw_h) * 65535)

    norm_x = max(0, min(65535, norm_x))
    norm_y = max(0, min(65535, norm_y))

    return norm_x, norm_y


def clamp_mouse_to_normalized(
    mx: float, my: float,
    widget_w: int, widget_h: int,
    video_w: int, video_h: int
) -> Optional[Tuple[int, int]]:
    """
    Tương tự map_mouse_to_normalized nhưng kẹp (clamp) tọa độ vào vùng video khi kéo chuột ra ngoài viền.
    Dùng cho sự kiện TOUCH_MOVE và TOUCH_UP khi người dùng đang kéo ngón tay ra mép màn hình.
    """
    if widget_w <= 0 or widget_h <= 0 or video_w <= 0 or video_h <= 0:
        return None

    scale = min(widget_w / video_w, widget_h / video_h)
    draw_w = int(video_w * scale)
    draw_h = int(video_h * scale)
    draw_x = int((widget_w - draw_w) / 2)
    draw_y = int((widget_h - draw_h) / 2)

    if draw_w <= 0 or draw_h <= 0:
        return None

    rel_x = max(0.0, min(float(draw_w), float(mx - draw_x)))
    rel_y = max(0.0, min(float(draw_h), float(my - draw_y)))

    norm_x = int((rel_x / draw_w) * 65535)
    norm_y = int((rel_y / draw_h) * 65535)

    norm_x = max(0, min(65535, norm_x))
    norm_y = max(0, min(65535, norm_y))

    return norm_x, norm_y


class ControlSender(threading.Thread):
    """
    Luồng gửi lệnh điều khiển xuống Android qua kết nối TCP.
    - Hàng đợi đảm bảo các lệnh đi đúng thứ tự.
    - Đối với TOUCH_MOVE: tự động gộp (coalescing) các lệnh MOVE đang chờ để chỉ gửi vị trí mới nhất.
    - Tuyệt đối không bỏ các lệnh TOUCH_DOWN, TOUCH_UP, TEXT, KEY, NAV, SCROLL, PINCH.
    """
    def __init__(self, send_fn):
        super().__init__(daemon=True)
        self.send_fn = send_fn  # Hàm gửi socket (ví dụ receiver.send_bytes)
        self._lock = threading.Lock()
        self._queue = []
        self._cv = threading.Condition(self._lock)
        self.running = False

    def enqueue(self, cmd_type: int, packet_bytes: bytes):
        """Đẩy lệnh vào hàng đợi gửi."""
        with self._lock:
            if not self.running:
                return

            # Nếu là TOUCH_MOVE: loại bỏ các TOUCH_MOVE cũ đang chờ trong hàng đợi để giảm trễ
            if cmd_type == CMD_TOUCH_MOVE:
                self._queue = [item for item in self._queue if item[0] != CMD_TOUCH_MOVE]

            self._queue.append((cmd_type, packet_bytes))
            self._cv.notify()

    def run(self):
        self.running = True
        while self.running:
            with self._lock:
                while self.running and not self._queue:
                    self._cv.wait(timeout=0.1)

                if not self.running:
                    break

                if not self._queue:
                    continue

                cmd_type, packet_bytes = self._queue.pop(0)

            try:
                self.send_fn(packet_bytes)
            except Exception as e:
                logger.warning(f"Lỗi gửi lệnh điều khiển (loại {cmd_type}): {e}")

        logger.info("ControlSender thread đã dừng.")

    def stop(self):
        with self._lock:
            self.running = False
            self._queue.clear()
            self._cv.notify_all()
