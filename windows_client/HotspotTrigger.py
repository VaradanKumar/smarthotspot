import sys
import threading
import bluetooth_engine as bt
import os
import time
from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QStyle, QWidget
from PySide6.QtGui import QAction, QIcon
from PySide6.QtCore import Qt, QLockFile, QDir

class SleepWatcher(QWidget):
    """Hidden widget to catch Windows power events."""
    def __init__(self, callback):
        super().__init__()
        self.callback = callback

    def nativeEvent(self, eventType, message):
        import ctypes
        from ctypes import wintypes
        msg = wintypes.MSG.from_address(message.__int__())
        if msg.message == 0x0218 and msg.wParam == 0x0004:
            self.callback()
        return super().nativeEvent(eventType, message)

class HotspotTrayApp:
    def __init__(self):
        self.lock_file = QLockFile(os.path.join(QDir.tempPath(), "smart_hotspot_tray.lock"))
        if not self.lock_file.tryLock(100):
            sys.exit(0)

        self.app = QApplication(sys.argv)
        self.app.setQuitOnLastWindowClosed(False)
        self.engine = bt.BluetoothEngine(lambda msg: None, self._handle_telemetry)
        
        self.tray = QSystemTrayIcon()
        self.tray.setIcon(self.app.style().standardIcon(QStyle.StandardPixmap.SP_DriveNetIcon))
        self.tray.setToolTip("AirBeam: Standby")
        
        self.menu = QMenu()
        self.menu.addAction("Hotspot ON").triggered.connect(lambda: self.trigger("HOTSPOT_ON"))
        self.menu.addAction("Hotspot OFF").triggered.connect(lambda: self.trigger("HOTSPOT_OFF"))
        self.menu.addSeparator()
        self.menu.addAction("Locate My Phone").triggered.connect(lambda: self.trigger("LOCATE_PHONE"))
        self.menu.addSeparator()
        self.menu.addAction("Exit").triggered.connect(self._exit_app)
        
        self.tray.setContextMenu(self.menu)
        self.tray.show()

        self.proximity_active = True
        self.last_notified = 0
        self.RSSI_THRESHOLD = -65 
        
        self.watcher = SleepWatcher(self._on_sleep)
        self.watcher.hide()

        threading.Thread(target=self._proximity_loop, daemon=True).start()

    def _handle_telemetry(self, data):
        try:
            parts = data.split("|")
            bat = parts[0].split(":")[1]
            sig = parts[1].split(":")[1]
            sig_text = { "0": "None", "1": "Poor", "2": "Fair", "3": "Good", "4": "Excellent" }.get(sig, "OK")
            self.tray.setToolTip(f"AirBeam: S22 Plus\nBattery: {bat}%\nSignal: {sig_text}")
        except: pass

    def _on_sleep(self):
        try: self.engine.send_command("HOTSPOT_OFF")
        except: pass

    def _proximity_loop(self):
        while self.proximity_active:
            try:
                rssi = self.engine.get_rssi()
                if rssi > self.RSSI_THRESHOLD:
                    now = time.time()
                    if now - self.last_notified > 600:
                        self.tray.showMessage("AirBeam Pro", "Phone nearby. Toggle Hotspot?", QSystemTrayIcon.Information, 5000)
                        self.last_notified = now
            except: pass
            time.sleep(10)

    def trigger(self, cmd):
        def task():
            try:
                self.engine.send_command(cmd)
                self.tray.showMessage("AirBeam Pro", f"Sent: {cmd}")
            except Exception as e: 
                self.tray.showMessage("Error", f"Link Failed: {str(e)}")
        threading.Thread(target=task, daemon=True).start()

    def _exit_app(self):
        self.proximity_active = False
        self.lock_file.unlock()
        self.app.quit()

    def run(self):
        sys.exit(self.app.exec())

if __name__ == "__main__":
    HotspotTrayApp().run()
