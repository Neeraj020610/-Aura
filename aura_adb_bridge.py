"""
Aura ADB Hardware Bridge
Directly links real Android phones connected via USB or Wireless ADB to the Aura Master Hub.
Provides 100% native hardware control: Real Screen Lock/Unlock, Real Calling, Real Battery Telemetry.
"""

import os
import sys
import json
import time
import subprocess
import threading
import websocket

SERVER_WS_URL = "ws://localhost:3000"
ADB_PATH = r"C:\platform-tools\adb.exe"

def run_adb(cmd, device_serial=None):
    """Executes an ADB command and returns output string"""
    try:
        if device_serial:
            full_cmd = f'"{ADB_PATH}" -s {device_serial} {cmd}'
        else:
            full_cmd = f'"{ADB_PATH}" {cmd}'
        result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=10)
        return result.stdout.strip()
    except Exception as e:
        print(f"[ADB ERROR] {e}")
        return ""

def get_connected_phones():
    """Returns list of connected Android device serials"""
    out = run_adb("devices")
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.strip().split('\t')
        if len(parts) == 2 and parts[1] == 'device':
            devices.append(parts[0])
    return devices

def get_phone_battery(serial):
    """Fetches real battery percentage and charging state via ADB"""
    out = run_adb("shell dumpsys battery", serial)
    level = 100
    charging = False
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("level:"):
            try:
                level = int(line.split(":")[1].strip())
            except:
                pass
        if line.startswith("status:"):
            # status 2 = Charging
            charging = ("2" in line or "5" in line)
    return level, charging

def is_screen_on(serial):
    """Checks if phone screen is currently ON"""
    out = run_adb("shell dumpsys power", serial)
    for line in out.splitlines():
        if "mHoldingDisplaySuspendBlocker=true" in line or "Display Power: state=ON" in line or "mWakefulness=Awake" in line:
            return True
    return False

def lock_phone(serial):
    """Physically locks Android phone screen"""
    if is_screen_on(serial):
        run_adb("shell input keyevent 26", serial)
        print(f"🔒 [AURA ADB] Phone ({serial}) screen turned OFF / Locked.")

def unlock_phone(serial, pin=""):
    """Wakes phone and unlocks (swipes up and enters PIN if provided)"""
    # 1. Wake screen
    run_adb("shell input keyevent 224", serial)
    time.sleep(0.3)
    # 2. Swipe up to show PIN / unlock screen
    run_adb("shell input swipe 500 1600 500 400 300", serial)
    time.sleep(0.3)
    # 3. Type PIN if given
    if pin:
        run_adb(f"shell input text {pin}", serial)
        time.sleep(0.2)
        run_adb("shell input keyevent 66", serial)  # Enter key
    print(f"🔓 [AURA ADB] Phone ({serial}) unlocked.")

def make_phone_call(serial, phone_number):
    """Initiates an actual real phone call on the target phone"""
    clean_num = ''.join(c for c in phone_number if c.isdigit() or c == '+')
    if not clean_num:
        clean_num = "9876543210"
    print(f"📞 [AURA ADB] Dialing {clean_num} on phone {serial}...")
    run_adb(f"shell am start -a android.intent.action.CALL -d tel:{clean_num}", serial)

def ring_alarm(serial):
    """Plays loud alarm sound and vibrates phone"""
    run_adb("shell cmd media_session volume --set 15", serial) # Max volume
    run_adb("shell am start -a android.intent.action.VIEW -d content://media/internal/audio/media/1 -t audio/*", serial)
    run_adb("shell input keyevent 224", serial)

# WebSocket Handler
class AuraAdbBridge:
    def __init__(self):
        self.ws = None
        self.running = True
        self.device_map = {} # serial -> 'phone_1' or 'phone_2'

    def connect(self):
        print(f"🚀 [Aura ADB Bridge] Connecting to {SERVER_WS_URL}...")
        self.ws = websocket.WebSocketApp(
            SERVER_WS_URL,
            on_open=self.on_open,
            on_message=self.on_message,
            on_error=self.on_error,
            on_close=self.on_close
        )
        self.ws.run_forever()

    def on_open(self, ws):
        print("✅ [Aura ADB Bridge] Connected to Aura Master Hub!")
        # Start phone polling thread
        threading.Thread(target=self.poll_phones_loop, daemon=True).start()

    def on_message(self, ws, message):
        try:
            data = json.loads(message)
            if data.get('type') == 'COMMAND':
                action = data.get('action')
                params = data.get('params', {})
                print(f"⚡ [AURA ADB] Action received: {action}, Params: {params}")

                # Find which physical serial this corresponds to
                phones = get_connected_phones()
                if not phones:
                    print("⚠️ [AURA ADB] No physical Android phone attached via ADB!")
                    return

                target_serial = phones[0] # Default to first phone

                if action == 'LOCK':
                    lock_phone(target_serial)
                elif action == 'UNLOCK':
                    unlock_phone(target_serial, params.get('pin', ''))
                elif action == 'SCREEN_OFF':
                    if is_screen_on(target_serial):
                        run_adb("shell input keyevent 26", target_serial)
                elif action == 'SCREEN_ON':
                    run_adb("shell input keyevent 224", target_serial)
                elif action == 'CALL':
                    num = params.get('contact', '9876543210')
                    make_phone_call(target_serial, num)
                elif action == 'RING_ALARM':
                    ring_alarm(target_serial)

        except Exception as e:
            print(f"Error handling message: {e}")

    def on_error(self, ws, error):
        print(f"⚠️ [AURA ADB] WS Error: {error}")

    def on_close(self, ws, code, msg):
        print("🔌 [AURA ADB] Disconnected. Retrying in 3s...")
        time.sleep(3)
        self.connect()

    def poll_phones_loop(self):
        """Continuously checks attached phones and syncs real battery & status"""
        while self.ws and self.ws.sock and self.ws.sock.connected:
            try:
                phones = get_connected_phones()
                if not phones:
                    # Print once
                    time.sleep(3)
                    continue

                for idx, serial in enumerate(phones):
                    slot_id = f"phone_{idx + 1}"
                    bat, charging = get_phone_battery(serial)
                    screen_state = "ON" if is_screen_on(serial) else "LOCKED"
                    
                    # Send telemetry to server
                    telemetry = {
                        "type": "TELEMETRY",
                        "deviceId": slot_id,
                        "battery": bat,
                        "isCharging": charging,
                        "screen": screen_state
                    }
                    self.ws.send(json.dumps(telemetry))

                time.sleep(4)
            except Exception as err:
                print(f"Error in telemetry loop: {err}")
                time.sleep(3)

if __name__ == "__main__":
    bridge = AuraAdbBridge()
    bridge.connect()
