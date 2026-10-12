"""
Module: receiver.py
Mục đích: Lắng nghe kết nối TCP từ điện thoại Android, nhận các gói dữ liệu H.264
          theo giao thức đã định nghĩa và đưa vào hàng đợi xử lý.
"""

import socket
import struct
import threading
import queue
import logging

logging.basicConfig(level=logging.INFO, format="[%(asctime)s][%(levelname)s] %(message)s")
logger = logging.getLogger("Receiver")

# Định nghĩa các loại gói tin theo giao thức
PACKET_TYPE_CONFIG = 0         # Gói cấu hình: SPS/PPS kèm chiều rộng và chiều cao (Width, Height)
PACKET_TYPE_KEYFRAME = 1       # Gói khung hình chính (I-frame / Keyframe)
PACKET_TYPE_DELTA = 2          # Gói khung hình thường (P-frame)
PACKET_TYPE_CONTROL_STATE = 3  # Gói trạng thái điều khiển từ điện thoại lên PC (bit0: enabled, bit1: accessibility)
PACKET_TYPE_RESYNC = 255       # Gói nội bộ: báo decoder chờ Keyframe tiếp theo (không đi qua mạng)


class StreamReceiver(threading.Thread):
    def __init__(self, host="0.0.0.0", port=8554, packet_queue=None,
                 on_client_connected=None, on_client_disconnected=None,
                 on_control_state_changed=None):
        """
        Khởi tạo TCP Server nhận luồng video và gửi lệnh điều khiển hai chiều.
        :param host: Địa chỉ lắng nghe (0.0.0.0 nhận từ mọi card mạng)
        :param port: Cổng TCP (mặc định 8554)
        :param packet_queue: Hàng đợi queue.Queue để đẩy (packet_type, payload) vào
        :param on_client_connected: Callback khi có điện thoại kết nối (addr)
        :param on_client_disconnected: Callback khi điện thoại ngắt kết nối ()
        :param on_control_state_changed: Callback khi nhận gói CONTROL_STATE (flags: int)
        """
        super().__init__(daemon=True)
        self.host = host
        self.port = port
        self.packet_queue = packet_queue if packet_queue is not None else queue.Queue(maxsize=120)
        self.on_client_connected = on_client_connected
        self.on_client_disconnected = on_client_disconnected
        self.on_control_state_changed = on_control_state_changed

        self.running = False
        self.server_sock = None
        self.client_sock = None
        self.client_addr = None
        self._send_lock = threading.Lock()

        # Thống kê số lượng gói nhận được
        self.received_packets_count = 0
        self._stats_lock = threading.Lock()

    def get_and_reset_rx_stats(self) -> int:
        """Trả về và đặt lại số gói nhận được để tính FPS nhận."""
        with self._stats_lock:
            cnt = self.received_packets_count
            self.received_packets_count = 0
            return cnt

    def run(self):
        """Vòng lặp chính của server: lắng nghe và xử lý kết nối."""
        self.running = True

        try:
            self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            # Cho phép sử dụng lại địa chỉ ngay lập tức nếu server vừa tắt
            self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_sock.bind((self.host, self.port))
            self.server_sock.listen(1)
            logger.info(f"Server đang lắng nghe tại {self.host}:{self.port}...")
        except Exception as e:
            logger.error(f"Lỗi khởi tạo Server Socket: {e}")
            self.running = False
            return

        while self.running:
            try:
                logger.info("Đang chờ điện thoại kết nối...")
                # Chấp nhận kết nối từ điện thoại
                client_sock, client_addr = self.server_sock.accept()
                self.client_sock = client_sock
                self.client_addr = client_addr

                # Bật TCP_NODELAY để tắt thuật toán Nagle, giảm tối đa độ trễ
                self.client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                logger.info(f"Đã kết nối với điện thoại: {client_addr}")

                if self.on_client_connected:
                    self.on_client_connected(client_addr)

                # Bắt đầu vòng lặp đọc dữ liệu từ điện thoại này
                self._handle_client()

            except socket.error as e:
                if self.running:
                    logger.warning(f"Lỗi kết nối hoặc client ngắt kết nối: {e}")
            finally:
                # Dọn dẹp tài nguyên client sau khi ngắt kết nối
                self._cleanup_client()
                if self.on_client_disconnected:
                    self.on_client_disconnected()

        # Đóng server socket khi dừng hẳn thread
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass
            self.server_sock = None
        logger.info("TCP Server đã dừng hoàn toàn.")

    def _handle_client(self):
        """
        Đọc liên tục các gói tin từ socket của client.
        Định dạng gói:
        [4 byte Payload Length (big-endian)] [1 byte Packet Type] [Payload Data (Payload Length bytes)]
        """
        while self.running and self.client_sock:
            # 1. Đọc 4 byte độ dài của payload
            length_bytes = self._read_exact(4)
            if not length_bytes:
                logger.info("Client đã ngắt kết nối (không còn dữ liệu).")
                break

            payload_len = struct.unpack(">I", length_bytes)[0]

            # Kiểm tra an toàn: kích thước gói không hợp lý (lớn hơn 10MB là bất thường)
            if payload_len > 10 * 1024 * 1024:
                logger.error(f"Kích thước gói quá lớn bất thường: {payload_len} bytes. Đóng kết nối.")
                break

            # 2. Đọc 1 byte loại gói tin (Packet Type)
            type_bytes = self._read_exact(1)
            if not type_bytes:
                break
            packet_type = type_bytes[0]

            # 3. Đọc đúng payload_len bytes dữ liệu payload
            payload_data = self._read_exact(payload_len)
            if not payload_data:
                break

            # Xử lý gói Loại 3 (CONTROL_STATE) từ điện thoại gửi lên
            if packet_type == PACKET_TYPE_CONTROL_STATE:
                if len(payload_data) >= 1 and self.on_control_state_changed:
                    try:
                        self.on_control_state_changed(payload_data[0])
                    except Exception as e:
                        logger.error(f"Lỗi callback CONTROL_STATE: {e}")
                # Không đẩy gói CONTROL_STATE vào hàng đợi video
                continue

            # Đẩy gói tin vào hàng đợi.
            # Nếu hàng đợi đầy: KHÔNG bỏ lẻ 1 gói (sẽ làm vỡ hình). Xóa hết rồi báo
            # decoder chờ Keyframe tiếp theo (gói RESYNC).
            with self._stats_lock:
                self.received_packets_count += 1

            try:
                self.packet_queue.put_nowait((packet_type, payload_data))
            except queue.Full:
                try:
                    while True:
                        self.packet_queue.get_nowait()
                except queue.Empty:
                    pass
                self.packet_queue.put((PACKET_TYPE_RESYNC, b""))
                self.packet_queue.put((packet_type, payload_data))

    def send_bytes(self, data: bytes) -> bool:
        """
        Gửi dữ liệu nhị phân xuống client đang kết nối (thread-safe).
        Bỏ qua im lặng và trả về False nếu chưa có client kết nối.
        """
        with self._send_lock:
            sock = self.client_sock
            if not sock or not self.running:
                return False
            try:
                sock.sendall(data)
                return True
            except Exception as e:
                logger.warning(f"Lỗi khi gửi dữ liệu xuống client: {e}")
                return False

    def _read_exact(self, num_bytes):
        """
        Đọc chính xác num_bytes từ socket. TCP là stream byte nên có thể
        nhận từng mảnh nhỏ, hàm này sẽ gom đủ số byte yêu cầu trước khi trả về.
        """
        buffer = bytearray()
        while len(buffer) < num_bytes and self.running and self.client_sock:
            try:
                chunk = self.client_sock.recv(num_bytes - len(buffer))
                if not chunk:
                    return None  # Socket đã bị đóng
                buffer.extend(chunk)
            except (socket.timeout, BlockingIOError):
                continue
            except Exception as e:
                logger.warning(f"Lỗi khi đọc socket: {e}")
                return None
        return bytes(buffer)

    def _cleanup_client(self):
        """Đóng socket client và reset trạng thái."""
        if self.client_sock:
            try:
                self.client_sock.close()
            except Exception:
                pass
            self.client_sock = None
            self.client_addr = None

        # Báo trạng thái điều khiển về 0 khi client ngắt kết nối
        if self.on_control_state_changed:
            try:
                self.on_control_state_changed(0)
            except Exception:
                pass

    def stop(self):
        """Dừng server an toàn."""
        self.running = False
        self._cleanup_client()
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass
