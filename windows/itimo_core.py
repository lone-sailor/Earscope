"""
itimo_core.py
-------------
Reverse-engineered protocol for the iTiMO-compatible ear-cleaner/endoscope
camera (com.molink.john.itimo). All plain UDP, no encryption, LAN only.
"""

import socket
import struct
import threading
import time

CAMERA_PORT = 8031
CONTROL_PORT = 50000
HEADER_LEN = 24
MAGIC = 0x66
TRIGGER = bytes.fromhex("999901000000000000000000000000000000000000000000")
STALL_AFTER = 2.0

_SETCMD_SUFFIX = bytes.fromhex("00009000040000000000")


def _build_setcmd(seq):
    return b"SETCMD" + struct.pack("<H", seq & 0xFFFF) + _SETCMD_SUFFIX


def _make_video_socket(timeout=0.5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512 * 1024)
    except OSError:
        pass
    sock.settimeout(timeout)
    return sock


class CameraStream:
    def __init__(self, camera_ip, on_state_change=None, on_error=None):
        self.camera_ip = camera_ip
        self.on_state_change = on_state_change or (lambda state, detail=None: None)
        self.on_error = on_error or (lambda message: None)

        self._stop_flag = threading.Event()
        self._lock = threading.Lock()
        self._latest_frame = None
        self._latest_frame_time = 0.0
        self._frame_count = 0
        self._thread = None
        self._heartbeat_thread = None
        self._state = "stopped"

        # Battery tracking: status ("unknown", "good", "normal", "low"), is_low (bool)
        self._battery_status = "unknown"
        self._is_low_battery = False

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop_flag.clear()
        self._thread = threading.Thread(target=self._run_video_loop, daemon=True)
        self._thread.start()
        self._heartbeat_thread = threading.Thread(target=self._run_heartbeat, daemon=True)
        self._heartbeat_thread.start()

    def stop(self):
        self._stop_flag.set()
        self._set_state("stopped")

    def get_latest_frame(self):
        with self._lock:
            if self._latest_frame is None:
                return None, None
            return self._latest_frame, time.time() - self._latest_frame_time

    def get_frame_count(self):
        with self._lock:
            return self._frame_count

    def get_state(self):
        with self._lock:
            return self._state

    def get_battery_status(self):
        """Returns (status_label: str, is_low: bool)"""
        with self._lock:
            return self._battery_status, self._is_low_battery

    def _set_state(self, state, detail=None):
        with self._lock:
            changed = state != self._state
            self._state = state
        if changed:
            try:
                self.on_state_change(state, detail)
            except Exception:
                pass

    def _run_heartbeat(self, interval=0.08):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        seq = 1
        while not self._stop_flag.is_set():
            try:
                sock.sendto(_build_setcmd(seq), (self.camera_ip, CONTROL_PORT))
            except OSError:
                pass
            seq = (seq + 1) & 0xFFFF
            time.sleep(interval)
        try:
            sock.close()
        except OSError:
            pass

    def _run_video_loop(self):
        self._set_state("connecting")
        sock = None
        current = {}
        expected_size = None
        frame_started_at = None
        last_keepalive = 0.0
        last_good_frame_at = time.time()

        while not self._stop_flag.is_set():
            try:
                if sock is None:
                    sock = _make_video_socket()
                    sock.sendto(TRIGGER, (self.camera_ip, CAMERA_PORT))
                    last_keepalive = time.time()

                try:
                    data, addr = sock.recvfrom(65536)
                except socket.timeout:
                    sock.sendto(TRIGGER, (self.camera_ip, CAMERA_PORT))
                    current, expected_size, frame_started_at = {}, None, None
                    last_keepalive = time.time()
                    if self._stop_flag.is_set():
                        break
                    if time.time() - last_good_frame_at > STALL_AFTER:
                        self._set_state("stalled")
                    continue

                now = time.time()

                if frame_started_at and (now - frame_started_at) > 0.5:
                    current, expected_size, frame_started_at = {}, None, None

                if now - last_keepalive > 1.0:
                    sock.sendto(TRIGGER, (self.camera_ip, CAMERA_PORT))
                    last_keepalive = now

                if len(data) < HEADER_LEN or data[0] != MAGIC:
                    continue

                flag = data[1]
                frame_size = struct.unpack("<H", data[4:6])[0]
                idx = struct.unpack("<H", data[12:14])[0]
                payload_len = struct.unpack("<H", data[14:16])[0]
                payload = data[HEADER_LEN:]

                # Decode battery/power grade from byte 16 of the first packet
                if flag == 0x01:
                    raw_grade = data[16]
                    is_critical = (raw_grade in (1, 2))
                    with self._lock:
                        self._is_low_battery = is_critical
                        if is_critical:
                            self._battery_status = "low"
                        elif raw_grade in (3, 4):
                            self._battery_status = "normal"
                        elif raw_grade >= 5:
                            self._battery_status = "good"
                        else:
                            self._battery_status = "unknown"

                if len(payload) != payload_len:
                    current, expected_size, frame_started_at = {}, None, None
                    continue

                if flag == 0x01:
                    current = {idx: payload}
                    expected_size = frame_size
                    frame_started_at = now
                    continue

                if flag not in (0x02, 0x03) or expected_size is None:
                    continue

                current[idx] = payload

                if flag == 0x02:
                    max_idx = max(current.keys())
                    if all(i in current for i in range(max_idx + 1)):
                        frame = b"".join(current[i] for i in range(max_idx + 1))
                        if len(frame) == expected_size and frame[:2] == b"\xff\xd8":
                            with self._lock:
                                self._latest_frame = frame
                                self._latest_frame_time = now
                                self._frame_count += 1
                            last_good_frame_at = now
                            self._set_state("streaming")
                    current, expected_size, frame_started_at = {}, None, None

            except OSError as e:
                self._set_state("error", str(e))
                try:
                    self.on_error(f"Network error: {e}")
                except Exception:
                    pass
                if sock:
                    try:
                        sock.close()
                    except OSError:
                        pass
                sock = None
                current, expected_size, frame_started_at = {}, None, None
                time.sleep(1.0)

            except Exception as e:
                self._set_state("error", str(e))
                try:
                    self.on_error(f"Unexpected error: {e}")
                except Exception:
                    pass
                time.sleep(1.0)

        if sock:
            try:
                sock.close()
            except OSError:
                pass