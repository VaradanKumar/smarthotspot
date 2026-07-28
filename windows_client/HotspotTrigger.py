import sys
import threading
import bluetooth_engine as bt
import os
import subprocess
from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QStyle
from PySide6.QtGui import QAction, QIcon
from PySide6.QtCore import Qt, QLockFile, QDir

class HotspotTrayApp:
    def __init__(self):
        # 1. Singleton Check: Prevent multiple instances
        self.lock_file = QLockFile(os.path.join(QDir.tempPath(), "smart_hotspot_tray.lock"))
        if not self.lock_file.tryLock(100):
            print("Smart Hotspot is already running.")
            sys.exit(0)

        self.app = QApplication(sys.argv)
        self.app.setQuitOnLastWindowClosed(False)
        self.engine = bt.BluetoothEngine(lambda msg: None)
        
        self.tray = QSystemTrayIcon()
        self.tray.setIcon(self.app.style().standardIcon(QStyle.StandardPixmap.SP_DriveNetIcon))
        self.tray.setToolTip("Smart Hotspot")
        
        self.menu = QMenu()
        self.menu.addAction("Hotspot ON").triggered.connect(lambda: self.trigger("HOTSPOT_ON"))
        self.menu.addAction("Hotspot OFF").triggered.connect(lambda: self.trigger("HOTSPOT_OFF"))
        self.menu.addSeparator()
        self.menu.addAction("Exit").triggered.connect(self._exit_app)
        
        self.tray.setContextMenu(self.menu)
        self.tray.show()

    def trigger(self, cmd):
        def task():
            try:
                self.engine.send_command(cmd)
                self.tray.showMessage("Smart Hotspot", f"Sent: {cmd}")
            except Exception as e: 
                self.tray.showMessage("Smart Hotspot", f"Error: {str(e)}")
        threading.Thread(target=task, daemon=True).start()

    def _exit_app(self):
        self.lock_file.unlock()
        self.app.quit()

    def run(self):
        sys.exit(self.app.exec())

if __name__ == "__main__":
    HotspotTrayApp().run()
