#!/usr/bin/env python3
import sys
import os
import time
import json
import socket
import signal
import webbrowser
import threading
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    import psutil
except ImportError:
    print("ERROR: 'psutil' is required. Please install it using:")
    print("       pip install psutil")
    sys.exit(1)

try:
    import tkinter as tk
    HAS_TKINTER = True
except ImportError:
    HAS_TKINTER = False

PID_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "taskmanager_server.pid")

class CompactQR:
    def __init__(self, text):
        self.text = text
        self.data = text.encode('utf-8')

    def generate_matrix(self):
        length = len(self.data)
        if length <= 26:
            version = 2
            size = 25
            data_codewords = 28
            ecc_codewords = 16
            align_pos = [18]
        elif length <= 42:
            version = 3
            size = 29
            data_codewords = 44
            ecc_codewords = 26
            align_pos = [22]
        else:
            version = 4
            size = 33
            data_codewords = 64
            ecc_codewords = 36
            align_pos = [26]

        matrix = [[None for _ in range(size)] for _ in range(size)]
        reserved = [[False for _ in range(size)] for _ in range(size)]

        def place_finder(r, c):
            for dr in range(-1, 8):
                for dc in range(-1, 8):
                    nr, nc = r + dr, c + dc
                    if 0 <= nr < size and 0 <= nc < size:
                        if 0 <= dr <= 6 and 0 <= dc <= 6:
                            if dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4):
                                matrix[nr][nc] = 1
                            else:
                                matrix[nr][nc] = 0
                        else:
                            matrix[nr][nc] = 0
                        reserved[nr][nc] = True

        place_finder(0, 0)
        place_finder(0, size - 7)
        place_finder(size - 7, 0)

        for ar in align_pos:
            for ac in align_pos:
                if reserved[ar][ac]:
                    continue
                for dr in range(-2, 3):
                    for dc in range(-2, 3):
                        nr, nc = ar + dr, ac + dc
                        if max(abs(dr), abs(dc)) == 2 or (dr == 0 and dc == 0):
                            matrix[nr][nc] = 1
                        else:
                            matrix[nr][nc] = 0
                        reserved[nr][nc] = True

        for i in range(size):
            if not reserved[6][i]:
                matrix[6][i] = 1 if i % 2 == 0 else 0
                reserved[6][i] = True
            if not reserved[i][6]:
                matrix[i][6] = 1 if i % 2 == 0 else 0
                reserved[i][6] = True

        matrix[4 * version + 9][8] = 1
        reserved[4 * version + 9][8] = True

        for i in range(9):
            if not reserved[8][i]:
                reserved[8][i] = True
            if not reserved[i][8]:
                reserved[i][8] = True
            if not reserved[8][size - 1 - i]:
                reserved[8][size - 1 - i] = True
            if not reserved[size - 1 - i][8]:
                reserved[size - 1 - i][8] = True

        bitstream = [0, 1, 0, 0]
        data_len = len(self.data)
        for i in range(7, -1, -1):
            bitstream.append((data_len >> i) & 1)
        for byte in self.data:
            for i in range(7, -1, -1):
                bitstream.append((byte >> i) & 1)

        bitstream.extend([0] * min(4, data_codewords * 8 - len(bitstream)))
        while len(bitstream) % 8 != 0:
            bitstream.append(0)

        pad_bytes = [236, 17]
        pad_idx = 0
        while len(bitstream) < data_codewords * 8:
            pb = pad_bytes[pad_idx % 2]
            for i in range(7, -1, -1):
                bitstream.append((pb >> i) & 1)
            pad_idx += 1

        data_bytes = []
        for i in range(0, len(bitstream), 8):
            val = 0
            for b in range(8):
                val = (val << 1) | bitstream[i + b]
            data_bytes.append(val)

        ecc_bytes = self._rs_encode(data_bytes, ecc_codewords)
        all_codewords = data_bytes + ecc_bytes

        all_bits = []
        for cw in all_codewords:
            for i in range(7, -1, -1):
                all_bits.append((cw >> i) & 1)

        bit_idx = 0
        right = size - 1
        up = True
        while right > 0:
            if right == 6:
                right -= 1
            cols = [right, right - 1]
            rows = range(size - 1, -1, -1) if up else range(size)
            for r in rows:
                for c in cols:
                    if not reserved[r][c]:
                        if bit_idx < len(all_bits):
                            matrix[r][c] = all_bits[bit_idx]
                            bit_idx += 1
                        else:
                            matrix[r][c] = 0
            up = not up
            right -= 2

        for r in range(size):
            for c in range(size):
                if not reserved[r][c] and (r + c) % 2 == 0:
                    matrix[r][c] ^= 1

        format_bits = [1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0]
        seq1 = [(8, 0), (8, 1), (8, 2), (8, 3), (8, 4), (8, 5), (8, 7), (8, 8),
                (7, 8), (5, 8), (4, 8), (3, 8), (2, 8), (1, 8), (0, 8)]
        for (r, c), bit in zip(seq1, format_bits):
            matrix[r][c] = bit

        seq2 = [(size - 1, 8), (size - 2, 8), (size - 3, 8), (size - 4, 8), (size - 5, 8), (size - 6, 8), (size - 7, 8),
                (8, size - 8), (8, size - 7), (8, size - 6), (8, size - 5), (8, size - 4), (8, size - 3), (8, size - 2), (8, size - 1)]
        for (r, c), bit in zip(seq2, format_bits):
            matrix[r][c] = bit

        return matrix

    def _rs_encode(self, data, ecc_len):
        gf_exp = [0] * 512
        gf_log = [0] * 256
        x = 1
        for i in range(255):
            gf_exp[i] = x
            gf_log[x] = i
            x <<= 1
            if x & 256:
                x ^= 285
        for i in range(255, 512):
            gf_exp[i] = gf_exp[i - 255]

        gen = [1]
        for i in range(ecc_len):
            g = [0] * (len(gen) + 1)
            for j in range(len(gen)):
                g[j] ^= gf_exp[(gf_log[gen[j]] + i) % 255]
                g[j + 1] ^= gen[j]
            gen = g

        res = [0] * ecc_len
        for d in data:
            coef = d ^ res[0]
            res = res[1:] + [0]
            if coef != 0:
                log_c = gf_log[coef]
                for i in range(ecc_len):
                    res[i] ^= gf_exp[(log_c + gf_log[gen[i]]) % 255]
        return res

    def print_terminal(self):
        matrix = self.generate_matrix()
        size = len(matrix)
        quiet_zone = 2

        WHITE_BG = "\033[47m  \033[0m"
        BLACK_BG = "\033[40m  \033[0m"

        full_width = size + quiet_zone * 2

        for _ in range(quiet_zone):
            print(WHITE_BG * full_width)

        for r in range(size):
            line = WHITE_BG * quiet_zone
            for c in range(size):
                if matrix[r][c] == 1:
                    line += BLACK_BG
                else:
                    line += WHITE_BG
            line += WHITE_BG * quiet_zone
            print(line)

        for _ in range(quiet_zone):
            print(WHITE_BG * full_width)

    def to_svg(self):
        matrix = self.generate_matrix()
        size = len(matrix)
        quiet_zone = 2
        total_size = size + quiet_zone * 2

        rects = []
        for r in range(size):
            for c in range(size):
                if matrix[r][c] == 1:
                    rects.append(f'<rect x="{c + quiet_zone}" y="{r + quiet_zone}" width="1" height="1" fill="#000"/>')

        rects_str = "".join(rects)
        return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_size} {total_size}" width="280" height="280" style="background:#fff; padding:12px; border-radius:12px; box-shadow:0 4px 15px rgba(0,0,0,0.5);">
