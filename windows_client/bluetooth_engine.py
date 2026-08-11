import asyncio
import json
import os
import threading
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "94f39d29-7d6d-437d-973b-fba39e49d4ee"
COMMAND_CHAR_UUID = "00000001-94f3-9d29-7d6d-973bfba39e49"
TELEMETRY_CHAR_UUID = "00000002-94f3-9d29-7d6d-973bfba39e49"
CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".smart_hotspot_config.json")


def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as file:
            return json.load(file)
    except (OSError, ValueError):
        return {"mac": ""}


def save_config(mac=None):
    config = load_config()
    if mac is not None:
        config["mac"] = mac
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as file:
            json.dump(config, file)
    except OSError:
        pass


class BluetoothEngine:
    """Single-loop, single-client BLE owner.

    Bleak clients, scanning, service discovery, and GATT writes are serialized here.
    This prevents two background reconnects or a button press from retaining stale
    client objects and issuing overlapping GATT operations.
    """
    def __init__(self, log_callback, telemetry_callback=None):
        self.client = None
        self.log_callback = log_callback
        self.telemetry_callback = telemetry_callback
        self.connected_device_name = "Phone"
        self.auto_reconnect = False
        self.is_manual_disconnect = False
        self.loop = asyncio.new_event_loop()
        self._connection_lock = None
        self._command_lock = None
        self._closing = False
        self._thread_ready = threading.Event()
        self._start_loop()
        self._thread_ready.wait(timeout=5)

    def _start_loop(self):
        def run_loop():
            asyncio.set_event_loop(self.loop)
            self._connection_lock = asyncio.Lock()
            self._command_lock = asyncio.Lock()
            self.loop.create_task(self._auto_reconnect_loop())
            self._thread_ready.set()
            self.loop.run_forever()
        threading.Thread(target=run_loop, daemon=True, name="AirBeamBluetooth").start()

    def log(self, message):
        try:
            self.log_callback(message)
        except Exception:
            pass

    async def _auto_reconnect_loop(self):
        delay_seconds = 5
        while not self._closing:
            if self.auto_reconnect and not self.is_manual_disconnect and not self.is_connected:
                try:
                    await self._connect_serialized(is_background=True)
                    delay_seconds = 5
                except Exception as error:
                    self.log(f"Auto-link retry failed: {error}")
                    delay_seconds = min(delay_seconds * 2, 60)
            await asyncio.sleep(delay_seconds)

    def _on_disconnected(self, client):
        # Bleak invokes this on its event loop. Do not call connect/disconnect here.
        if self.client is client:
            self.client = None
            self.log("Phone disconnected")

    def _on_telemetry(self, _sender, data):
        try:
            message = bytes(data).decode("utf-8")
            telemetry = {}
            for part in message.split("|"):
                key, separator, value = part.partition(":")
                if separator:
                    telemetry[key] = value
            if telemetry.get("M"):
                self.connected_device_name = telemetry["M"]
            if self.telemetry_callback:
                self.telemetry_callback(telemetry)
        except (UnicodeDecodeError, ValueError) as error:
            self.log(f"Ignored malformed telemetry: {error}")

    async def _find_device(self):
        def matches_service(device, advertisement):
            return SERVICE_UUID.lower() in {uuid.lower() for uuid in (advertisement.service_uuids or [])}
        return await BleakScanner.find_device_by_filter(matches_service, timeout=10.0)

    async def _disconnect_current_locked(self):
        client = self.client
        self.client = None  # invalidate before awaiting callbacks from a stale client
        if client:
            try:
                if client.is_connected:
                    await client.disconnect()
            except Exception as error:
                self.log(f"Stale connection cleanup failed: {error}")

    async def _connect_serialized(self, is_background=False):
        async with self._connection_lock:
            if self.client and self.client.is_connected:
                return
            await self._disconnect_current_locked()
            if not is_background:
                self.log("Scanning for phone…")
            device = await self._find_device()
            if not device:
                raise RuntimeError("Phone not found. Ensure Bluetooth is on and AirBeam is running.")

            self.connected_device_name = device.name or "Phone"
            client = BleakClient(device, timeout=20.0, disconnected_callback=self._on_disconnected, winrt={"use_cached_services": False})
            try:
                await client.connect()
                if not client.is_connected:
                    raise RuntimeError("Windows reported a disconnected BLE client")
                command = client.services.get_characteristic(COMMAND_CHAR_UUID)
                if command is None:
                    raise RuntimeError("Phone does not expose the AirBeam command characteristic")
                self.client = client
                try:
                    await client.start_notify(TELEMETRY_CHAR_UUID, self._on_telemetry)
                except Exception as error:
                    # Telemetry is optional; control commands must still work.
                    self.log(f"Telemetry subscription unavailable: {error}")
                save_config(mac=device.address)
                self.log("Linked successfully." if not is_background else f"Auto-linked: {self.connected_device_name}")
            except Exception:
                try:
                    await client.disconnect()
                except Exception:
                    pass
                raise

    async def _send_command_async(self, command):
        if command not in {"HOTSPOT_ON", "HOTSPOT_OFF", "LOCATE_PHONE"}:
            raise ValueError("Unsupported AirBeam command")
        async with self._command_lock:
            if not self.client or not self.client.is_connected:
                await self._connect_serialized()
            client = self.client
            try:
                # A write response makes delivery observable. The Android server supports it.
                await client.write_gatt_char(COMMAND_CHAR_UUID, command.encode("utf-8"), response=True)
                self.log(f"Sent {command}")
            except Exception:
                # A failed write generally means the Windows object is stale. Release it so
                # the next explicit command or auto-link creates a new client.
                async with self._connection_lock:
                    if self.client is client:
                        await self._disconnect_current_locked()
                raise

    async def _get_rssi_async(self):
        if self.client and self.client.is_connected:
            return -100  # Windows does not expose connected BLE RSSI through Bleak reliably.
        try:
            devices = await BleakScanner.discover(timeout=3.0)
            remembered_address = load_config().get("mac", "").upper()
            for device in devices:
                if device.address.upper() == remembered_address:
                    return getattr(device, "rssi", -100)
        except Exception as error:
            self.log(f"RSSI scan failed: {error}")
        return -100

    def connect(self):
        self.is_manual_disconnect = False
        return asyncio.run_coroutine_threadsafe(self._connect_serialized(), self.loop).result()

    def send_command(self, command):
        self.is_manual_disconnect = False
        return asyncio.run_coroutine_threadsafe(self._send_command_async(command), self.loop).result()

    def get_rssi(self):
        return asyncio.run_coroutine_threadsafe(self._get_rssi_async(), self.loop).result()

    async def disconnect_async(self):
        async with self._connection_lock:
            await self._disconnect_current_locked()

    def disconnect(self):
        self.is_manual_disconnect = True
        return asyncio.run_coroutine_threadsafe(self.disconnect_async(), self.loop).result()

    @property
    def is_connected(self):
        return self.client is not None and self.client.is_connected
