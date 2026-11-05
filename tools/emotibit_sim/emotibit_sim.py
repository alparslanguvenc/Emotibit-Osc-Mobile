#!/usr/bin/env python3
"""EmotiBit Wi-Fi simulator for testing the RotaLab Android controller without hardware.

Re-implements, from the firmware source (EmotiBit_FeatherWing 1.14.x, EmotiBitWiFi.cpp and
EmotiBit.cpp), the behaviour a host sees:

* answers HE on the advertising port with HH (DP=-1 when free, DI=<device id>)
* on EC opens a TCP connection to the host control port and replies PO; PN keeps the link alive,
  10 s without PN drops it (configurable)
* every --sync-interval seconds sends RD,TL,TU on the data channel and waits 100 ms
  (MAX_SYNC_WAIT_INTERVAL) for TL and AK; RD/TL/AK are written to the "SD card" file and echoed
  in the data stream, like the firmware does
* RB opens <timestamp>.csv and <timestamp>_info.json in --sd-dir, RE closes it, UN/RB/RE and other
  control packets are logged into the file; MO switches Wi-Fi off, MH exits
* EM mode packets every 200 ms, B% battery, a few fake sensor packets
* --offline FROM:TO simulates leaving Wi-Fi range (seconds after start): no network traffic, but
  recording to the SD file continues

Only the Python standard library is used.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import os
import select
import socket
import sys
import threading
import time

AVAILABLE = -1
MAX_SYNC_WAIT = 0.100


class Sim:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.t0 = time.monotonic()
        self.lock = threading.RLock()
        self.counter = 0
        self.adv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.adv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.adv.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.adv.bind((args.bind, args.adv_port))
        self.adv.settimeout(0.01)
        self.data: socket.socket | None = None
        self.control: socket.socket | None = None
        self.control_buf = b""
        self.host_ip: str | None = None
        self.host_data_port = AVAILABLE
        self.connected = False
        self.last_ping = 0.0
        self.recording = False
        self.sd_file = None
        self.sd_name = ""
        self.power = "MN"
        self.wifi_off_until = 0.0
        self.out = ""  # pending data packets (_outDataPackets)
        self.running = True

    # ---------------------------------------------------------------- helpers
    def millis(self) -> int:
        return int((time.monotonic() - self.t0) * 1000) & 0xFFFFFFFF

    def elapsed(self) -> float:
        return time.monotonic() - self.t0

    def packet(self, tag: str, payload: list[str] | None = None, data_length: int | None = None) -> str:
        payload = payload or []
        with self.lock:
            n = self.counter
            self.counter = (self.counter + 1) & 0xFFFF
        dl = len(payload) if data_length is None else data_length
        s = f"{self.millis()},{n},{dl},{tag},1,100"
        if payload:
            s += "," + ",".join(payload)
        return s + "\n"

    def log(self, *a: object) -> None:
        if self.args.verbose:
            print(f"[sim {self.elapsed():7.2f}]", *a, file=sys.stderr, flush=True)

    def wifi_up(self) -> bool:
        t = self.elapsed()
        for frm, to in self.args.offline:
            if frm <= t < to:
                return False
        if self.power == "MO":
            if self.wifi_off_until and time.monotonic() >= self.wifi_off_until:
                # Simulated short button press: back to normal power.
                self.power = "MN"
                self.log("wifi back on (simulated button press)")
                return True
            return False
        return True

    @staticmethod
    def parse(line: str) -> tuple[list[str], int] | None:
        parts = line.strip().split(",")
        if len(parts) < 6:
            return None
        try:
            int(parts[0]); int(parts[1]); int(parts[2])
        except ValueError:
            return None
        return parts, 6

    @staticmethod
    def keyed(parts: list[str], key: str) -> str | None:
        for i in range(6, len(parts) - 1):
            if parts[i] == key:
                return parts[i + 1]
        return None

    # ---------------------------------------------------------------- link
    def disconnect(self, why: str) -> None:
        if self.connected:
            self.log("disconnect:", why)
        self.connected = False
        self.host_data_port = AVAILABLE
        if self.control:
            try:
                self.control.close()
            except OSError:
                pass
        self.control = None
        self.control_buf = b""
        if self.data:
            self.data.close()
        self.data = None

    def connect(self, host_ip: str, cp: int, dp: int) -> bool:
        try:
            c = socket.create_connection((host_ip, cp), timeout=2)
        except OSError as e:
            self.log("control connect failed", e)
            return False
        c.setblocking(False)
        d = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # Firmware binds its UDP data socket to the host's data port number. On one machine that port
        # is taken by the host, so the simulator uses an ephemeral port; hosts must reply to the source.
        d.bind((self.args.bind, 0))
        d.settimeout(0.001)
        self.control, self.data = c, d
        self.host_ip, self.host_data_port = host_ip, dp
        self.connected = True
        self.last_ping = time.monotonic()
        self.log("connected to host", host_ip, cp, dp)
        return True

    def send_data(self, msg: str) -> None:
        if self.connected and self.data and self.power != "MO":
            try:
                self.data.sendto(msg.encode(), (self.host_ip, self.host_data_port))
            except OSError:
                pass

    def read_data(self) -> str | None:
        if not self.data:
            return None
        try:
            b, _ = self.data.recvfrom(255)  # firmware reads at most MAX_TO_EMOTIBIT_PACKET_LEN
            return b.decode(errors="replace")
        except (socket.timeout, BlockingIOError):
            return None
        except OSError:
            return None

    # ---------------------------------------------------------------- loops
    def process_advertising(self) -> None:
        try:
            b, addr = self.adv.recvfrom(1024)
        except (socket.timeout, BlockingIOError):
            return
        msg = b.decode(errors="replace")
        line = msg.split("\n")[0]  # firmware: one packet per datagram
        p = self.parse(line)
        if not p:
            return
        parts, _ = p
        tag = parts[3]
        if tag == "HE":
            dp = self.host_data_port if self.connected else AVAILABLE
            out = self.packet("HH", ["DP", str(dp), "DI", self.args.device_id])
            self.adv.sendto(out.encode(), addr)
        elif tag == "EC":
            if not self.connected:
                cp = self.keyed(parts, "CP")
                dp = self.keyed(parts, "DP")
                if cp and dp:
                    self.connect(addr[0], int(cp), int(dp))
            if self.connected:
                self.adv.sendto(self.packet("PO", ["DP", str(self.host_data_port)]).encode(), addr)
                self.last_ping = time.monotonic()
        elif tag == "PN":
            if self.connected and addr[0] == self.host_ip and self.keyed(parts, "DP") == str(self.host_data_port):
                self.last_ping = time.monotonic()
                self.adv.sendto(self.packet("PO", ["DP", str(self.host_data_port)]).encode(), addr)

    def process_time_sync(self) -> str:
        sync = ""
        # flush stale messages, logging them like the firmware does
        while True:
            m = self.read_data()
            if m is None:
                break
            p = self.parse(m.split("\n")[0])
            if p:
                parts, _ = p
                sync += self.packet(parts[3], parts[6:], int(parts[2]))
        rd = self.packet("RD", ["TL", "TU"])
        rd_number = int(rd.split(",")[1])
        self.send_data(rd)
        sync += rd
        start = time.monotonic()
        while time.monotonic() - start < MAX_SYNC_WAIT:
            m = self.read_data()
            if m is None:
                time.sleep(0.0005)
                continue
            p = self.parse(m.split("\n")[0])
            if not p:
                continue
            parts, _ = p
            tag = parts[3]
            if tag in ("TL", "TU"):
                sync += self.packet(tag, parts[6:], int(parts[2]))
            elif tag == "AK":
                sync += self.packet(tag, parts[6:], int(parts[2]))
                if len(parts) > 6 and parts[6].strip() == str(rd_number):
                    self.log("sync ok")
                    return sync
        self.log("sync timed out")
        return sync

    def handle_control(self, line: str) -> str:
        p = self.parse(line)
        if not p:
            return ""
        parts, _ = p
        tag = parts[3]
        payload = parts[6:]
        if tag == "RB":
            stamp = ",".join(payload)
            self.sd_name = stamp + ".csv"
            os.makedirs(self.args.sd_dir, exist_ok=True)
            with open(os.path.join(self.args.sd_dir, stamp + "_info.json"), "w") as f:
                json.dump([{"info": {"name": "EmotiBitSimulator", "device_id": self.args.device_id, "created": stamp}}], f)
            self.sd_file = open(os.path.join(self.args.sd_dir, self.sd_name), "a")
            self.recording = True
            self.log("record begin", self.sd_name)
        elif tag == "RE":
            if self.sd_file:
                self.sd_file.close()
            self.sd_file = None
            self.recording = False
            self.log("record end")
        elif tag in ("MN", "ML", "MM"):
            self.power = tag
        elif tag == "MO":
            self.power = "MO"
            self.wifi_off_until = time.monotonic() + self.args.wifi_off_seconds if self.args.wifi_off_seconds > 0 else 0
            self.disconnect("wifi off")
        elif tag == "MH":
            self.log("hibernate")
            self.running = False
        elif tag == "ED":
            self.disconnect("host requested")
        return self.packet(tag, payload, int(parts[2]))

    def read_control(self) -> str:
        if not self.control:
            return ""
        logged = ""
        try:
            r, _, _ = select.select([self.control], [], [], 0)
            if r:
                chunk = self.control.recv(4096)
                if not chunk:
                    self.disconnect("control closed")
                    return ""
                self.control_buf += chunk
        except OSError:
            self.disconnect("control error")
            return ""
        while b"\n" in self.control_buf:
            line, self.control_buf = self.control_buf.split(b"\n", 1)
            if line.strip():
                logged += self.handle_control(line.decode(errors="replace"))
        return logged

    # ---------------------------------------------------------------- fake sensors
    def samples(self, tag: str, rate: float, fn) -> list[str]:
        """All samples of `tag` due since the last call at `rate` Hz (independent of loop timing)."""
        if not hasattr(self, "_next_t"):
            self._next_t = {}
        now = self.elapsed()
        t = self._next_t.get(tag, now)
        out = []
        while t <= now:
            out.append(fn(t))
            t += 1.0 / rate
        self._next_t[tag] = t
        return out

    def sensor_packets(self) -> str:
        out = ""
        hr = 72 + 4 * math.sin(self.elapsed() / 20)

        def eda(t: float) -> str:
            tonic = 0.8 + 0.1 * math.sin(2 * math.pi * t / 60)
            phase = t % 25  # a skin-conductance response every 25 s
            phasic = 0.25 * (phase / 2) * math.exp(1 - phase / 2) if phase < 12 else 0
            return f"{tonic + phasic + random.gauss(0, 0.002):.4f}"

        def ppg(scale: float, base: float):
            def f(t: float) -> str:
                ph = (t * hr / 60) % 1
                pulse = math.exp(-((ph - 0.2) ** 2) / 0.004) + 0.35 * math.exp(-((ph - 0.45) ** 2) / 0.01)
                return f"{base + scale * pulse + random.gauss(0, scale * 0.01):.0f}"
            return f

        def acc(axis: int):
            def f(t: float) -> str:
                g = (0.0, 0.0, 1.0)[axis]
                return f"{g + 0.05 * math.sin(2 * math.pi * 1.4 * t + axis) + random.gauss(0, 0.01):.3f}"
            return f

        def add(tag: str, values: list[str]) -> None:
            nonlocal out
            if values:
                out += self.packet(tag, values)

        add("EA", self.samples("EA", 15, eda))
        add("PI", self.samples("PI", 25, ppg(2000, 60000)))
        add("PR", self.samples("PR", 25, ppg(1200, 40000)))
        add("PG", self.samples("PG", 25, ppg(800, 9000)))
        for i, tag in enumerate(("AX", "AY", "AZ")):
            add(tag, self.samples(tag, 25, acc(i)))
        for i, tag in enumerate(("GX", "GY", "GZ")):
            add(tag, self.samples(tag, 25, lambda t, i=i: f"{3 * math.sin(t + i) + random.gauss(0, 0.3):.2f}"))
        t1 = self.samples("T1", 7.5, lambda t: f"{33.1 + 0.2 * math.sin(t / 40):.3f}")
        th = self.samples("TH", 7.5, lambda t: f"{32.4 + 0.3 * math.sin(t / 50):.3f}")
        if t1:
            out += self.packet("T1", t1)
        if th:
            out += self.packet("TH", th)
        if self.elapsed() - getattr(self, "_last_hr", 0) > 60 / hr:
            self._last_hr = self.elapsed()
            out += self.packet("HR", [f"{hr:.0f}"])
        return out

    def write_sd(self, s: str) -> None:
        if self.recording and self.sd_file and s:
            self.sd_file.write(s)
            self.sd_file.flush()

    def run(self) -> None:
        last_sync = time.monotonic()
        last_mode = 0.0
        last_data = 0.0
        last_batt = 0.0
        batt = 87.0
        print(f"EmotiBit simulator {self.args.device_id} on advertising port {self.args.adv_port}", file=sys.stderr, flush=True)
        while self.running and (self.args.duration <= 0 or self.elapsed() < self.args.duration):
            now = time.monotonic()
            up = self.wifi_up()
            if up:
                self.process_advertising()
                if self.connected and now - self.last_ping > self.args.connection_timeout:
                    self.disconnect("ping timeout")
                if self.connected and now - last_sync > self.args.sync_interval:
                    last_sync = now
                    self.out += self.process_time_sync()
                self.out += self.read_control()
            else:
                # Out of range: drain nothing, link will time out on both sides.
                try:
                    self.adv.recvfrom(1024)
                except (socket.timeout, BlockingIOError, OSError):
                    pass
                if self.connected and now - self.last_ping > self.args.connection_timeout:
                    self.disconnect("ping timeout (offline)")
                time.sleep(0.01)
            if now - last_mode > 0.2:
                last_mode = now
                rs = ["RS", "RB", self.sd_name] if self.recording else ["RS", "RE"]
                self.out += self.packet("EM", rs + ["PS", self.power])
            if now - last_data > 0.2:
                last_data = now
                self.out += self.sensor_packets()
            if now - last_batt > 1.0:
                last_batt = now
                batt = max(0.0, batt - 0.01)
                self.out += self.packet("B%", [f"{batt:.0f}"])
            if self.out:
                self.write_sd(self.out)
                if up:
                    self.send_data(self.out)
                self.out = ""
            time.sleep(0.002)
        if self.sd_file:
            self.sd_file.close()
        self.disconnect("exit")
        print("simulator exit", file=sys.stderr, flush=True)


def parse_window(s: str) -> tuple[float, float]:
    a, b = s.split(":")
    return float(a), float(b)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--adv-port", type=int, default=3131)
    ap.add_argument("--device-id", default="MD-V5-SIM0001")
    ap.add_argument("--sd-dir", default="sim_sd")
    ap.add_argument("--sync-interval", type=float, default=5.107)
    ap.add_argument("--connection-timeout", type=float, default=10.0)
    ap.add_argument("--offline", type=parse_window, action="append", default=[], metavar="FROM:TO")
    ap.add_argument("--wifi-off-seconds", type=float, default=0, help="after MO, turn Wi-Fi back on after N s (0 = never)")
    ap.add_argument("--duration", type=float, default=0, help="exit after N s (0 = run until interrupted)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    try:
        Sim(args).run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