<rect width="100%" height="100%" fill="#fff"/>
{rects_str}
</svg>'''

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def get_cpu_temp():
    try:
        if hasattr(psutil, "sensors_temperatures"):
            temps = psutil.sensors_temperatures()
            if temps:
                for key in ['coretemp', 'cpu_thermal', 'cpu-thermal', 'k10temp', 'zenpower', 'acpitz', 'soc_thermal']:
                    if key in temps and temps[key]:
                        for entry in temps[key]:
                            if entry.current and entry.current > 0:
                                return round(entry.current, 1)
                for sensor_list in temps.values():
                    for entry in sensor_list:
                        if entry.current and entry.current > 0:
                            return round(entry.current, 1)
    except Exception:
        pass

    if os.name == 'nt':
        try:
            import subprocess
            cmd = 'powershell -Command "Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature | Select-Object -ExpandProperty CurrentTemperature"'
            output = subprocess.check_output(cmd, shell=True, timeout=1.5, stderr=subprocess.DEVNULL).decode('utf-8').strip()
            if output:
                lines = [line.strip() for line in output.split('\n') if line.strip()]
                if lines:
                    raw_kelvin = float(lines[0])
                    celsius = round((raw_kelvin / 10.0) - 273.15, 1)
                    if 0 < celsius < 120:
                        return celsius
        except Exception:
            pass

    return 0.0

def get_gpu_stats():
    gpu_info = {
        "name": "N/A",
        "percent": 0.0,
        "memory_used_mb": 0,
        "memory_total_mb": 0,
        "temp_c": 0
    }

    try:
        import subprocess
        cmd = ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu", "--format=csv,noheader,nounits"]
        output = subprocess.check_output(cmd, timeout=1, stderr=subprocess.DEVNULL).decode('utf-8').strip()
        if output:
            lines = output.split('\n')[0].split(',')
            if len(lines) >= 5:
                gpu_info["name"] = lines[0].strip()
                gpu_info["percent"] = float(lines[1].strip())
                gpu_info["memory_used_mb"] = int(float(lines[2].strip()))
                gpu_info["memory_total_mb"] = int(float(lines[3].strip()))
                gpu_info["temp_c"] = int(float(lines[4].strip()))
                return gpu_info
    except Exception:
        pass

    if os.name == 'posix':
        try:
            import glob
            drm_cards = glob.glob('/sys/class/drm/card[0-9]/device')
            for card in drm_cards:
                busy_file = os.path.join(card, 'gpu_busy_percent')
                vram_used_file = os.path.join(card, 'mem_info_vram_used')
                vram_total_file = os.path.join(card, 'mem_info_vram_total')
                hwmon_temps = glob.glob(os.path.join(card, 'hwmon/hwmon*/temp1_input'))

                if os.path.exists(busy_file):
                    with open(busy_file, 'r') as f:
                        gpu_info["percent"] = float(f.read().strip())

                    if os.path.exists(vram_used_file) and os.path.exists(vram_total_file):
                        with open(vram_used_file, 'r') as f:
                            gpu_info["memory_used_mb"] = int(int(f.read().strip()) / (1024 * 1024))
                        with open(vram_total_file, 'r') as f:
                            gpu_info["memory_total_mb"] = int(int(f.read().strip()) / (1024 * 1024))

                    if hwmon_temps and os.path.exists(hwmon_temps[0]):
                        with open(hwmon_temps[0], 'r') as f:
                            gpu_info["temp_c"] = int(int(f.read().strip()) / 1000)

                    try:
                        import subprocess
                        out = subprocess.check_output("lspci | grep -i 'VGA\\|3D'", shell=True, timeout=1).decode('utf-8')
                        for line in out.split('\n'):
                            if 'AMD' in line or 'Radeon' in line or 'Navi' in line:
                                parts = line.split(': ')
                                gpu_info["name"] = parts[-1].strip() if len(parts) > 1 else line.strip()
                                break
                    except Exception:
                        pass

                    if gpu_info["name"] == "N/A":
                        gpu_info["name"] = "AMD Radeon GPU"

                    return gpu_info
        except Exception:
            pass

    if os.name == 'nt':
        try:
            import subprocess
            cmd = 'powershell -Command "Get-CimInstance Win32_VideoController | Select-Object Name, AdapterRAM | ConvertTo-Json"'
            output = subprocess.check_output(cmd, shell=True, timeout=2.5, stderr=subprocess.DEVNULL).decode('utf-8').strip()
            if output:
                data = json.loads(output)
                if isinstance(data, dict):
                    data = [data]

                selected_gpu = None
                for gpu_item in data:
                    name = gpu_item.get("Name", "")
                    if any(vendor in name for vendor in ["Radeon", "GeForce", "RTX", "GTX", "RX ", "ARC", "NVIDIA", "AMD"]):
                        selected_gpu = gpu_item
                        break
                if not selected_gpu and len(data) > 0:
                    selected_gpu = data[0]

                if selected_gpu:
                    gpu_info["name"] = selected_gpu.get("Name", "N/A")
                    ram = selected_gpu.get("AdapterRAM", 0)
                    if ram and isinstance(ram, (int, float)) and ram > 0:
                        vram_mb = int(ram / (1024 * 1024))
                        if vram_mb < 0:
                            vram_mb = int((ram + 4294967296) / (1024 * 1024))
                        if "6700" in gpu_info["name"] and (vram_mb < 4000 or vram_mb > 16000):
                            vram_mb = 12288
                        gpu_info["memory_total_mb"] = vram_mb
        except Exception:
            pass

        try:
            import ctypes
            adl = None
            for dll_name in ["atiadlxx.dll", "atiadlxy.dll"]:
                try:
                    adl = ctypes.cdll.LoadLibrary(dll_name)
                    if adl:
                        break
                except Exception:
                    pass

            if adl:
                class ADLTemperature(ctypes.Structure):
                    _fields_ = [("iSize", ctypes.c_int), ("iTemperature", ctypes.c_int)]

                temp_struct = ADLTemperature(ctypes.sizeof(ADLTemperature), 0)
                if hasattr(adl, "ADL_Overdrive5_Temperature_Get"):
                    if adl.ADL_Overdrive5_Temperature_Get(0, 0, ctypes.byref(temp_struct)) == 0:
                        gpu_info["temp_c"] = int(temp_struct.iTemperature / 1000)
        except Exception:
            pass

        try:
            import subprocess
            cmd = 'powershell -Command "Get-CimInstance -Namespace root/LibreHardwareMonitor -ClassName Sensor | Where-Object SensorType -eq \'Temperature\' | Select-Object Name, Value | ConvertTo-Json"'
            output = subprocess.check_output(cmd, shell=True, timeout=1.5, stderr=subprocess.DEVNULL).decode('utf-8').strip()
            if output:
                sensors = json.loads(output)
                if isinstance(sensors, dict):
                    sensors = [sensors]
                for s in sensors:
                    s_name = s.get("Name", "").lower()
                    if "gpu" in s_name or "core" in s_name:
                        val = s.get("Value", 0)
                        if val > 0:
                            gpu_info["temp_c"] = int(val)
                            break
        except Exception:
            pass

        try:
            import subprocess
            cmd = 'powershell -Command "(Get-Counter \'\\GPU Engine(*3D*)\\UtilizationPercentage\' -ErrorAction SilentlyContinue).CounterSamples | Measure-Object -Property CookedValue -Sum | Select-Object -ExpandProperty Sum"'
            output = subprocess.check_output(cmd, shell=True, timeout=2.0, stderr=subprocess.DEVNULL).decode('utf-8').strip()
            if output:
                val = float(output)
                gpu_info["percent"] = round(min(val, 100.0), 1)
        except Exception:
            pass

        if gpu_info["name"] != "N/A":
            return gpu_info

    try:
        import GPUtil
        gpus = GPUtil.getGPUs()
        if gpus:
            gpu = gpus[0]
            gpu_info["name"] = gpu.name
            gpu_info["percent"] = round(gpu.load * 100, 1)
            gpu_info["memory_used_mb"] = int(gpu.memoryUsed)
            gpu_info["memory_total_mb"] = int(gpu.memoryTotal)
            gpu_info["temp_c"] = int(gpu.temperature) if gpu.temperature else 0
            return gpu_info
    except Exception:
        pass

    return gpu_info

CURRENT_PC_VOLUME = 50

def get_pc_volume():
    global CURRENT_PC_VOLUME
    if os.name == 'posix':
        try:
            import subprocess
            out = subprocess.check_output("amixer sget Master | grep -o '[0-9]*%' | head -n 1", shell=True, timeout=1).decode('utf-8').strip()
            if out:
                CURRENT_PC_VOLUME = int(out.replace("%", ""))
                return CURRENT_PC_VOLUME
        except Exception:
            pass
    return CURRENT_PC_VOLUME

def set_pc_volume(level):
    global CURRENT_PC_VOLUME
    level = max(0, min(100, int(level)))
    CURRENT_PC_VOLUME = level

    if os.name == 'nt':
        try:
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
            from ctypes import cast, POINTER
            from comtypes import CLSCTX_ALL
            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume = cast(interface, POINTER(IAudioEndpointVolume))
            volume.SetMasterVolumeLevelScalar(level / 100.0, None)
            return True
        except Exception:
            pass

        try:
            import subprocess
            cmd = f'powershell -Command "$code = @\" using System; using System.Runtime.InteropServices; namespace AudioLib {{ public class VolumeControl {{ [Guid(\\\"5CDF2C82-841E-4546-9722-0CF74078229A\\\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] private interface IAudioEndpointVolume {{ int RegisterControlChangeNotify(IntPtr pNotify); int UnregisterControlChangeNotify(IntPtr pNotify); int GetChannelCount(out uint pnChannelCount); int SetMasterVolumeLevel(float fLevelDB, Guid pguidEventContext); int SetMasterVolumeLevelScalar(float fLevel, Guid pguidEventContext); int GetMasterVolumeLevel(out float pfLevelDB); int GetMasterVolumeLevelScalar(out float pfLevel); }} [Guid(\\\"D666063F-1587-4E43-81F1-B948E807363F\\\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] private interface IMMDevice {{ int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface); }} [Guid(\\\"A95664D2-9614-4F35-A746-DE8DB63617E6\\\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] private interface IMMDeviceEnumerator {{ int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IntPtr ppDevices); int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint); }} [ComImport, Guid(\\\"BCDE0395-E52F-467C-8E3D-C4579291692E\\\")] private class MMDeviceEnumeratorComObject {{ }} public static void SetVolume(float levelPercent) {{ try {{ var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject()); IMMDevice dev; enumerator.GetDefaultAudioEndpoint(0, 0, out dev); var iid = new Guid(\\\"5CDF2C82-841E-4546-9722-0CF74078229A\\\"); object obj; dev.Activate(ref iid, 23, IntPtr.Zero, out obj); var vol = (IAudioEndpointVolume)obj; vol.SetMasterVolumeLevelScalar(levelPercent / 100f, Guid.Empty); }} catch {{ }} }} }} \"@; Add-Type -TypeDefinition $code; [AudioLib.VolumeControl]::SetVolume({level})"'
            subprocess.Popen(cmd, shell=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        except Exception:
            pass

    elif os.name == 'posix':
        try:
            import subprocess
            subprocess.Popen(f"pactl set-sink-volume @DEFAULT_SINK@ {level}%", shell=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
            subprocess.Popen(f"amixer -D pulse sset Master {level}%", shell=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        except Exception:
            pass

    elif sys.platform == 'darwin':
        try:
            import subprocess
            subprocess.Popen(f"osascript -e 'set volume output volume {level}'", shell=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
        except Exception:
            pass

    return True

def get_active_window_info():
    if os.name == 'nt':
        try:
            import ctypes
            user32 = ctypes.windll.user32
            hwnd = user32.GetForegroundWindow()
            if hwnd:
                pid = ctypes.c_ulong()
                user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                p_pid = pid.value
                if p_pid:
                    try:
                        proc = psutil.Process(p_pid)
                        return p_pid, proc.name()
                    except Exception:
                        pass
        except Exception:
            pass
    return None, ""

def get_rtss_fps():
    try:
        import mmap
        import struct
        for shm_name in ["RTSSSharedMemoryV2", "RTSSSharedMemory"]:
            try:
                mm = mmap.mmap(-1, 65536, shm_name)
                if mm:
                    sig = mm[0:4]
                    if sig == b'RTSS':
                        app_num = struct.unpack("I", mm[8:12])[0]
                        if app_num > 0:
                            for i in range(app_num):
                                entry_offset = 256 + (i * 512)
                                if entry_offset + 32 < len(mm):
                                    fps_val = struct.unpack("I", mm[entry_offset + 28: entry_offset + 32])[0]
                                    if fps_val > 0:
                                        mm.close()
                                        return fps_val
                    mm.close()
            except Exception:
                pass
    except Exception:
        pass
    return 0

LAST_DWM_FRAME = 0
LAST_DWM_TIME = time.time()
MEASURED_GAME_FPS = 0

def get_dwm_fps():
    if os.name == 'nt':
        try:
            import ctypes
            dwmapi = ctypes.windll.dwmapi
            class DWM_TIMING_INFO(ctypes.Structure):
                _fields_ = [
                    ("cbSize", ctypes.c_uint32),
                    ("rateRefresh", ctypes.c_uint64),
                    ("qpcRefreshPeriod", ctypes.c_uint64),
                    ("rateCompose", ctypes.c_uint64),
                    ("qpcVBlank", ctypes.c_uint64),
                    ("cRefresh", ctypes.c_uint64),
                    ("cDXRefresh", ctypes.c_uint32),
                    ("qpcCompose", ctypes.c_uint64),
                    ("cFrame", ctypes.c_uint64),
                    ("cDXPresent", ctypes.c_uint32),
                    ("cRefreshFrame", ctypes.c_uint64),
                    ("cFrameSubmitted", ctypes.c_uint64),
                    ("cDXPresentSubmitted", ctypes.c_uint32),
                    ("cFrameConfirmed", ctypes.c_uint64),
                    ("cDXPresentConfirmed", ctypes.c_uint32),
                    ("cRefreshConfirmed", ctypes.c_uint64),
                    ("cDXRefreshConfirmed", ctypes.c_uint32),
                    ("cFramesLate", ctypes.c_uint64),
                    ("cGlitches", ctypes.c_uint32),
                    ("cCompositionSubmitted", ctypes.c_uint64),
                    ("cFramesSubmitted", ctypes.c_uint64),
                    ("cPredictionsSubmitted", ctypes.c_uint64),
                    ("cFrameCompleted", ctypes.c_uint64),
                    ("cFramePending", ctypes.c_uint64),
                    ("cFrameDisplayed", ctypes.c_uint64),
                    ("cGlitchesInFrame", ctypes.c_uint32),
                    ("cNumberFrames", ctypes.c_uint64),
                ]
            info = DWM_TIMING_INFO()
            info.cbSize = ctypes.sizeof(DWM_TIMING_INFO)
            res = dwmapi.DwmGetCompositionTimingInfo(0, ctypes.byref(info))
            if res == 0:
                return info.cFrame
        except Exception:
            pass
    return None

def get_active_game_info_and_fps():
    global LAST_DWM_FRAME, LAST_DWM_TIME, MEASURED_GAME_FPS
    pid, proc_name = get_active_window_info()

    clean_name = ""
    if proc_name and not proc_name.lower().startswith("explorer"):
        clean_name = proc_name.replace(".exe", "").replace("-Win64-Shipping", "").replace("_x64", "").title()

    rtss_fps = get_rtss_fps()
    if rtss_fps > 0:
        return clean_name if clean_name else "Game", rtss_fps

    if os.name == 'nt':
        c_frame = get_dwm_fps()
        now = time.time()
        if c_frame is not None and LAST_DWM_FRAME > 0:
            dt = now - LAST_DWM_TIME
            if dt >= 0.2:
                df = c_frame - LAST_DWM_FRAME
                fps_calc = int(round(df / dt))
                if 1 <= fps_calc <= 600:
                    MEASURED_GAME_FPS = fps_calc
                LAST_DWM_FRAME = c_frame
                LAST_DWM_TIME = now
        elif c_frame is not None:
            LAST_DWM_FRAME = c_frame
            LAST_DWM_TIME = now

        if MEASURED_GAME_FPS > 0:
            return clean_name, MEASURED_GAME_FPS

    refresh = get_pc_fps()
    return clean_name, refresh

def get_pc_fps():
    if os.name == 'nt':
        try:
            import subprocess
            cmd = 'powershell -Command "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty CurrentRefreshRate"'
            output = subprocess.check_output(cmd, shell=True, timeout=1.5, stderr=subprocess.DEVNULL).decode('utf-8').strip()
            if output:
                rates = [int(r.strip()) for r in output.split('\n') if r.strip().isdigit()]
                if rates and max(rates) > 0:
                    return max(rates)
        except Exception:
            pass
    return 60

LAST_NET_BYTES_SENT = 0
LAST_NET_BYTES_RECV = 0
LAST_NET_TIME = time.time()
CURRENT_NET_DOWN_SPEED = "0.0 KB/s"
CURRENT_NET_UP_SPEED = "0.0 KB/s"

def format_bytes_speed(bytes_per_sec):
    if bytes_per_sec >= 1024 * 1024:
        return f"{round(bytes_per_sec / (1024 * 1024), 1)} MB/s"
    elif bytes_per_sec >= 1024:
        return f"{round(bytes_per_sec / 1024, 1)} KB/s"
    else:
        return f"{int(bytes_per_sec)} B/s"

def get_network_stats():
    global LAST_NET_BYTES_SENT, LAST_NET_BYTES_RECV, LAST_NET_TIME, CURRENT_NET_DOWN_SPEED, CURRENT_NET_UP_SPEED
    try:
        net_io = psutil.net_io_counters()
        now = time.time()
        if net_io and LAST_NET_TIME > 0 and LAST_NET_BYTES_RECV > 0:
            dt = now - LAST_NET_TIME
            if dt >= 0.2:
                down_bytes = (net_io.bytes_recv - LAST_NET_BYTES_RECV) / dt
                up_bytes = (net_io.bytes_sent - LAST_NET_BYTES_SENT) / dt

                CURRENT_NET_DOWN_SPEED = format_bytes_speed(max(0, down_bytes))
                CURRENT_NET_UP_SPEED = format_bytes_speed(max(0, up_bytes))

                LAST_NET_BYTES_SENT = net_io.bytes_sent
                LAST_NET_BYTES_RECV = net_io.bytes_recv
                LAST_NET_TIME = now
        elif net_io:
            LAST_NET_BYTES_SENT = net_io.bytes_sent
            LAST_NET_BYTES_RECV = net_io.bytes_recv
            LAST_NET_TIME = now
    except Exception:
        pass

    return {
        "down": CURRENT_NET_DOWN_SPEED,
        "up": CURRENT_NET_UP_SPEED
    }

def get_pc_stats():
    cpu_percent = psutil.cpu_percent(interval=0.1)
    cpu_count = psutil.cpu_count(logical=True)
    cpu_temp = get_cpu_temp()

    ram = psutil.virtual_memory()
    ram_used_mb = round(ram.used / (1024 * 1024), 1)
    ram_total_mb = round(ram.total / (1024 * 1024), 1)
    ram_percent = ram.percent

    gpu = get_gpu_stats()
    game_name, game_fps = get_active_game_info_and_fps()
    net = get_network_stats()

    storage_list = []
    try:
        for part in psutil.disk_partitions(all=False):
            if part.fstype and (os.name == 'nt' or part.mountpoint == '/'):
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    storage_list.append({
                        "device": part.device,
                        "mountpoint": part.mountpoint,
                        "used_gb": round(usage.used / (1024 ** 3), 1),
                        "total_gb": round(usage.total / (1024 ** 3), 1),
                        "percent": usage.percent
                    })
                except Exception:
                    pass
    except Exception:
        pass

    return {
        "status": "online",
        "hostname": socket.gethostname(),
        "cpu_percent": cpu_percent,
        "cpu_count": cpu_count,
        "cpu_temp_c": cpu_temp,
        "ram_used_mb": ram_used_mb,
        "ram_total_mb": ram_total_mb,
        "ram_percent": ram_percent,
        "gpu_name": gpu["name"],
        "gpu_percent": gpu["percent"],
        "gpu_mem_used_mb": gpu["memory_used_mb"],
        "gpu_mem_total_mb": gpu["memory_total_mb"],
        "gpu_temp_c": gpu["temp_c"],
        "fps": game_fps,
        "focused_app": game_name,
        "net_down": net["down"],
        "net_up": net["up"],
        "volume": get_pc_volume(),
        "storage": storage_list,
        "timestamp": time.time()
    }

class ReusableHTTPServer(HTTPServer):
    allow_reuse_address = True

class TaskManagerHTTPHandler(BaseHTTPRequestHandler):
    server_url = ""

    def log_message(self, format, *args):
        now = datetime.now().strftime("%H:%M:%S")
        print(f"[{now}] HTTP Request: {args[0]}")

    def do_GET(self):
        if self.path.startswith("/volume") or self.path.startswith("/set_volume"):
            from urllib.parse import urlparse, parse_qs
            query = parse_qs(urlparse(self.path).query)
            val_list = query.get("val") or query.get("level") or query.get("volume")
            new_vol = CURRENT_PC_VOLUME
            if val_list and val_list[0].isdigit():
                new_vol = int(val_list[0])
                set_pc_volume(new_vol)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            res = {"status": "ok", "volume": new_vol}
            self.wfile.write(json.dumps(res).encode("utf-8"))
            return

        if self.path in ["/stats", "/api/stats", "/json"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            stats = get_pc_stats()
            self.wfile.write(json.dumps(stats, indent=2).encode("utf-8"))
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            stats = get_pc_stats()
            qr = CompactQR(TaskManagerHTTPHandler.server_url)
            svg_qr = qr.to_svg()

            html = f"""<!DOCTYPE html>
<html>
<head>
    <title>TaskManager Monitor</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body {{ font-family: Arial, sans-serif; background: #121212; color: #fff; text-align: center; padding: 20px; }}
        .card {{ background: #1e1e1e; padding: 20px; margin: 15px auto; max-width: 420px; border-radius: 12px; box-shadow: 0 4px 10px rgba(0,0,0,0.5); }}
        h1 {{ color: #00FF66; margin-bottom: 5px; }}
        .val {{ font-size: 1.4em; font-weight: bold; color: #00FF66; }}
        .url {{ font-size: 1.1em; color: #00FF66; font-family: monospace; word-break: break-all; margin: 10px 0; }}
        .qr-box {{ margin: 20px auto; }}
    </style>
</head>
<body>
    <h1>🖥️ TaskManager Monitor Server</h1>
    <p style="color:#aaa;">Pindai Kode QR di bawah menggunakan aplikasi Android TaskManager</p>

    <div class="qr-box">
        {svg_qr}
    </div>

    <div class="url">{TaskManagerHTTPHandler.server_url}</div>

    <div class="card">
        <h3>Host PC</h3>
        <div class="val">{stats['hostname']}</div>
    </div>
    <div class="card">
        <h3>Penggunaan CPU</h3>
        <div class="val">{stats['cpu_percent']}% ({stats['cpu_count']} cores)</div>
    </div>
    <div class="card">
        <h3>Penggunaan RAM</h3>
        <div class="val">{stats['ram_used_mb']} MB / {stats['ram_total_mb']} MB ({stats['ram_percent']}%)</div>
    </div>
</body>
</html>"""
            self.wfile.write(html.encode("utf-8"))

def start_gui_window(url, web_url, http_server, qr_text=None):
    root = tk.Tk()
    root.title("TaskManager - Scan QR Code")
    root.configure(bg="#1E1E1E")
    root.resizable(False, False)

    window_width, window_height = 400, 560
    screen_width = root.winfo_screenwidth()
    screen_height = root.winfo_screenheight()
    x = (screen_width // 2) - (window_width // 2)
    y = (screen_height // 2) - (window_height // 2)
    root.geometry(f"{window_width}x{window_height}+{x}+{y}")

    label_title = tk.Label(
        root,
        text="🖥️ TaskManager Monitor Server",
        font=("Helvetica", 16, "bold"),
        fg="#00FF66",
        bg="#1E1E1E"
    )
    label_title.pack(pady=(20, 5))

    label_sub = tk.Label(
        root,
        text="Arahkan kamera HP ke Kode QR di bawah:",
        font=("Helvetica", 10),
        fg="#CCCCCC",
        bg="#1E1E1E"
    )
    label_sub.pack(pady=(0, 15))

    target_qr_text = qr_text if qr_text else url
    qr = CompactQR(target_qr_text)
    matrix = qr.generate_matrix()
    size = len(matrix)
    quiet_zone = 2
    total_modules = size + quiet_zone * 2

    box_size = 8
    canvas_dim = total_modules * box_size

    frame_canvas = tk.Frame(root, bg="#FFFFFF", padx=8, pady=8)
    frame_canvas.pack()

    canvas = tk.Canvas(
        frame_canvas,
        width=canvas_dim,
        height=canvas_dim,
        bg="#FFFFFF",
        highlightthickness=0
    )
    canvas.pack()

    for r in range(size):
        for c in range(size):
            if matrix[r][c] == 1:
                x0 = (c + quiet_zone) * box_size
                y0 = (r + quiet_zone) * box_size
                x1 = x0 + box_size
                y1 = y0 + box_size
                canvas.create_rectangle(x0, y0, x1, y1, fill="#000000", outline="")

    label_url = tk.Label(
        root,
        text=url,
        font=("Courier", 10, "bold"),
        fg="#00FF66",
        bg="#1E1E1E",
        wraplength=360
    )
    label_url.pack(pady=(12, 5))

    label_status = tk.Label(
        root,
        text="● Server Aktif & Mendengarkan...",
        font=("Helvetica", 10, "bold"),
        fg="#00FF66",
        bg="#1E1E1E"
    )
    label_status.pack(pady=(0, 15))

    frame_btns = tk.Frame(root, bg="#1E1E1E")
    frame_btns.pack(pady=(0, 15))

    btn_browser = tk.Button(
        frame_btns,
        text="🌐 Web Dashboard",
        font=("Helvetica", 9, "bold"),
        bg="#333333",
        fg="#FFFFFF",
        activebackground="#555555",
        activeforeground="#FFFFFF",
        relief="flat",
        padx=10,
        pady=5,
        command=lambda: webbrowser.open(web_url)
    )
    btn_browser.pack(side="left", padx=5)

    def stop_and_close():
        print("[!] Stopping TaskManager server & closing GUI...")
        label_status.config(text="● Server Dihentikan", fg="#FF3B30")
        root.update()
        cleanup_pid()
        threading.Thread(target=http_server.shutdown, daemon=True).start()
        root.after(300, root.destroy)

    btn_stop = tk.Button(
        frame_btns,
        text="🛑 Stop Server",
        font=("Helvetica", 9, "bold"),
        bg="#882222",
        fg="#FFFFFF",
        activebackground="#AA3333",
        activeforeground="#FFFFFF",
        relief="flat",
        padx=10,
        pady=5,
        command=stop_and_close
    )
    btn_stop.pack(side="left", padx=5)

    root.protocol("WM_DELETE_WINDOW", stop_and_close)
    root.mainloop()

def save_pid():
    try:
        with open(PID_FILE, "w") as f:
            f.write(str(os.getpid()))
    except Exception:
        pass

def cleanup_pid():
    if os.path.exists(PID_FILE):
        try:
            os.remove(PID_FILE)
        except Exception:
            pass

def stop_running_server():
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE, "r") as f:
                pid = int(f.read().strip())
            if pid != os.getpid() and psutil.pid_exists(pid):
                os.kill(pid, signal.SIGTERM)
                print(f"[+] TaskManager server (PID {pid}) berhasil dihentikan (Stopped).")
            else:
                print(f"[!] Server (PID {pid}) tidak aktif.")
            cleanup_pid()
            return True
        except Exception as e:
            print(f"[-] Gagal menghentikan server: {e}")
            cleanup_pid()
            return False
    else:
        print("[!] Tidak ada server TaskManager yang sedang berjalan.")
        return False

def main():
    if len(sys.argv) > 1 and sys.argv[1].lower() in ["stop", "--stop", "-s", "kill"]:
        stop_running_server()
        sys.exit(0)

    if os.path.exists(PID_FILE):
        stop_running_server()
        time.sleep(0.5)

    save_pid()

    ip = get_local_ip()
    server = None
    selected_port = 8080

    for port_candidate in range(8080, 8090):
        try:
            server = ReusableHTTPServer(("0.0.0.0", port_candidate), TaskManagerHTTPHandler)
            selected_port = port_candidate
            break
        except OSError:
            continue

    if server is None:
        print("ERROR: tidak dapat menggunakan port 8080-8090.")
        cleanup_pid()
        sys.exit(1)

    server_url = f"http://{ip}:{selected_port}/stats"
    web_page_url = f"http://{ip}:{selected_port}/"
    ip_port_str = f"{ip}:{selected_port}"
    TaskManagerHTTPHandler.server_url = server_url

    print("\n" + "=" * 60)
    print("   🖥️  TASKMANAGER MONITOR SERVER STARTED  🖥️")
    print("=" * 60)
    print(f" PC Local IP  : {ip}")
    print(f" Server Port : {selected_port}")
    print(f" Target URL  : {server_url}")
    print(f" PID Process : {os.getpid()}")
    print("=" * 60)
    print("\n[!] Perintah Stop Server: python3 host.py stop")
    print("\n[!] Kode QR High-Contrast (Terminal):\n")

    qr = CompactQR(ip_port_str)
    qr.print_terminal()

    print("\n" + "=" * 60)
    print(f" Web Browser: {web_page_url}")
    print(f" Listening for Android connections at {server_url} ...")
    print(" Tekan Ctrl+C atau jalankan 'python3 host.py stop' untuk menghentikan.")
    print("=" * 60 + "\n")

    def handle_signal(sig, frame):
        print("\n[!] Menerima sinyal penghentian, mematikan server TaskManager...")
        cleanup_pid()
        try:
            server.shutdown()
        except Exception:
            pass
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    if HAS_TKINTER:
        try:
            start_gui_window(server_url, web_page_url, server, qr_text=ip_port_str)
        except Exception as e:
            print(f"[!] Tidak dapat membuka GUI Tkinter: {e}")
            print("[!] Menjalankan dalam mode konsol.")
            server_thread.join()
    else:
        print("[!] Tkinter tidak tersedia, menjalankan dalam mode konsol.")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            handle_signal(None, None)

if __name__ == "__main__":
    main()
