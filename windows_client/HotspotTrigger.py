import sys
import threading
import bluetooth_engine as bt
import traceback
from PySide6.QtWidgets import (QApplication, QSystemTrayIcon, QMenu, QMessageBox, 
                             QInputDialog, QComboBox, QDialog, QVBoxLayout, QPushButton, QLabel, QStyle)
from PySide6.QtGui import QAction
from PySide6.QtCore import Qt

class SetupDialog(QDialog):
    def __init__(self, current_mac):
        super().__init__()
        self.setWindowTitle("SmartHotspot Setup")
        self.setFixedSize(350, 250)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Select Your Phone:"))
        self.combo = QComboBox()
        layout.addWidget(self.combo)
        btn_scan = QPushButton("Refresh Paired List")
        btn_scan.clicked.connect(self.scan)
        layout.addWidget(btn_scan)
        layout.addWidget(QLabel("--- or ---"))
        btn_save = QPushButton("SAVE AND CLOSE")
        btn_save.setStyleSheet("background-color: #4CAF50; color: white; font-weight: bold; height: 40px;")
        btn_save.clicked.connect(self.accept)
        layout.addWidget(btn_save)
        btn_man = QPushButton("Enter MAC Manually...")
        btn_man.clicked.connect(self.manual)
        layout.addWidget(btn_man)
        self.manual_mac = current_mac
        self.scan()

    def scan(self):
        self.combo.clear()
        devices = bt.get_paired_devices()
        for d in devices:
            self.combo.addItem(f"{d['name']} ({d['address']})", d['address'])
        if self.manual_mac:
            idx = self.combo.findData(self.manual_mac)
            if idx >= 0: self.combo.setCurrentIndex(idx)

    def manual(self):
        text, ok = QInputDialog.getText(self, 'Manual', 'Enter MAC (AA:BB:CC:DD:EE:FF):')
        if ok and text:
            self.manual_mac = bt.normalize_mac(text)
            self.accept()

    def get_mac(self): return self.combo.currentData()

class HotspotTrayApp:
    def __init__(self):
        self.app = QApplication(sys.argv)
        self.app.setQuitOnLastWindowClosed(False)
        self.engine = bt.BluetoothEngine(lambda msg: None) # No console logging
        self.tray = QSystemTrayIcon()
        self.tray.setIcon(self.app.style().standardIcon(QStyle.StandardPixmap.SP_DriveNetIcon))
        self.tray.setToolTip("SmartHotspot")
        menu = QMenu()
        menu.addAction("Turn Hotspot ON").triggered.connect(lambda: self.trigger("HOTSPOT_ON"))
        menu.addAction("Turn Hotspot OFF").triggered.connect(lambda: self.trigger("HOTSPOT_OFF"))
        menu.addSeparator()
        menu.addAction("Setup Phone...").triggered.connect(self.run_setup)
        menu.addAction("Exit").triggered.connect(self.app.quit)
        self.tray.setContextMenu(menu)
        self.tray.show()
        cfg = bt.load_config()
        if not cfg.get("mac"): self.run_setup()

    def trigger(self, cmd):
        def task():
            try:
                cfg = bt.load_config()
                if not cfg.get("mac"): return
                self.notify(f"Connecting...")
                self.engine.connect(cfg["mac"])
                self.engine.send_command(cmd)
                self.notify(f"Success: {cmd}")
                self.engine.disconnect()
            except Exception as e: self.notify(f"Error: {str(e)}")
        threading.Thread(target=task, daemon=True).start()

    def run_setup(self):
        cfg = bt.load_config()
        d = SetupDialog(cfg.get("mac"))
        if d.exec():
            mac = d.get_mac()
            if mac:
                bt.save_config(mac=mac)
                self.notify("Phone Saved!")

    def notify(self, msg): self.tray.showMessage("SmartHotspot", msg, QSystemTrayIcon.Information, 2000)
    def run(self): sys.exit(self.app.exec())

if __name__ == "__main__":
    HotspotTrayApp().run()
