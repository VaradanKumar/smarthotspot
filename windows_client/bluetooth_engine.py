import re
import socket
import subprocess
import threading
import json
import os
import time
import asyncio

# SHARED CONFIGURATION (Ultimate Stability v4.5)
SERVICE_UUID = "94f39d29-7d6d-437d-973b-fba39e49d4ee"
# STRICT BLACKLIST: Ports 1, 2, 3 are NEVER scanned
SAFE_SCAN_PORTS = [7, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15] + list(range(16, 31))
CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".smart_hotspot_config.json")

def normalize_mac(addr):
    digits = re.sub(r"[^0-9A-Fa-f]", "", addr or "")
    if len(digits) != 12: return ""
    return ":".join(digits.upper()[i:i+2] for i in range(0, 12, 2))

def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, 'r') as f: return json.load(f)
        except: pass
    return {"mac": "", "last_port": None}

def save_config(mac=None, port=None):
    cfg = load_config()
    if mac: cfg["mac"] = mac
    if port: cfg["last_port"] = port
    try:
        with open(CONFIG_PATH, 'w') as f: json.dump(cfg, f)
    except: pass

def get_paired_devices():
    cmd = "Get-PnpDevice -Class Bluetooth | Where-Object { $_.InstanceId -match 'DEV_([0-9A-F]{12})' } | ForEach-Object { $a = ($Matches[1] -replace '(.{2})(?!$)', '$1:'); \"$($_.FriendlyName)|$a\" }"
    try:
        res = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", cmd], capture_output=True, text=True, timeout=10)
        devices = []
        seen = set()
        for line in res.stdout.splitlines():
            if "|" not in line: continue
            name, addr = line.rsplit("|", 1)
            addr = normalize_mac(addr)
            if addr and addr not in seen:
                seen.add(addr)
                devices.append({"name": name.strip(), "address": addr})
        return sorted(devices, key=lambda x: x["name"].lower())
    except: return []

class BluetoothEngine:
    def __init__(self, log_callback):
        self.socket = None
        self.log_callback = log_callback
        self.config = load_config()

    def log(self, msg): self.log_callback(msg)

    def connect(self, address, retries=3):
        for attempt in range(1, retries + 1):
            try:
                if attempt > 1: self.log(f"Retry attempt {attempt}/{retries}...")
                if self._internal_connect(address):
                    return True
            except Exception as e:
                if attempt == retries: raise e
                self.log(f"Attempt {attempt} failed, retrying in 1s...")
                time.sleep(1)
        return False

    def _internal_connect(self, address):
        if self.socket: self.disconnect()
        addr = normalize_mac(address)
        if not addr: raise Exception("Invalid MAC Address")
        save_config(mac=addr)

        # 1. Try Cached Port (Instant Success)
        last_port = load_config().get("last_port")
        if last_port:
            self.log(f"Trying last port: {last_port}...")
            if self._attempt_handshake(addr, last_port): return True

        # 2. Try BLE discovery (Modern Bleak logic)
        try:
            import bleak
            self.log("Listening for phone beacon (8s)...")
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            port = loop.run_until_complete(self._discover_ble(addr))
            loop.close()
            if port and port not in [1, 2, 3]:
                self.log(f"Beacon found port {port}")
                if self._attempt_handshake(addr, port):
                    save_config(port=port)
                    return True
        except: pass

        # 3. Turbo Scan (Safe range)
        self.log("Starting Turbo Scan...")
        for port in SAFE_SCAN_PORTS:
            if port == last_port: continue
            if self._attempt_handshake(addr, port, timeout=0.8):
                save_config(port=port)
                return True
        return False

    async def _discover_ble(self, target):
        import bleak
        devices = await bleak.BleakScanner.discover(timeout=8.0, return_adv=True)
        for addr, (dev, adv) in devices.items():
            if addr.upper() == target.upper():
                m_data = adv.manufacturer_data
                if 0xFFFF in m_data: return int(m_data[0xFFFF][0])
        return None

    def _attempt_handshake(self, host, port, timeout=3.0):
        try:
            sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            sock.settimeout(timeout)
            sock.connect((host, port))
            sock.send("HELO\n".encode("utf-8"))
            res = sock.recv(1024).decode("utf-8")
            if "READY" in res:
                sock.settimeout(None)
                self.socket = sock
                self.log(f"Verified on port {port}")
                return True
            sock.close()
        except: pass
        return False

    def send_command(self, cmd):
        if not self.socket: raise Exception("Not connected")
        try:
            for _ in range(3): # Extra burst
                self.socket.send(f"{cmd}\n".encode("utf-8"))
                time.sleep(0.1)
            self.log(f"Sent: {cmd}")
            return True
        except:
            self.socket = None
            raise Exception("Connection lost")

    def disconnect(self):
        if self.socket:
            try: self.socket.close()
            finally: self.socket = None

    @property
    def is_connected(self):
        return self.socket is not None
