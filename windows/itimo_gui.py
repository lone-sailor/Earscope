"""
itimo_gui.py
------------
"Ear Scope" -- Windows desktop app for viewing the iTiMO endoscope camera feed.
"""

import ctypes
import io
import json
import os
import sys
import time
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

from PIL import Image, ImageTk
from itimo_core import CameraStream

APP_TITLE = "Ear Scope"
DEFAULT_CAMERA_IP = "192.168.10.123"
CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".earscope_config.json")

# Theme Palette
DARK_PURPLE = "#271e2b"
ACCENT_ORANGE = "#fd8618"
CARD_SURFACE = "#382a3e"
TEXT_WHITE = "#ffffff"
TEXT_MUTED = "#aaaaaa"

STATE_COLORS = {
    "stopped": "#888888",
    "connecting": "#fd8618",
    "streaming": "#2bb673",
    "stalled": "#fd8618",
    "error": "#d64545",
}
STATE_LABELS = {
    "stopped": "Disconnected",
    "connecting": "Connecting...",
    "streaming": "Streaming",
    "stalled": "Signal lost -- retrying...",
    "error": "Error -- retrying...",
}


def get_asset_path(filename):
    """Resolve path to local resource, compatible with PyInstaller bundle mode."""
    if hasattr(sys, "_MEIPASS"):
        return os.path.join(sys._MEIPASS, filename)
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)


def load_config():
    try:
        with open(CONFIG_PATH, "r") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w") as f:
            json.dump(cfg, f)
    except OSError:
        pass


