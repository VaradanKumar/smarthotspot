import tkinter as tk
from tkinter import messagebox, ttk
import threading
import bluetooth_engine as bt

class SmartHotspotApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Smart Hotspot Controller")
        self.geometry("400x550")
        
        # Initialize engine BEFORE UI because UI updates depend on it
        self.engine = bt.BluetoothEngine(self._add_log)
        self.status_var = tk.StringVar(value="Disconnected")
        
        self._build_ui()

    def _add_log(self, msg):
        self.after(0, lambda: self.log_area.insert(tk.END, f"{msg}\n"))
        self.after(0, lambda: self.log_area.see(tk.END))

    def _build_ui(self):
        main = tk.Frame(self, padx=20, pady=20)
        main.pack(fill=tk.BOTH, expand=True)

        tk.Label(main, text="Smart Hotspot", font=("Arial", 20, "bold")).pack(pady=(0, 20))
        
        status_frame = tk.LabelFrame(main, text="System Status", padx=10, pady=10)
        status_frame.pack(fill=tk.X, pady=(0, 20))
        
        self.status_lbl = tk.Label(status_frame, text="Ready", font=("Arial", 12))
        self.status_lbl.pack()

        self.btn_conn = ttk.Button(main, text="Connect", command=self._connect)
        self.btn_conn.pack(fill=tk.X, pady=(0, 10))
        
        ttk.Button(main, text="Disconnect", command=self._disconnect).pack(fill=tk.X, pady=(0, 20))

        cmd_frame = tk.Frame(main)
        cmd_frame.pack(fill=tk.X, pady=(0, 20))
        
        self.btn_on = ttk.Button(cmd_frame, text="Turn Hotspot ON", command=lambda: self._send("HOTSPOT_ON"))
        self.btn_on.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 5))
        
        self.btn_off = ttk.Button(cmd_frame, text="Turn Hotspot OFF", command=lambda: self._send("HOTSPOT_OFF"))
        self.btn_off.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(5, 0))

        tk.Label(main, text="Logs", font=("Arial", 10, "bold")).pack(anchor=tk.W)
        self.log_area = tk.Text(main, height=10, font=("Arial", 9), bg="#f0f0f0")
        self.log_area.pack(fill=tk.BOTH, expand=True)
        
        self._update_btns()

    def _connect(self):
        self.status_lbl.config(text="Connecting...")
        self._update_btns()
        def task():
            try:
                self.engine.connect()
                self.after(0, lambda: self.status_lbl.config(text="Connected"))
            except Exception as e:
                err_msg = str(e)
                self.after(0, lambda: messagebox.showerror("Error", err_msg))
                self.after(0, lambda: self.status_lbl.config(text="Failed"))
            self.after(0, self._update_btns)
        threading.Thread(target=task, daemon=True).start()

    def _disconnect(self):
        self.engine.disconnect()
        self.status_lbl.config(text="Ready")
        self._update_btns()

    def _send(self, cmd):
        def task():
            try:
                self.engine.send_command(cmd)
                self.after(0, lambda: self.status_lbl.config(text=f"Sent: {cmd}"))
            except Exception as e:
                err_msg = str(e)
                self.after(0, lambda: messagebox.showerror("Error", err_msg))
                self.after(0, self._disconnect)
        threading.Thread(target=task, daemon=True).start()

    def _update_btns(self):
        conn = self.engine.is_connected
        self.btn_conn.config(state=tk.DISABLED if conn else tk.NORMAL)
        self.btn_on.config(state=tk.NORMAL if conn else tk.DISABLED)
        self.btn_off.config(state=tk.NORMAL if conn else tk.DISABLED)

if __name__ == "__main__":
    SmartHotspotApp().mainloop()
