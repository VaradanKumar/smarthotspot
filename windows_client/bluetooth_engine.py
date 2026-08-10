import asyncio
import json
import os
import re
import subprocess
import threading
import time
from bleak import BleakClient, BleakScanner

# SHARED CONFIGURATION (v6.0 - AirBeam Pro Universal)
SERVICE_UUID = "94f39d29-7d6d-437d-973b-fba39e49d4ee"
COMMAND_CHAR_UUID = "00000001-94f3-9d29-7d6d-973bfba39e49"
TELEMETRY_CHAR_UUID = "00000002-94f3-9d29-7d6d-973bfba39e49"

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".smart_hotspot_config.json")

def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, 'r') as f: return json.load(f)
        except: pass
    return {"mac": ""}

def save_config(mac=None):
    cfg = load_config()
    if mac is not None: cfg["mac"] = mac
    try:
        with open(CONFIG_PATH, 'w') as f: json.dump(cfg, f)
    except: pass

class BluetoothEngine:
    def __init__(self, log_callback, telemetry_callback=None):
        self.client = None
        self.log_callback = log_callback
        self.telemetry_callback = telemetry_callback
        self.connected_device_name = "Phone"
        self.auto_reconnect = False
        self.is_manual_disconnect = False
        self.loop = asyncio.new_event_loop()
        self._start_loop()

    def _start_loop(self):
        def run_loop():
            asyncio.set_event_loop(self.loop)
            self.loop.create_task(self._auto_reconnect_loop())
            self.loop.run_forever()
        threading.Thread(target=run_loop, daemon=True).start()

    async def _auto_reconnect_loop(self):
        while True:
            if self.auto_reconnect and not self.is_manual_disconnect:
                if not self.client or not self.client.is_connected:
                    try:
                        # Background auto-connect
                        await self._find_and_connect_async(is_background=True)
                    except: pass
            await asyncio.sleep(60)

    def log(self, msg): self.log_callback(msg)

    async def _on_telemetry(self, sender, data):
        try:
            msg = data.decode("utf-8")
            telemetry = {}
            for part in msg.split("|"):
                kv = part.split(":")
                if len(kv) == 2: telemetry[kv[0]] = kv[1]
            
            if telemetry.get('M') and (not self.connected_device_name or self.connected_device_name == "Unknown Phone"):
                self.connected_device_name = telemetry['M']

            if self.telemetry_callback:
                self.telemetry_callback(telemetry)
        except: pass

    async def _get_rssi_async(self):
        if self.client and self.client.is_connected:
            return -100
        try:
            devices = await BleakScanner.discover(timeout=1.0)
            cfg = load_config()
            mac = cfg.get("mac")
            if mac:
                for d in devices:
                    if d.address.upper() == mac.upper(): return d.rssi
        except: pass
        return -100

    async def _find_and_connect_async(self, is_background=False):
        try:
            # ROBUST DISCOVERY: Skip MAC check and scan for the AirBeam Service directly.
            # This handles Android MAC rotation automatically.
            if not is_background: self.log("Scanning for phone (Turbo-Discovery)...")
            
            def detection_callback(d, ad):
                return SERVICE_UUID.lower() in [u.lower() for u in ad.service_uuids]

            device = await BleakScanner.find_device_by_filter(detection_callback, timeout=6.0)
            
            if not device:
                raise Exception("Phone not found. Ensure Bluetooth is ON and AirBeam is running.")

            address = device.address
            self.connected_device_name = device.name or "Phone"
            if not is_background: self.log(f"Found {self.connected_device_name}. Connecting...")

            # Clean up any stale client before connecting
            await self.disconnect_async()
            
            # Connect with refreshed services
            self.client = BleakClient(address, timeout=15.0, winrt={"use_cached_services": False})
            await self.client.connect()
            
            # Post-connection breathing room for Windows drivers (increased for stability)
            await asyncio.sleep(2.0)
            
            if not is_background: self.log("Finalizing connection...")
            char = None
            # Extended polling loop for discovery (15 seconds total)
            for i in range(150): 
                try:
                    # Explicitly refresh services if possible (Bleak doesn't have a direct method, 
                    # but use_cached_services=False helps)
                    char = self.client.services.get_characteristic(COMMAND_CHAR_UUID)
                except: char = None
                if char: break
                if not is_background and i % 20 == 0: self.log(f"Still reading services... ({i//10}s)")
                await asyncio.sleep(0.1)

            if not char:
                # Comprehensive Diagnostic Log: Show ALL services to identify "Phone Link" interference
                services_found = [str(s.uuid) for s in self.client.services]
                self.log(f"Handshake Failed. Found {len(services_found)} services:")
                for s_uuid in services_found:
                    self.log(f" -> {s_uuid}")
                raise Exception("Command service handshake failed.")

            try:
                await self.client.start_notify(TELEMETRY_CHAR_UUID, self._on_telemetry)
            except: pass
            
            save_config(mac=address)
            self.log("Linked successfully." if not is_background else f"Auto-Linked: {self.connected_device_name}")
            
        except Exception as e:
            err_msg = str(e)
            # Check for powered-off radio
            if "powered off" in err_msg.lower():
                err_msg = "Laptop Bluetooth is OFF. Please turn it ON."
            
            if not is_background:
                self.log(f"Connection failed: {err_msg}")
            
            await self.disconnect_async()
            raise Exception(err_msg)

    def connect(self):
        self.is_manual_disconnect = False
        future = asyncio.run_coroutine_threadsafe(self._find_and_connect_async(), self.loop)
        return future.result()

    async def _send_command_async(self, cmd):
        if not self.client or not self.client.is_connected:
            await self._find_and_connect_async()
        await self.client.write_gatt_char(COMMAND_CHAR_UUID, cmd.encode("utf-8"), response=False)

    def send_command(self, cmd):
        future = asyncio.run_coroutine_threadsafe(self._send_command_async(cmd), self.loop)
        return future.result()

    def get_rssi(self):
        future = asyncio.run_coroutine_threadsafe(self._get_rssi_async(), self.loop)
        return future.result()

    async def disconnect_async(self):
        if self.client:
            try: await self.client.disconnect()
            except: pass
            self.client = None

    def disconnect(self):
        self.is_manual_disconnect = True
        asyncio.run_coroutine_threadsafe(self.disconnect_async(), self.loop)

    @property
    def is_connected(self):
        return self.client is not None and self.client.is_connected
