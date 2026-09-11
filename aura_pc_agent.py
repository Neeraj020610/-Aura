"""
Aura PC Agent (Windows)
Connects to Aura Central Hub via WebSocket.
Executes physical lock, sleep, app control, and sends live battery/CPU telemetry.
"""

import os
import sys
import json
import time
import ctypes
import threading
import subprocess
import psutil
import websocket

# Configuration
SERVER_WS_URL = "ws://localhost:3000"
DEVICE_ID = "pc"  # or 'laptop'
DEVICE_NAME = f"PC ({os.getenv('COMPUTERNAME', 'Windows')})"

def lock_workstation():
    """Immediately locks Windows screen"""
    print("🔒 [AURA AGENT] Executing Lock Screen...")
    ctypes.windll.user32.LockWorkStation()

def trigger_alarm():
    """Beeps loudly and triggers voice speech on PC"""
    print("🔊 [AURA AGENT] Alarm triggered!")
    try:
        import winsound
        for _ in range(5):
            winsound.Beep(1000, 200)
            winsound.Beep(1500, 200)
    except Exception as e:
        print(f"Error in alarm: {e}")

def get_system_telemetry():
    """Fetches real battery % and CPU usage"""
    battery = psutil.sensors_battery()
    battery_percent = int(battery.percent) if battery else 100
    is_charging = battery.power_plugged if battery else True
    
    return {
        "type": "TELEMETRY",
        "deviceId": DEVICE_ID,
        "battery": battery_percent,
        "isCharging": is_charging,
        "screen": "ON",
        "cpu": int(psutil.cpu_percent(interval=None))
    }

def on_message(ws, message):
    try:
        data = json.loads(message)
        print(f"📥 [AURA AGENT] Received message: {data.get('type')}")

        if data.get('type') == 'COMMAND':
            action = data.get('action')
            params = data.get('params', {})
            print(f"⚡ [AURA AGENT] Action requested: {action}")

            if action == 'LOCK':
                lock_workstation()
            elif action == 'RING_ALARM':
                threading.Thread(target=trigger_alarm, daemon=True).start()
            elif action == 'SLEEP':
                print("💤 [AURA AGENT] Putting PC to sleep...")
                subprocess.run("rundll32.exe powrprof.dll,SetSuspendState 0,1,0", shell=True)
            elif action == 'SCREEN_OFF':
                # Turn off monitor using SendMessage (SC_MONITORPOWER, 2)
                ctypes.windll.user32.SendMessageW(0xFFFF, 0x0112, 0xF170, 2)
            elif action == 'SCREEN_ON':
                # Wake monitor
                ctypes.windll.user32.SendMessageW(0xFFFF, 0x0112, 0xF170, -1)
                
    except Exception as err:
        print(f"❌ Error processing message: {err}")

def on_error(ws, error):
    print(f"⚠️ [AURA AGENT] WebSocket Error: {error}")

def on_close(ws, close_status_code, close_msg):
    print("🔌 [AURA AGENT] Disconnected from Aura Server. Retrying in 3s...")
    time.sleep(3)
    start_agent()

def telemetry_loop(ws):
    """Sends telemetry every 5 seconds"""
    while ws.sock and ws.sock.connected:
        try:
            telemetry = get_system_telemetry()
            ws.send(json.dumps(telemetry))
            time.sleep(5)
        except Exception as e:
            break

def on_open(ws):
    print(f"✅ [AURA AGENT] Connected to Aura Hub as '{DEVICE_ID}' ({DEVICE_NAME})")
    
    # 1. Register device
    reg_payload = {
        "type": "REGISTER",
        "deviceId": DEVICE_ID,
        "name": DEVICE_NAME,
        "type": "desktop"
    }
    ws.send(json.dumps(reg_payload))

    # 2. Start telemetry thread
    t = threading.Thread(target=telemetry_loop, args=(ws,), daemon=True)
    t.start()

def start_agent():
    print(f"🚀 Starting Aura PC Agent connecting to {SERVER_WS_URL}...")
    ws = websocket.WebSocketApp(
        SERVER_WS_URL,
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close
    )
    ws.run_forever()

if __name__ == "__main__":
    if len(sys.argv) > 1:
        SERVER_WS_URL = sys.argv[1]
    start_agent()
