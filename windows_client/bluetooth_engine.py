import asyncio
import json
import os
import re
import subprocess
import threading
import time
from bleak import BleakClient, BleakScanner

# SHARED CONFIGURATION (v6.0 - Nordic Standard BLE)
SERVICE_UUID = "94f39d29-7d6d-437d-973b-fba39e49d4ee"
COMMAND_CHAR_UUID = "00000001-94f3-9d29-7d6d-973bfba39e49"

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".smart_hotspot_config.json")

def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, 'r') as f: return json.load(f)
        except: pass
    return {"mac": ""}

def save_config(mac=None):
    cfg = load_config()
    if mac: cfg["mac"] = mac
    try:
        with open(CONFIG_PATH, 'w') as f: json.dump(cfg, f)
    except: pass

class BluetoothEngine:
    def __init__(self, log_callback):
        self.client = None
        self.log_callback = log_callback
        self.loop = asyncio.new_event_loop()
        self._start_loop()

    def _start_loop(self):
        def run_loop():
            asyncio.set_event_loop(self.loop)
            self.loop.run_forever()
        threading.Thread(target=run_loop, daemon=True).start()

    def log(self, msg): self.log_callback(msg)

    async def _find_and_connect_async(self):
        # 1. Quick Link (Cached)
        cfg = load_config()
        cached_mac = cfg.get("mac")
        device = None

        if cached_mac:
            self.log(f"Quick Link: {cached_mac}")
            device = await BleakScanner.find_device_by_address(cached_mac, timeout=2.0)
        
        # 2. Burst Scan (Aggressive)
        if not device:
            self.log("Searching air (Burst Scan)...")
            device = await BleakScanner.find_device_by_filter(
                lambda d, ad: SERVICE_UUID.lower() in [u.lower() for u in ad.service_uuids],
                timeout=4.0
            )
        
        if not device:
            raise Exception("Phone not found. Ensure app engine is ON.")
            
        self.log(f"Linked: {device.name or 'SmartHotspot'}")
        self.client = BleakClient(device)
        await self.client.connect()
        
        # --- NEW: Reliability Delay & Multi-pass Verification ---
        self.log("Verifying Command Center...")
        await asyncio.sleep(1.0) # Let Windows GATT settle
        
        found = False
        # Try up to 3 times to find the service (handles slow discovery)
        for attempt in range(3):
            for service in self.client.services:
                for char in service.characteristics:
                    if char.uuid.lower() == COMMAND_CHAR_UUID.lower():
                        found = True
                        break
                if found: break
            if found: break
            if attempt < 2:
                self.log(f"Retrying discovery (Attempt {attempt+2})...")
                await asyncio.sleep(1.0)

        if not found:
            # Debug: Log what WAS found to help the user
            self.log("Verification failed. Windows Cache issue likely.")
            self.log("Tip: Toggle Bluetooth on Phone & Laptop.")
            raise Exception("Command ID not found. Please toggle Bluetooth.")

        self.log("System Active.")
        save_config(mac=device.address)

    def connect(self):
        future = asyncio.run_coroutine_threadsafe(self._find_and_connect_async(), self.loop)
        return future.result()

    async def _send_command_async(self, cmd, retry=True):
        if not self.client or not self.client.is_connected:
            await self._find_and_connect_async()
            
        self.log(f"Pushing: {cmd}")
        try:
            await self.client.write_gatt_char(COMMAND_CHAR_UUID, cmd.encode("utf-8"))
            self.log("Success!")
        except Exception as e:
            if retry:
                self.log("Auto-recovering link...")
                await self.disconnect_async()
                await asyncio.sleep(0.5)
                await self._send_command_async(cmd, retry=False)
            else:
                self.log(f"Fail: {str(e)}")
                raise e

    def send_command(self, cmd):
        future = asyncio.run_coroutine_threadsafe(self._send_command_async(cmd), self.loop)
        return future.result()

    async def disconnect_async(self):
        if self.client:
            try: await self.client.disconnect()
            except: pass
            self.client = None

    def disconnect(self):
        asyncio.run_coroutine_threadsafe(self.disconnect_async(), self.loop)
        self.log("Offline.")

    @property
    def is_connected(self):
        return self.client is not None and self.client.is_connected
