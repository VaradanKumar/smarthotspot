    import tkinter as tk
from tkinter import messagebox, ttk
import threading
import bluetooth_engine as bt

class SmartHotspotApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("SmartHotspot Controller")
        self.geometry("480x550")
        self.engine = bt.BluetoothEngine(self._add_log)
        self.devices = []
        self.status_var = tk.StringVar(value="Status: Idle")
        self.sel_device = tk.StringVar()
        self._build_ui()
        self._refresh_list()

    def _add_log(self, msg):
        self.after(0, lambda: self.log_area.insert(tk.END, f"{msg}\n"))
        self.after(0, lambda: self.log_area.see(tk.END))

    def _build_ui(self):
        c = tk.Frame(self, padx=20, pady=10)
        c.pack(fill=tk.BOTH, expand=True)
        tk.Label(c, text="SmartHotspot", font=("Arial", 18, "bold")).pack(anchor=tk.W)
        tk.Label(c, textvariable=self.status_var, font=("Arial", 11)).pack(anchor=tk.W, pady=10)
        self.combo = ttk.Combobox(c, textvariable=self.sel_device, state="readonly")
        self.combo.pack(fill=tk.X, pady=5)
        tk.Button(c, text="Refresh Paired Devices", command=self._refresh_list).pack(fill=tk.X)
        self.btn_conn = tk.Button(c, text="SEARCH AND CONNECT", bg="#e1f5fe", height=2, font=("Arial", 10, "bold"), command=self._connect)
        self.btn_conn.pack(fill=tk.X, pady=10)
        tk.Button(c, text="Disconnect", command=self._disconnect).pack(fill=tk.X)
        tk.Label(c, text="Remote Control:", font=("Arial", 9, "bold")).pack(anchor=tk.W, pady=(20, 0))
        self.btn_on = tk.Button(c, text="HOTSPOT ON", bg="#c8e6c9", height=2, font=("Arial", 10, "bold"), command=lambda: self._send("HOTSPOT_ON"))
        self.btn_on.pack(fill=tk.X, pady=5)
        self.btn_off = tk.Button(c, text="HOTSPOT OFF", bg="#ffcdd2", height=2, font=("Arial", 10, "bold"), command=lambda: self._send("HOTSPOT_OFF"))
        self.btn_off.pack(fill=tk.X)
        self.log_area = tk.Text(c, height=10, font=("Consolas", 8))
        self.log_area.pack(fill=tk.BOTH, expand=True, pady=10)
        self._update_btns()

    def _refresh_list(self):
        self.status_var.set("Scanning system...")
        def task():
            self.devices = bt.get_paired_devices()
            self.after(0, self._list_done)
        threading.Thread(target=task, daemon=True).start()

    def _list_done(self):
        self.combo["values"] = [f"{d['name']} ({d['address']})" for d in self.devices]
        if self.devices:
            cfg = bt.load_config()
            for i, d in enumerate(self.devices):
                if d["address"] == cfg["mac"]:
                    self.combo.current(i)
                    break
            else: self.combo.current(0)
            self.status_var.set(f"Found {len(self.devices)} devices")
        else: self.status_var.set("No phones paired!")
        self._update_btns()

    def _connect(self):
        idx = self.combo.current()
        if idx < 0: return
        self.status_var.set("Connecting...")
        self.log_area.delete("1.0", tk.END)
        self._update_btns()
        def task():
            try:
                self.engine.connect(self.devices[idx]["address"])
                self.after(0, lambda: self.status_var.set("Connected! Ready."))
            except Exception as e:
                self.after(0, lambda: messagebox.showerror("Error", str(e)))
                self.after(0, lambda: self.status_var.set("Failed"))
            self.after(0, self._update_btns)
        threading.Thread(target=task, daemon=True).start()

    def _disconnect(self):
        self.engine.disconnect()
        self.status_var.set("Disconnected")
        self._update_btns()

    def _send(self, cmd):
        try:
            self.engine.send_command(cmd)
            self.status_var.set(f"Successfully Sent: {cmd}")
        except Exception as e:
            messagebox.showerror("Error", str(e))
            self._disconnect()

    def _update_btns(self):
        conn = self.engine.is_connected
        busy = self.status_var.get() == "Connecting..."
        self.btn_conn.config(state=tk.DISABLED if conn or busy else tk.NORMAL)
        self.btn_on.config(state=tk.NORMAL if conn else tk.DISABLED)
        self.btn_off.config(state=tk.NORMAL if conn else tk.DISABLED)

if __name__ == "__main__":
    SmartHotspotApp().mainloop()
