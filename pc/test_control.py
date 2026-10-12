"""
Unit Test kiểm tra module điều khiển pc/control.py:
1. Đóng gói đúng định dạng nhị phân [4 byte len][1 byte type][payload].
2. Kiểm tra các hàm build: TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP, SCROLL, PINCH, TEXT, KEY, NAV.
3. Kiểm tra hàm map_mouse_to_normalized có tính đến viền đen (letterboxing).
4. Kiểm tra cơ chế gộp TOUCH_MOVE trong ControlSender.
"""

import struct
import time
import unittest
from control import (
    CMD_TOUCH_DOWN, CMD_TOUCH_MOVE, CMD_TOUCH_UP, CMD_SCROLL, CMD_PINCH,
    CMD_TEXT, CMD_KEY, CMD_NAV, KEY_BACKSPACE, KEY_ENTER, NAV_BACK,
    build_touch_down, build_touch_move, build_touch_up, build_scroll, build_pinch,
    build_text, build_key, build_nav, map_mouse_to_normalized, clamp_mouse_to_normalized,
    ControlSender
)


class TestControlProtocol(unittest.TestCase):

    def test_touch_packets(self):
        # 1. TOUCH_DOWN
        pkt = build_touch_down(1000, 2000)
        self.assertEqual(len(pkt), 4 + 1 + 4)
        length, cmd_type, x, y = struct.unpack(">IBHH", pkt)
        self.assertEqual(length, 4)
        self.assertEqual(cmd_type, CMD_TOUCH_DOWN)
        self.assertEqual(x, 1000)
        self.assertEqual(y, 2000)

        # 2. Clamping tọa độ
        pkt_clamped = build_touch_move(-50, 70000)
        _, _, cx, cy = struct.unpack(">IBHH", pkt_clamped)
        self.assertEqual(cx, 0)
        self.assertEqual(cy, 65535)

        # 3. TOUCH_UP
        pkt_up = build_touch_up(32768, 16384)
        length, cmd_type, ux, uy = struct.unpack(">IBHH", pkt_up)
        self.assertEqual(length, 4)
        self.assertEqual(cmd_type, CMD_TOUCH_UP)
        self.assertEqual(ux, 32768)
        self.assertEqual(uy, 16384)

    def test_scroll_packet(self):
        pkt = build_scroll(100, 200, -120, 240)
        self.assertEqual(len(pkt), 4 + 1 + 8)
        length, cmd_type, x, y, dx, dy = struct.unpack(">IBHHhh", pkt)
        self.assertEqual(length, 8)
        self.assertEqual(cmd_type, CMD_SCROLL)
        self.assertEqual((x, y, dx, dy), (100, 200, -120, 240))

    def test_pinch_packet(self):
        pkt = build_pinch(500, 600, +1)
        self.assertEqual(len(pkt), 4 + 1 + 5)
        length, cmd_type, x, y, direction = struct.unpack(">IBHHb", pkt)
        self.assertEqual(length, 5)
        self.assertEqual(cmd_type, CMD_PINCH)
        self.assertEqual(direction, 1)

        pkt_out = build_pinch(500, 600, -1)
        _, _, _, _, dir_out = struct.unpack(">IBHHb", pkt_out)
        self.assertEqual(dir_out, -1)

    def test_text_packet(self):
        vietnamese_text = "Việt Nam 🇻🇳 123"
        pkt = build_text(vietnamese_text)
        utf8_bytes = vietnamese_text.encode("utf-8")
        self.assertEqual(len(pkt), 4 + 1 + len(utf8_bytes))
        length, cmd_type = struct.unpack(">IB", pkt[:5])
        self.assertEqual(length, len(utf8_bytes))
        self.assertEqual(cmd_type, CMD_TEXT)
        self.assertEqual(pkt[5:].decode("utf-8"), vietnamese_text)

    def test_key_and_nav_packets(self):
        pkt_key = build_key(KEY_BACKSPACE)
        self.assertEqual(len(pkt_key), 4 + 1 + 1)
        length, cmd_type, key = struct.unpack(">IBB", pkt_key)
        self.assertEqual(length, 1)
        self.assertEqual(cmd_type, CMD_KEY)
        self.assertEqual(key, KEY_BACKSPACE)

        pkt_nav = build_nav(NAV_BACK)
        self.assertEqual(len(pkt_nav), 4 + 1 + 1)
        length, cmd_type, nav = struct.unpack(">IBB", pkt_nav)
        self.assertEqual(length, 1)
        self.assertEqual(cmd_type, CMD_NAV)
        self.assertEqual(nav, NAV_BACK)

    def test_map_mouse_to_normalized(self):
        # Widget 800x600, Video 1000x500 (Tỉ lệ 2:1) -> Video vẽ ở 800x400, viền đen trên dưới 100px mỗi bên
        widget_w, widget_h = 800, 600
        video_w, video_h = 1000, 500

        # Chuột ở giữa màn hình (400, 300) -> chính là tâm video
        res = map_mouse_to_normalized(400, 300, widget_w, widget_h, video_w, video_h)
        self.assertIsNotNone(res)
        norm_x, norm_y = res
        self.assertAlmostEqual(norm_x, 32767, delta=10)
        self.assertAlmostEqual(norm_y, 32767, delta=10)

        # Chuột ở viền đen phía trên (y < 100) -> trả về None
        self.assertIsNone(map_mouse_to_normalized(400, 50, widget_w, widget_h, video_w, video_h))

        # Clamp khi đang kéo ra viền đen
        clamped = clamp_mouse_to_normalized(400, 50, widget_w, widget_h, video_w, video_h)
        self.assertIsNotNone(clamped)
        cx, cy = clamped
        self.assertEqual(cy, 0)  # Kẹp về mép trên cùng của video

    def test_control_sender_coalescing(self):
        sent_packets = []

        def mock_send(data):
            sent_packets.append(data)
            time.sleep(0.01)  # Giả lập độ trễ mạng nhẹ

        sender = ControlSender(mock_send)
        sender.start()

        # Đẩy DOWN, sau đó 5 lệnh MOVE liên tiếp, rồi UP
        sender.enqueue(CMD_TOUCH_DOWN, build_touch_down(100, 100))
        for i in range(1, 6):
            sender.enqueue(CMD_TOUCH_MOVE, build_touch_move(100 + i * 10, 100 + i * 10))
        sender.enqueue(CMD_TOUCH_UP, build_touch_up(200, 200))

        time.sleep(0.15)
        sender.stop()

        # Kiểm tra gói đầu tiên là DOWN, gói cuối cùng là UP
        self.assertGreaterEqual(len(sent_packets), 2)
        _, first_type = struct.unpack(">IB", sent_packets[0][:5])
        _, last_type = struct.unpack(">IB", sent_packets[-1][:5])
        self.assertEqual(first_type, CMD_TOUCH_DOWN)
        self.assertEqual(last_type, CMD_TOUCH_UP)
        # Các gói MOVE phải được gộp (ít hơn 5 lệnh MOVE được gửi đi)
        self.assertLess(len(sent_packets), 7)


if __name__ == "__main__":
    unittest.main()
