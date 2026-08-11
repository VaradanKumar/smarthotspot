import customtkinter as ctk
from tkinter import messagebox
import threading
import datetime
import os
import bluetooth_engine as bt

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

class SmartHotspotApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("AirBeam Pro Dashboard")
        self.geometry("450x680")
        
        icon_path = os.path.join(os.path.dirname(__file__), "airbeam.ico")
        if os.path.exists(icon_path):
            self.iconbitmap(icon_path)
        
        self.log_file = os.path.join(os.path.dirname(__file__), "smart_hotspot.log")
        
        # UI Variables
        self.battery_var = ctk.StringVar(value="--%")
        self.signal_var = ctk.StringVar(value="--")
        self.network_var = ctk.StringVar(value="LTE")
        self.status_var = ctk.StringVar(value="Ready to Link")
        
        # Initialize engine
        self.engine = bt.BluetoothEngine(self._add_log, self._on_telemetry)
        self.engine.auto_reconnect = True 
        
        self._build_ui()
        self._add_log(f"--- Session Started: {datetime.datetime.now()} ---")
        
        # Start state monitor
        self.after(1000, self._update_ui_state)

    def _on_telemetry(self, data):
        try:
            bat = data.get('B', '--')
            sig = data.get('S', '0')
            net = data.get('N', 'LTE')
            chg = data.get('C', '0')
            
            sig_text = {"0":"None", "1":"Poor", "2":"Fair", "3":"Good", "4":"Excellent"}.get(sig, "OK")
            
            # Update battery with charging indicator
            bat_display = f"{bat}%"
            if chg == "1":
                bat_display = f"⚡ {bat}%"
                self.after(0, lambda: self.battery_lbl.configure(text_color="#2ecc71")) # Green when charging
            else:
                self.after(0, lambda: self.battery_lbl.configure(text_color="#3498db")) # Blue normally

            self.after(0, lambda: self.battery_var.set(bat_display))
            self.after(0, lambda: self.signal_var.set(sig_text))
            self.after(0, lambda: self.network_var.set(net))
            
            if self.engine.is_connected:
                name = self.engine.connected_device_name
                self.after(0, lambda: self.status_var.set(f"Linked: {name}"))
        except: pass

    def _update_ui_state(self):
        connected = self.engine.is_connected
        if connected:
            self.btn_smart.configure(text="DISCONNECT SYSTEM", fg_color="#c0392b", hover_color="#e74c3c")
        else:
            if self.status_var.get() != "Connecting...":
                self.btn_smart.configure(text="CONNECT SYSTEM", fg_color="#2980b9", hover_color="#3498db")
        self.after(1000, self._update_ui_state)

    def _add_log(self, msg):
        timestamp = datetime.datetime.now().strftime("%H:%M:%S")
        formatted_msg = f"[{timestamp}] {msg}"
        self.after(0, lambda: self.log_area.insert("end", f"{formatted_msg}\n"))
        self.after(0, lambda: self.log_area.see("end"))
        try:
            with open(self.log_file, "a") as f:
                f.write(f"{formatted_msg}\n")
        except: pass

    def _build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(7, weight=1)

        ctk.CTkLabel(self, text="AIRBEAM PRO", font=("Arial", 28, "bold")).grid(row=0, column=0, pady=(30, 5))
        ctk.CTkLabel(self, text="Control Center", font=("Arial", 12), text_color="gray").grid(row=1, column=0, pady=(0, 20))
        
        dash_frame = ctk.CTkFrame(self, fg_color="#1e1e1e", corner_radius=15)
        dash_frame.grid(row=2, column=0, padx=20, pady=10, sticky="nsew")
        dash_frame.grid_columnconfigure((0,1,2), weight=1)
        
        # Battery
        ctk.CTkLabel(dash_frame, text="BATTERY", font=("Arial", 10, "bold"), text_color="#aaaaaa").grid(row=0, column=0, pady=(15, 0))
        self.battery_lbl = ctk.CTkLabel(dash_frame, textvariable=self.battery_var, font=("Arial", 20, "bold"), text_color="#3498db")
        self.battery_lbl.grid(row=1, column=0, pady=(0, 15))
        
        ctk.CTkLabel(dash_frame, text="NETWORK", font=("Arial", 10, "bold"), text_color="#aaaaaa").grid(row=0, column=1, pady=(15, 0))
        ctk.CTkLabel(dash_frame, textvariable=self.network_var, font=("Arial", 20, "bold"), text_color="#3498db").grid(row=1, column=1, pady=(0, 15))

        ctk.CTkLabel(dash_frame, text="SIGNAL", font=("Arial", 10, "bold"), text_color="#aaaaaa").grid(row=0, column=2, pady=(15, 0))
        ctk.CTkLabel(dash_frame, textvariable=self.signal_var, font=("Arial", 18, "bold"), text_color="#f1c40f").grid(row=1, column=2, pady=(0, 15))

        self.status_lbl = ctk.CTkLabel(self, textvariable=self.status_var, font=("Arial", 14, "italic"), text_color="#95a5a6")
        self.status_lbl.grid(row=3, column=0, pady=10)

        self.btn_smart = ctk.CTkButton(self, text="CONNECT SYSTEM", command=self._handle_click, height=50, font=("Arial", 14, "bold"))
        self.btn_smart.grid(row=4, column=0, padx=20, pady=10, sticky="ew")

        cmd_frame = ctk.CTkFrame(self, fg_color="transparent")
        cmd_frame.grid(row=5, column=0, padx=20, pady=10, sticky="ew")
        cmd_frame.grid_columnconfigure((0,1), weight=1)

        ctk.CTkButton(cmd_frame, text="HOTSPOT ON", command=lambda: self._send("HOTSPOT_ON"), height=45, font=("Arial", 12, "bold"), fg_color="#27ae60", hover_color="#2ecc71").grid(row=0, column=0, padx=(0,5), sticky="ew")
        ctk.CTkButton(cmd_frame, text="HOTSPOT OFF", command=lambda: self._send("HOTSPOT_OFF"), height=45, font=("Arial", 12, "bold"), fg_color="#c0392b", hover_color="#e74c3c").grid(row=0, column=1, padx=(5,0), sticky="ew")

        auto_frame = ctk.CTkFrame(self, fg_color="transparent")
        auto_frame.grid(row=6, column=0, padx=20, pady=5, sticky="ew")
        auto_frame.grid_columnconfigure(0, weight=1)

        self.auto_link_cb = ctk.CTkCheckBox(auto_frame, text="Auto-link when nearby", command=self._toggle_auto, font=("Arial", 11))
        self.auto_link_cb.grid(row=0, column=0, sticky="w")
        self.auto_link_cb.select()

        ctk.CTkButton(auto_frame, text="Clear Cache", command=self._reset_config, width=80, height=24, font=("Arial", 10), fg_color="#333333").grid(row=0, column=1, sticky="e")

        self.log_area = ctk.CTkTextbox(self, height=120, font=("Consolas", 11), fg_color="#0a0a0a", border_width=1, border_color="#222222")
        self.log_area.grid(row=7, column=0, padx=20, pady=(10, 20), sticky="nsew")

    def _handle_click(self):
        if self.engine.is_connected:
            self._add_log("Manual disconnect.")
            self.engine.disconnect()
            self.status_var.set("Ready to Link")
        else:
            self._connect()

    def _connect(self):
        self.status_var.set("Connecting...")
        def task():
            try:
                self.engine.connect()
            except Exception as e:
                err_msg = str(e) or "An unknown Bluetooth error occurred."
                self._add_log(f"Link Failed: {err_msg}")
                self.after(0, lambda: messagebox.showerror("Connection Failed", err_msg))
                self.after(0, lambda: self.status_var.set("Ready to Link"))
        threading.Thread(target=task, daemon=True).start()

    def _toggle_auto(self):
        self.engine.auto_reconnect = self.auto_link_cb.get() == 1
        state = "ON" if self.engine.auto_reconnect else "OFF"
        self._add_log(f"Auto-link {state}")

    def _reset_config(self):
        if messagebox.askyesno("Reset", "Clear saved device memory?"):
            bt.save_config(mac="")
            self._add_log("Memory cleared.")

    def _send(self, cmd):
        threading.Thread(target=lambda: self.engine.send_command(cmd), daemon=True).start()

if __name__ == "__main__":
    app = SmartHotspotApp()
    app.mainloop()