class EarScopeApp:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_TITLE)

        # Apply icon to window titlebar and taskbar
        icon_path = get_asset_path("icon.ico")
        if os.path.exists(icon_path):
            try:
                self.root.iconbitmap(icon_path)
            except Exception:
                pass

        self.root.minsize(480, 420)
        self.root.configure(background=DARK_PURPLE)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.stream = None
        self.last_frame_count_seen = 0
        self.last_fps_check = time.time()
        self.fps = 0
        self._tk_image = None
        self._rotation = 0

        self._configure_styles()
        self._build_ui()
        self._load_last_ip()
        self._poll_frame()

    def _configure_styles(self):
        style = ttk.Style(self.root)
        style.theme_use("clam")

        style.configure(".", background=DARK_PURPLE, foreground=TEXT_WHITE)
        style.configure("TFrame", background=DARK_PURPLE)

        # Buttons
        style.configure(
            "Orange.TButton",
            background=ACCENT_ORANGE,
            foreground=TEXT_WHITE,
            font=("Segoe UI", 9, "bold"),
            borderwidth=0,
            focusthickness=0,
        )
        style.map(
            "Orange.TButton",
            background=[("active", "#e0720d"), ("disabled", "#555555")],
            foreground=[("disabled", "#888888")],
        )

        # Text input entry
        style.configure(
            "Dark.TEntry",
            fieldbackground="#1e1622",
            foreground=TEXT_WHITE,
            insertcolor=TEXT_WHITE,
            bordercolor=ACCENT_ORANGE,
        )

    def _build_ui(self):
        top = ttk.Frame(self.root, padding=10)
        top.pack(side=tk.TOP, fill=tk.X)

        ttk.Label(top, text="Camera IP:", font=("Segoe UI", 9, "bold")).pack(side=tk.LEFT)
        self.ip_var = tk.StringVar()
        self.ip_entry = ttk.Entry(top, textvariable=self.ip_var, width=16, style="Dark.TEntry")
        self.ip_entry.pack(side=tk.LEFT, padx=(6, 10))
        self.ip_entry.bind("<Return>", lambda e: self.on_connect_clicked())

        self.connect_btn = ttk.Button(top, text="Connect", style="Orange.TButton", command=self.on_connect_clicked)
        self.connect_btn.pack(side=tk.LEFT)

        self.snapshot_btn = ttk.Button(top, text="Save Snapshot", style="Orange.TButton", command=self.on_snapshot_clicked, state=tk.DISABLED)
        self.snapshot_btn.pack(side=tk.LEFT, padx=(8, 0))

        self.rotate_btn = ttk.Button(top, text="Rotate ↻", style="Orange.TButton", command=self.on_rotate_clicked)
        self.rotate_btn.pack(side=tk.LEFT, padx=(8, 0))

        # Video Display Surface
        video_frame = tk.Frame(self.root, background="black", highlightthickness=1, highlightbackground=CARD_SURFACE)
        video_frame.pack(side=tk.TOP, fill=tk.BOTH, expand=True, padx=10, pady=(0, 8))
        self.video_label = tk.Label(video_frame, background="black", anchor=tk.CENTER)
        self.video_label.pack(fill=tk.BOTH, expand=True)
        self._set_placeholder_text("Enter camera IP and click Connect.")

        # Status Footer
        status_frame = tk.Frame(self.root, background=DARK_PURPLE, padx=12, pady=6)
        status_frame.pack(side=tk.BOTTOM, fill=tk.X)

        # Left: Connection Status
        left_box = tk.Frame(status_frame, background=DARK_PURPLE)
        left_box.pack(side=tk.LEFT)

        self.status_dot = tk.Canvas(left_box, width=10, height=10, highlightthickness=0, background=DARK_PURPLE)
        self.status_dot.pack(side=tk.LEFT)
        self._dot_id = self.status_dot.create_oval(1, 1, 9, 9, fill=STATE_COLORS["stopped"], outline="")

        self.status_var = tk.StringVar(value=STATE_LABELS["stopped"])
        self.status_lbl = tk.Label(left_box, textvariable=self.status_var, background=DARK_PURPLE, foreground=TEXT_WHITE, font=("Segoe UI", 9))
        self.status_lbl.pack(side=tk.LEFT, padx=(6, 0))

        # Middle: Battery State
        self.battery_var = tk.StringVar(value="🔋 --")
        self.battery_lbl = tk.Label(status_frame, textvariable=self.battery_var, background=DARK_PURPLE, foreground=TEXT_MUTED, font=("Segoe UI", 9, "bold"))
        self.battery_lbl.pack(side=tk.LEFT, expand=True)

        # Right: FPS Counter
        self.fps_var = tk.StringVar(value="")
        tk.Label(status_frame, textvariable=self.fps_var, background=DARK_PURPLE, foreground=TEXT_WHITE, font=("Segoe UI", 9)).pack(side=tk.RIGHT)

    def _set_placeholder_text(self, text):
        self.video_label.configure(image="", text=text, foreground=TEXT_WHITE, font=("Segoe UI", 11))

    def _load_last_ip(self):
        cfg = load_config()
        ip = cfg.get("camera_ip") or DEFAULT_CAMERA_IP
        self.ip_var.set(ip)

    def on_connect_clicked(self):
        if self.stream is not None:
            self._disconnect()
            return

        ip = self.ip_var.get().strip()
        if not ip:
            messagebox.showwarning(APP_TITLE, "Please enter the camera's IP address.")
            return

        save_config({"camera_ip": ip})

        self.stream = CameraStream(camera_ip=ip)
        self.stream.start()
        self.connect_btn.configure(text="Disconnect")
        self.ip_entry.configure(state=tk.DISABLED)
        self.snapshot_btn.configure(state=tk.NORMAL)
        self._set_status("connecting")

    def _disconnect(self):
        if self.stream:
            self.stream.stop()
            self.stream = None
        self.connect_btn.configure(text="Connect")
        self.ip_entry.configure(state=tk.NORMAL)
        self.snapshot_btn.configure(state=tk.DISABLED)
        self._set_status("stopped")
        self._set_placeholder_text("Disconnected. Click Connect to resume.")
        self.fps_var.set("")
        self.battery_var.set("🔋 --")
        self.battery_lbl.configure(foreground=TEXT_MUTED)

    def _set_status(self, state):
        self.status_var.set(STATE_LABELS.get(state, state))
        self.status_dot.itemconfigure(self._dot_id, fill=STATE_COLORS.get(state, "#888888"))

    def _poll_frame(self):
        if self.stream is not None:
            state = self.stream.get_state()
            self._set_status(state)

            # Update battery display
            if state == "streaming":
                batt_status, is_low = self.stream.get_battery_status()
                if is_low:
                    self.battery_var.set("⚠️ LOW BATTERY")
                    self.battery_lbl.configure(foreground="#d64545")
                elif batt_status == "good":
                    self.battery_var.set("🔋 Good")
                    self.battery_lbl.configure(foreground="#2bb673")
                elif batt_status == "normal":
                    self.battery_var.set("🔋 Normal")
                    self.battery_lbl.configure(foreground=ACCENT_ORANGE)
                else:
                    self.battery_var.set("🔋 OK")
                    self.battery_lbl.configure(foreground="#2bb673")
            else:
                self.battery_var.set("🔋 --")
                self.battery_lbl.configure(foreground=TEXT_MUTED)

            jpg, age = self.stream.get_latest_frame()
            if jpg is not None:
                try:
                    img = Image.open(io.BytesIO(jpg))
                    img = self._fit_to_label(img)
                    self._tk_image = ImageTk.PhotoImage(img)
                    self.video_label.configure(image=self._tk_image, text="")
                except Exception:
                    pass

            self._update_fps()

        self.root.after(33, self._poll_frame)

    def on_rotate_clicked(self):
        self._rotation = (self._rotation + 90) % 360

    def _fit_to_label(self, img):
        if self._rotation:
            img = img.rotate(-self._rotation, expand=True)

        target_w = max(self.video_label.winfo_width(), 1)
        target_h = max(self.video_label.winfo_height(), 1)
        if target_w <= 1 or target_h <= 1:
            return img
        img_ratio = img.width / img.height
        box_ratio = target_w / target_h
        if img_ratio > box_ratio:
            new_w = target_w
            new_h = int(target_w / img_ratio)
        else:
            new_h = target_h
            new_w = int(target_h * img_ratio)
        new_w, new_h = max(new_w, 1), max(new_h, 1)
        return img.resize((new_w, new_h), Image.LANCZOS)

    def _update_fps(self):
        now = time.time()
        if now - self.last_fps_check >= 1.0:
            count = self.stream.get_frame_count() if self.stream else 0
            self.fps = count - self.last_frame_count_seen
            self.last_frame_count_seen = count
            self.last_fps_check = now
            if self.stream:
                self.fps_var.set(f"{self.fps} fps")

    def on_snapshot_clicked(self):
        if self.stream is None:
            return
        jpg, _ = self.stream.get_latest_frame()
        if jpg is None:
            messagebox.showinfo(APP_TITLE, "No frame available yet.")
            return
        default_name = f"earscope_{time.strftime('%Y%m%d_%H%M%S')}.jpg"
        path = filedialog.asksaveasfilename(
            title="Save Snapshot",
            defaultextension=".jpg",
            initialfile=default_name,
            filetypes=[("JPEG image", "*.jpg")],
        )
        if not path:
            return
        try:
            with open(path, "wb") as f:
                f.write(jpg)
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"Could not save snapshot:\n{e}")

    def on_close(self):
        if self.stream:
            self.stream.stop()
        self.root.destroy()


def main():
    # Force Windows shell to associate the process with its own AppUserModelID
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("vgc.earscope.camera.1.0")
    except Exception:
        pass

    root = tk.Tk()
    app = EarScopeApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()