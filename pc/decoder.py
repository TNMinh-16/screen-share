"""
Module: decoder.py
Mục đích: Giải mã các gói tin video H.264 nhận được từ mạng LAN bằng PyAV.
          Tuân thủ nguyên tắc:
          1. Giải mã mọi gói để giữ nguyên chuỗi tham chiếu H.264.
          2. Nếu decoder bị chậm quá mức, chuyển chế độ bỏ qua delta-frame và nhảy thẳng đến Keyframe tiếp theo.
          3. Chỉ hiển thị khung hình mới nhất trên UI (bỏ qua khung cũ để triệt tiêu độ trễ).
          4. Chỉ bắt đầu giải mã khi đã có gói cấu hình (SPS/PPS) và Keyframe đầu tiên.
"""

import struct
import threading
import queue
import logging
import av

logger = logging.getLogger("Decoder")

# Ngưỡng số lượng gói dồn trong hàng đợi mạng trước khi kích hoạt nhảy đến Keyframe tiếp theo (50 gói phù hợp với 60 FPS)
LAG_PACKET_THRESHOLD = 50


class H264Decoder(threading.Thread):
    def __init__(self, packet_queue, display_queue, on_dimension_changed=None):
        """
        Khởi tạo luồng giải mã H.264.
        :param packet_queue: Hàng đợi chứa (packet_type, payload) từ receiver
        :param display_queue: Hàng đợi chứa khung hình RGB mới nhất để hiển thị
        :param on_dimension_changed: Callback khi kích thước video thay đổi (width, height)
        """
        super().__init__(daemon=True)
        self.packet_queue = packet_queue
        self.display_queue = display_queue
        self.on_dimension_changed = on_dimension_changed

        self.running = False
        self.codec = None
        self.has_config = False
        self.has_first_keyframe = False
        self.skipping_to_keyframe = False

        self.width = 0
        self.height = 0
        self.current_sps_pps = None
        self._reset_requested = False

    def _init_codec(self, extradata=None):
        """Khởi tạo hoặc reset lại FFmpeg CodecContext với độ trễ thấp nhất."""
        try:
            self.codec = av.CodecContext.create("h264", "r")
            # Cấu hình flag low_delay cho FFmpeg
            self.codec.flags |= av.codec.context.Flags.low_delay
            self.codec.options = {
                "flags": "low_delay",
                "tune": "zerolatency",
            }
            if extradata:
                self.codec.extradata = extradata
            self.codec.open()
            logger.info("Đã khởi tạo bộ giải mã PyAV H.264 (low_delay, zerolatency).")
        except Exception as e:
            logger.error(f"Lỗi khởi tạo codec H.264: {e}")
            self.codec = None

    def reset(self):
        """
        Yêu cầu reset khi client ngắt kết nối (được gọi từ luồng Receiver).
        Chỉ đặt cờ và xóa gói cũ; việc reset thật do chính luồng decoder làm
        để không xảy ra tranh chấp (race) khi đang giải mã dở.
        """
        self._reset_requested = True
        try:
            while True:
                self.packet_queue.get_nowait()
        except queue.Empty:
            pass

    def _do_reset(self):
        """Reset toàn bộ trạng thái phiên (chạy trong luồng decoder)."""
        self._reset_requested = False
        self.has_config = False
        self.has_first_keyframe = False
        self.skipping_to_keyframe = False
        self.width = 0
        self.height = 0
        self.current_sps_pps = None
        self._init_codec()

    def run(self):
        """Vòng lặp giải mã lấy gói tin từ packet_queue."""
        self.running = True
        self._init_codec()

        while self.running:
            if self._reset_requested:
                self._do_reset()
            try:
                # Đợi gói tin mới với timeout 0.1s để có thể kiểm tra cờ self.running
                packet_item = self.packet_queue.get(timeout=0.1)
            except queue.Empty:
                continue

            if packet_item is None:
                # Tín hiệu dừng luồng
                break

            packet_type, payload = packet_item

            try:
                self._process_packet(packet_type, payload)
            except Exception as e:
                logger.error(f"Lỗi giải mã gói (loại {packet_type}): {e}")

        logger.info("Decoder thread đã dừng.")

    def _process_packet(self, packet_type, payload):
        """Xử lý từng loại gói tin theo quy tắc đồng bộ video."""

        # 0. Gói RESYNC (do Receiver gửi khi hàng đợi tràn): chờ Keyframe tiếp theo
        if packet_type == 255:
            self.skipping_to_keyframe = True
            return

        # 1. Gói Loại 0: Cấu hình (Width, Height, SPS/PPS)
        if packet_type == 0:
            if len(payload) < 8:
                logger.warning("Gói cấu hình quá ngắn (< 8 bytes).")
                return

            new_width, new_height = struct.unpack(">II", payload[:8])
            sps_pps = payload[8:]

            dimension_changed = (new_width != self.width or new_height != self.height)
            config_changed = (sps_pps != getattr(self, "current_sps_pps", None))

            if dimension_changed or config_changed or self.codec is None:
                logger.info(f"Cấu hình thay đổi -> Khởi tạo lại codec: {new_width}x{new_height}")
                self._init_codec(extradata=sps_pps if sps_pps else None)
                self.width = new_width
                self.height = new_height
                self.current_sps_pps = sps_pps
                self.has_first_keyframe = False
                self.skipping_to_keyframe = False

                if dimension_changed and self.on_dimension_changed:
                    self.on_dimension_changed(self.width, self.height)

            self.has_config = True
            return

        # Kiểm tra điều kiện tiên quyết: phải có gói cấu hình trước
        if not self.has_config:
            # Chưa có cấu hình thì không thể giải mã các frame tiếp theo
            return

        # 2. Gói Loại 1: Keyframe (I-frame)
        if packet_type == 1:
            self.has_first_keyframe = True
            # Nếu đang ở chế độ bỏ qua vì lag, gặp Keyframe thì khôi phục giải mã bình thường
            if self.skipping_to_keyframe:
                logger.info("Đã gặp Keyframe tiếp theo, khôi phục giải mã bình thường.")
                self.skipping_to_keyframe = False

            # Gắn SPS/PPS vào đầu keyframe (in-band) để luôn giải mã được,
            # kể cả sau khi kết nối lại.
            if self.current_sps_pps:
                payload = self.current_sps_pps + payload
            self._decode_and_push(payload)
            return

        # 3. Gói Loại 2: Frame thường (P-frame / Delta frame)
        if packet_type == 2:
            # Chưa có Keyframe đầu tiên -> bắt buộc bỏ qua để tránh vỡ hình
            if not self.has_first_keyframe:
                return

            # Nếu đang trong trạng thái bỏ qua do decoder bị chậm -> tiếp tục bỏ qua P-frame này
            if self.skipping_to_keyframe:
                return

            # Kiểm tra xem hàng đợi nhận có đang bị dồn ứ không
            # Nếu bị dồn quá LAG_PACKET_THRESHOLD gói, bật cờ nhảy đến Keyframe tiếp theo
            queue_len = self.packet_queue.qsize()
            if queue_len > LAG_PACKET_THRESHOLD:
                logger.warning(f"Hàng đợi dồn {queue_len} gói (> {LAG_PACKET_THRESHOLD}). Kích hoạt bỏ qua đến Keyframe tiếp theo để triệt tiêu độ trễ!")
                self.skipping_to_keyframe = True
                return

            self._decode_and_push(payload)

    def _decode_and_push(self, payload):
        """Gửi gói dữ liệu H.264 vào PyAV để giải mã và đẩy khung hình mới nhất vào display_queue."""
        if not self.codec:
            return

        av_pkt = av.Packet(payload)
        try:
            frames = self.codec.decode(av_pkt)
        except Exception as e:
            # Nếu gặp lỗi giải mã (ví dụ dữ liệu mạng bị suy hao), kích hoạt nhảy đến Keyframe tiếp theo
            logger.warning(f"Lỗi khi giải mã frame: {e}. Bỏ qua đến Keyframe sau.")
            self.skipping_to_keyframe = True
            return

        for frame in frames:
            # Chuyển đổi khung hình sang mảng numpy RGB
            # Định dạng RGB24 thuận tiện để render lên PySide6 QImage
            rgb_array = frame.to_ndarray(format="rgb24")

            # Đẩy vào display_queue
            # Theo yêu cầu: Chỉ giữ khung hình MỚI NHẤT, bỏ qua khung cũ chưa kịp vẽ
            while not self.display_queue.empty():
                try:
                    self.display_queue.get_nowait()
                except queue.Empty:
                    break

            try:
                self.display_queue.put_nowait(rgb_array)
            except queue.Full:
                pass

    def stop(self):
        """Dừng decoder luồng an toàn."""
        self.running = False
        self.packet_queue.put(None)  # Đánh thức queue.get() nếu đang đợi
