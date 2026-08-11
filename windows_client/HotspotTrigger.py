import sys
import threading
import bluetooth_engine as bt
import os
import time
from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QStyle, QWidget
from PySide6.QtGui import QAction, QIcon
from PySide6.QtCore import Qt, QLockFile, QDir

class SleepWatcher(QWidget):
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
        self.engine.auto_reconnect = True 
        
        self.tray = QSystemTrayIcon()
        icon_path = os.path.join(os.path.dirname(__file__), "airbeam.ico")
        if os.path.exists(icon_path):
            self.tray.setIcon(QIcon(icon_path))
        else:
            self.tray.setIcon(self.app.style().standardIcon(QStyle.StandardPixmap.SP_DriveNetIcon))
        
        self.menu = QMenu()
        self.menu.addAction("Connect").triggered.connect(lambda: threading.Thread(target=self.engine.connect, daemon=True).start())
        self.menu.addAction("Disconnect").triggered.connect(self.engine.disconnect)
        self.menu.addSeparator()
        self.menu.addAction("Hotspot ON").triggered.connect(lambda: self.trigger("HOTSPOT_ON"))
        self.menu.addAction("Hotspot OFF").triggered.connect(lambda: self.trigger("HOTSPOT_OFF"))
        self.menu.addSeparator()
        self.menu.addAction("Exit").triggered.connect(self._exit_app)
        
        self.tray.setContextMenu(self.menu)
        self.tray.show()

        self.proximity_active = True
        self.watcher = SleepWatcher(self._on_sleep)
        self.watcher.hide()

        threading.Thread(target=self._proximity_loop, daemon=True).start()

    def _handle_telemetry(self, data):
        try:
            bat = data.get('B', '--')
            sig = data.get('S', '0')
            net = data.get('N', 'LTE')
            chg = data.get('C', '0')
            sig_text = {"0":"None", "1":"Poor", "2":"Fair", "3":"Good", "4":"Excellent"}.get(sig, "OK")
            charge_status = " (Charging)" if chg == "1" else ""
            name = self.engine.connected_device_name
            self.tray.setToolTip(f"AirBeam: {name}\nBattery: {bat}%{charge_status}\nNetwork: {net}\nSignal: {sig_text}")
        except: pass

    def _on_sleep(self):
        try: self.engine.send_command("HOTSPOT_OFF")
        except: pass

    def _proximity_loop(self):
        while self.proximity_active:
            if not self.engine.is_connected:
                try:
                    rssi = self.engine.get_rssi()
                    if rssi > -65:
                        self.tray.showMessage("AirBeam Pro", "Phone nearby.", QSystemTrayIcon.Information, 3000)
                except: pass
            time.sleep(15)

    def trigger(self, cmd):
        def task():
            try:
                self.engine.send_command(cmd)
            except Exception as e: 
                self.tray.showMessage("Error", f"Failed: {str(e)}")
        threading.Thread(target=task, daemon=True).start()

    def _exit_app(self):
        self.proximity_active = False
        self.lock_file.unlock()
        self.app.quit()

    def run(self):
        sys.exit(self.app.exec())

if __name__ == "__main__":
    HotspotTrayApp().run()
