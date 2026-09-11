# AURA // Unified Multi-Device Command & Voice Assistant Hub

Aura is a centralized command system that unifies and controls your PCs, Laptops, and Android Phones from a single dashboard with 2-way Siri/Jarvis-style voice interaction.

---

## 🌟 Key Features

1. **Central Device Fleet Management**:
   - Live telemetry for **PC, Laptop, Android Phone 1, and Android Phone 2**.
   - Battery %, charging state, screen status (Active / Locked / Off), and call state.

2. **Siri / Jarvis Voice AI Engine**:
   - **Voice Input**: Tap the pulsating orb or speak into your microphone.
   - **Intelligent Intent Parsing**: Understands natural commands in English & Hinglish (e.g. *"Lock PC"*, *"Phone 1 se Rohan ko call lagao"*, *"Find Phone 1"*, *"Screen off Phone 2"*).
   - **2-Way Voice Synthesis (TTS)**: Siri/Jarvis vocal feedback confirms every executed action (*"Locking PC immediately, Sir"*).

3. **Remote Control Actions**:
   - **PC / Laptop**: Screen Lock (Windows API), Sleep, Alarm/Beep, Screen On/Off.
   - **Android Phones**: Screen Lock/Unlock, Screen Off, Remote Calling, Find Phone/Loud Alarm, Incoming Call popups.

4. **Incoming Call & Telephony Bridge**:
   - Live incoming call alerts pop up with caller name, number, and voice alerts.
   - Remote call dialer and call answering triggers.

---

## 🚀 How to Run

### Step 1: Start the Master Server Hub
In the terminal, run:
```bash
npm start
```
The server will start at:
- **Local Access:** `http://localhost:3000`
- **Mobile Access:** `http://<YOUR_LOCAL_IP>:3000` (e.g. `http://192.168.1.5:3000`)

---

### Step 2: Start the PC Background Agent (Windows)
In a second terminal, start the Python agent:
```bash
python aura_pc_agent.py
```
*Now, whenever you press **"Lock Screen"** or speak **"Lock PC"** in the dashboard, your PC will physically lock instantly!*

---

### Step 3: Open on Your Mobile Phone
1. Connect your Android phone to the same Wi-Fi as your PC.
2. Open Chrome/Browser on your phone and go to: `http://<YOUR_LOCAL_IP>:3000`.
3. You can control all devices right from your phone's screen or mic!
