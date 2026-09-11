// AURA MASTER CONTROLLER - CLIENT LOGIC
let socket = null;
let recognition = null;
let isListening = false;
let synth = window.speechSynthesis;

// DOM Elements
const micButton = document.getElementById('micButton');
const voiceStatusLabel = document.getElementById('voiceStatusLabel');
const userSpeechTranscript = document.getElementById('userSpeechTranscript');
const userTextDisplay = document.getElementById('userTextDisplay');
const assistantTextDisplay = document.getElementById('assistantTextDisplay');
const serverIpAddress = document.getElementById('serverIpAddress');
const eventLogContainer = document.getElementById('eventLogContainer');

// Modal Elements
const incomingCallModal = document.getElementById('incomingCallModal');
const modalCallerName = document.getElementById('modalCallerName');
const modalCallerNumber = document.getElementById('modalCallerNumber');
const modalTargetDevice = document.getElementById('modalTargetDevice');

// --- 1. WEBSOCKET CONNECTION & HUB SYNC ---
function initWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}`;

  socket = new WebSocket(wsUrl);

  socket.onopen = () => {
    logActivity('sys', 'Connected to Aura Central WebSocket Core.');
    serverIpAddress.textContent = `${wsUrl}`;
    
    // Register as Master UI
    socket.send(JSON.stringify({
      type: 'REGISTER',
      deviceId: 'master_ui',
      name: 'Master Command Dashboard',
      type: 'controller'
    }));
  };

  socket.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);

      switch (msg.type) {
        case 'DEVICES_UPDATE':
          updateDevicesUI(msg.devices);
          break;

        case 'VOICE_RESPONSE':
          handleVoiceResponse(msg);
          break;

        case 'INCOMING_CALL_ALERT':
          handleIncomingCallAlert(msg.data);
          break;

        case 'PHONE_NOTIFICATION_ALERT':
          handlePhoneNotification(msg.data);
          break;

        case 'DEVICE_LOCATION_UPDATE':
          handleDeviceLocation(msg.data);
          break;

        case 'COMMAND_CONFIRMED':
          logActivity('cmd', msg.data.message);
          break;

        default:
          break;
      }
    } catch (err) {
      console.error('Error handling WebSocket message:', err);
    }
  };

  socket.onclose = () => {
    logActivity('sys', 'Connection lost. Reconnecting in 3s...');
    setTimeout(initWebSocket, 3000);
  };
}

// Update Device UI Cards (Dynamically renders only real connected devices)
function updateDevicesUI(devices) {
  const grid = document.getElementById('dynamicDeviceGrid');
  const countSpan = document.getElementById('fleetCount');
  
  const devEntries = Object.entries(devices);
  const liveCount = devEntries.length;

  if (countSpan) {
    countSpan.textContent = `(${liveCount} Live Device${liveCount === 1 ? '' : 's'})`;
  }

  // If no devices connected, show empty state
  if (liveCount === 0) {
    grid.innerHTML = `
      <div class="no-devices-box">
        <div class="empty-icon">📡</div>
        <h3>No Devices Connected Yet</h3>
        <p>Open <code>https://83ba789fe18674.lhr.life/phone.html</code> on your phone to pair it wirelessly!</p>
      </div>
    `;
    return;
  }

  // Render each connected device
  let html = '';
  for (const [id, dev] of devEntries) {
    const isMobile = dev.type === 'mobile' || id.startsWith('phone');
    const iconSvg = isMobile
      ? `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>`
      : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`;

    const batDisplay = dev.isCharging 
      ? `<span style="color:#f59e0b; font-weight:800;">⚡ ${dev.battery}% (CHARGING)</span>`
      : `<span style="color:${dev.battery <= 20 ? '#f43f5e' : '#10b981'}; font-weight:700;">${dev.battery}%</span>`;

    const actionsHtml = isMobile 
      ? `
        <button class="cmd-btn btn-primary" onclick="triggerCommand('${id}', 'LOCK')">🔒 Lock</button>
        <button class="cmd-btn btn-secondary" onclick="triggerCommand('${id}', 'UNLOCK')">🔓 Wake / Unlock</button>
        <button class="cmd-btn btn-accent" onclick="triggerCommand('${id}', 'GET_LOCATION')">📍 Locate GPS</button>
        <button class="cmd-btn btn-danger" onclick="triggerCommand('${id}', 'RING_ALARM')">🚨 Find Phone</button>
        <button class="cmd-btn btn-accent" onclick="promptMakeCall('${id}')">📞 Dial Call</button>
        <button class="cmd-btn btn-warning" onclick="promptSpeakText('${id}')">🗣️ Speak Text</button>
        <button class="cmd-btn btn-secondary" onclick="triggerCommand('${id}', 'TORCH_ON')">🔦 Flashlight On</button>
        <button class="cmd-btn btn-secondary" onclick="triggerCommand('${id}', 'TORCH_OFF')">💡 Flashlight Off</button>
        <button class="cmd-btn btn-ghost" onclick="triggerCommand('${id}', 'HOME')">🏠 Home</button>
        <button class="cmd-btn btn-ghost" onclick="triggerCommand('${id}', 'SCREENSHOT')">📸 Screenshot</button>
      `
      : `
        <button class="cmd-btn btn-primary" onclick="triggerCommand('${id}', 'LOCK')">🔒 Lock Screen</button>
        <button class="cmd-btn btn-secondary" onclick="triggerCommand('${id}', 'SLEEP')">💤 Sleep</button>
        <button class="cmd-btn btn-danger" onclick="triggerCommand('${id}', 'RING_ALARM')">🔊 Alarm</button>
      `;

    html += `
      <div class="device-card real-card" id="card-${id}" data-device="${id}">
        <div class="device-top">
          <div class="dev-icon ${isMobile ? 'phone-icon' : 'desktop-icon'}">
            ${iconSvg}
          </div>
          <div class="dev-meta">
            <h3>📱 ${dev.name || 'Device'} <span style="font-size:11px; color:#94a3b8;">(${id})</span></h3>
            <span class="status-pill status-real-hardware">🟢 LIVE CONNECTED</span>
          </div>
        </div>

        <div class="telemetry-row">
          <div class="stat-box">
            <span class="stat-label">BATTERY</span>
            <span class="stat-val">${batDisplay}</span>
          </div>
          <div class="stat-box">
            <span class="stat-label">SCREEN</span>
            <span class="stat-val" style="color: ${dev.screen === 'LOCKED' ? '#f59e0b' : '#00f3ff'}; font-weight:700;">${dev.screen || 'ACTIVE'}</span>
          </div>
          <div class="stat-box">
            <span class="stat-label">${isMobile ? 'CALL STATE' : 'STATUS'}</span>
            <span class="stat-val ${dev.callState ? 'text-yellow' : 'text-green'}">${dev.callState ? dev.callState.caller : 'IDLE'}</span>
          </div>
        </div>

        <div class="dev-actions">
          ${actionsHtml}
        </div>
      </div>
    `;
  }

  grid.innerHTML = html;
}

// Send Command to Device
function triggerCommand(targetDevice, action, params = {}) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    alert('Aura Hub is not connected.');
    return;
  }

  const payload = {
    type: 'EXECUTE_COMMAND',
    targetDevice,
    action,
    params
  };

  socket.send(JSON.stringify(payload));
  logActivity('cmd', `Sent action [${action}] to [${targetDevice}]`);
}

// Prompt for Direct Call
function promptMakeCall(targetDevice) {
  const contact = prompt(`Enter contact name or number to dial from ${targetDevice}:`, '9876543210');
  if (contact) {
    triggerCommand(targetDevice, 'CALL', { contact });
  }
}

// Prompt for Speech Text to play on phone
function promptSpeakText(targetDevice) {
  const text = prompt(`Enter message for ${targetDevice} to speak aloud in Jarvis voice:`, 'Alert: Aura command center connected.');
  if (text) {
    triggerCommand(targetDevice, 'SPEAK', { text });
  }
}

// Simulate Incoming Call
function simulateIncomingCall(targetDevice, callerInfo) {
  const caller = callerInfo.split('(')[0].trim();
  const number = (callerInfo.split('(')[1] || '+91 98765 43210').replace(')', '').trim();

  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({
      type: 'TRIGGER_INCOMING_CALL',
      targetDevice,
      caller,
      number
    }));
  }
}

// --- 2. SIRI / JARVIS SPEECH & AI VOICE ENGINE ---
function initSpeechEngine() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

  if (SpeechRecognition) {
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.lang = 'en-US'; // Supports Hinglish/English

    recognition.onstart = () => {
      isListening = true;
      micButton.classList.add('listening');
      voiceStatusLabel.textContent = 'Listening to your command...';
      userTextDisplay.textContent = 'Listening... Speak now.';
    };

    recognition.onresult = (event) => {
      const transcript = event.results[0][0].transcript;
      userTextDisplay.textContent = `"${transcript}"`;
      logActivity('cmd', `Voice Command: "${transcript}"`);
      sendVoiceCommandQuery(transcript);
    };

    recognition.onerror = (event) => {
      console.warn('Speech Recognition Error:', event.error);
      stopListening();
    };

    recognition.onend = () => {
      stopListening();
    };
  } else {
    voiceStatusLabel.textContent = 'Speech API not supported (Use Quick Chips)';
  }
}

function toggleListening() {
  if (!recognition) {
    alert('Voice input is not supported in this browser. Please use Google Chrome or Edge, or click the Quick Command buttons!');
    return;
  }

  if (isListening) {
    recognition.stop();
  } else {
    try {
      recognition.start();
    } catch (e) {
      console.error(e);
    }
  }
}

function stopListening() {
  isListening = false;
  micButton.classList.remove('listening');
  voiceStatusLabel.textContent = 'Voice Assistant Ready';
}

function sendVoiceCommandQuery(queryText) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({
      type: 'VOICE_COMMAND',
      query: queryText
    }));
  }
}

// Handle AI Voice Response & Speech Synthesis (Siri/Jarvis voice)
function handleVoiceResponse(msg) {
  const reply = msg.voiceReply;
  assistantTextDisplay.textContent = `"${reply}"`;
  logActivity('ok', `[AURA AI]: ${reply}`);

  // Speak aloud via TTS
  speakAloud(reply);
}

function speakAloud(text) {
  if (!synth) return;
  
  // Cancel previous speech
  synth.cancel();

  const utterance = new SpeechSynthesisUtterance(text);
  utterance.pitch = 1.05;
  utterance.rate = 1.0;

  // Try to find a clear English/Indian-English voice
  const voices = synth.getVoices();
  const preferredVoice = voices.find(v => v.name.includes('Google') || v.name.includes('Natural') || v.name.includes('Samantha') || v.name.includes('India') || v.lang.startsWith('en'));
  if (preferredVoice) {
    utterance.voice = preferredVoice;
  }

  micButton.classList.add('speaking');
  utterance.onend = () => {
    micButton.classList.remove('speaking');
  };

  synth.speak(utterance);
}

// --- 3. INCOMING CALL MODAL ALERTS ---
function handleIncomingCallAlert(data) {
  modalCallerName.textContent = data.caller || 'Unknown';
  modalCallerNumber.textContent = data.number || '+91 98765 43210';
  modalTargetDevice.textContent = `Ringing on ${data.device.name || 'Android Device'}`;
  incomingCallModal.classList.add('active');

  logActivity('call', `🚨 INCOMING CALL from ${data.caller} on ${data.device.name}`);

  // Speak voice alert
  if (data.voiceAlert) {
    speakAloud(data.voiceAlert);
  }
}

function dismissCallModal() {
  incomingCallModal.classList.remove('active');
  logActivity('sys', 'Call rejected/dismissed.');
}

function acceptCallModal() {
  incomingCallModal.classList.remove('active');
  logActivity('ok', 'Call answered remotely on speakerphone.');
  speakAloud('Call answered on speakerphone, Sir.');
}

// --- 4. PHONE NOTIFICATION & SMS SYNC (WhatsApp, Messages, OTPs) ---
let notificationCount = 0;

function handlePhoneNotification(data) {
  notificationCount++;
  const badge = document.getElementById('notifCountBadge');
  if (badge) badge.textContent = `${notificationCount} Message${notificationCount === 1 ? '' : 's'} Synced`;

  // Log to terminal
  logActivity('ok', `📩 [${data.appName}] ${data.title}: ${data.text}`);

  // Play subtle futuristic chime
  playChimeSound();

  // Show Floating Toast
  showNotificationToast(data);

  // Add to Live Notifications Feed
  const streamList = document.getElementById('notifStreamList');
  if (streamList) {
    const emptyBox = streamList.querySelector('.empty-notifs');
    if (emptyBox) emptyBox.remove();

    const timeStr = new Date(data.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const notifEl = document.createElement('div');
    notifEl.className = 'notif-item';
    
    // Choose icon based on app
    let iconChar = '📩';
    if (data.appName.toLowerCase().includes('whatsapp')) iconChar = '💬';
    else if (data.appName.toLowerCase().includes('message') || data.appName.toLowerCase().includes('sms')) iconChar = '✉️';
    else if (data.appName.toLowerCase().includes('instagram')) iconChar = '📸';
    else if (data.appName.toLowerCase().includes('mail') || data.appName.toLowerCase().includes('gmail')) iconChar = '📧';
    else if (data.appName.toLowerCase().includes('bank') || data.text.toLowerCase().includes('otp')) iconChar = '🔑';

    notifEl.innerHTML = `
      <div class="toast-icon-badge" style="font-size:16px; width:34px; height:34px;">${iconChar}</div>
      <div class="toast-content">
        <div class="toast-header">
          <span class="toast-app-name">${data.appName} <span style="font-size:10px; color:#64748b;">(${data.deviceId || 'phone'})</span></span>
          <span class="toast-time">${timeStr}</span>
        </div>
        <div class="toast-title" style="font-size:13px;">${escapeHtml(data.title)}</div>
        <div class="toast-body" style="font-size:12px;">${escapeHtml(data.text)}</div>
      </div>
    `;

    streamList.insertBefore(notifEl, streamList.firstChild);
  }
}

function showNotificationToast(data) {
  const container = document.getElementById('toastContainer');
  if (!container) return;

  const timeStr = new Date(data.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const toast = document.createElement('div');
  toast.className = 'notif-toast';

  let iconChar = '📩';
  if (data.appName.toLowerCase().includes('whatsapp')) iconChar = '💬';
  else if (data.appName.toLowerCase().includes('message') || data.appName.toLowerCase().includes('sms')) iconChar = '✉️';
  else if (data.appName.toLowerCase().includes('instagram')) iconChar = '📸';
  else if (data.appName.toLowerCase().includes('mail') || data.appName.toLowerCase().includes('gmail')) iconChar = '📧';
  else if (data.appName.toLowerCase().includes('bank') || data.text.toLowerCase().includes('otp')) iconChar = '🔑';

  toast.innerHTML = `
    <div class="toast-icon-badge">${iconChar}</div>
    <div class="toast-content">
      <div class="toast-header">
        <span class="toast-app-name">${data.appName}</span>
        <span class="toast-time">${timeStr}</span>
      </div>
      <div class="toast-title">${escapeHtml(data.title)}</div>
      <div class="toast-body">${escapeHtml(data.text)}</div>
    </div>
  `;

  container.appendChild(toast);

  // Auto-dismiss after 6 seconds
  setTimeout(() => {
    toast.classList.add('fade-out');
    setTimeout(() => toast.remove(), 300);
  }, 6000);
}

function playChimeSound() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(587.33, ctx.currentTime); // D5
    osc.frequency.setValueAtTime(880.00, ctx.currentTime + 0.08); // A5

    gain.gain.setValueAtTime(0.3, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.35);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.4);
  } catch (e) {}
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// --- 5. GPS LOCATION TRACKING & MAP PINPOINT ---
let currentGoogleMapsUrl = '';

function handleDeviceLocation(data) {
  const locModal = document.getElementById('locationModal');
  const locDeviceTitle = document.getElementById('locDeviceTitle');
  const locLatitude = document.getElementById('locLatitude');
  const locLongitude = document.getElementById('locLongitude');
  const locAccuracy = document.getElementById('locAccuracy');
  const mapIframe = document.getElementById('mapIframe');

  if (locDeviceTitle) locDeviceTitle.textContent = `${data.name || 'Device'} (${data.deviceId || 'phone'})`;
  if (locLatitude) locLatitude.textContent = data.latitude.toFixed(6);
  if (locLongitude) locLongitude.textContent = data.longitude.toFixed(6);
  if (locAccuracy) locAccuracy.textContent = `±${data.accuracy || 10} m`;

  currentGoogleMapsUrl = data.mapsUrl || `https://www.google.com/maps?q=${data.latitude},${data.longitude}`;

  // OpenStreetMap embed coordinates
  const lat = data.latitude;
  const lng = data.longitude;
  const delta = 0.004;
  const embedUrl = `https://www.openstreetmap.org/export/embed.html?bbox=${lng - delta}%2C${lat - delta}%2C${lng + delta}%2C${lat + delta}&layer=mapnik&marker=${lat}%2C${lng}`;
  
  if (mapIframe) mapIframe.src = embedUrl;

  if (locModal) locModal.classList.add('active');

  logActivity('ok', `📍 [GPS] ${data.name || data.deviceId} at Lat: ${data.latitude.toFixed(5)}, Lng: ${data.longitude.toFixed(5)}`);

  // Voice announcement if requested
  if (data.voiceAlert) {
    speakAloud(data.voiceAlert);
  }
}

function closeLocationModal() {
  const locModal = document.getElementById('locationModal');
  if (locModal) locModal.classList.remove('active');
}

function openInGoogleMaps() {
  if (currentGoogleMapsUrl) {
    window.open(currentGoogleMapsUrl, '_blank');
  }
}

// --- 6. TERMINAL LOG ACTIVITY ---
function logActivity(type, message) {
  const entry = document.createElement('div');
  const timeStr = new Date().toLocaleTimeString();
  entry.className = `log-entry ${type}`;
  entry.textContent = `[${timeStr}] ${message}`;
  
  eventLogContainer.appendChild(entry);
  eventLogContainer.scrollTop = eventLogContainer.scrollHeight;
}

function clearLogs() {
  eventLogContainer.innerHTML = '<div class="log-entry sys">[SYSTEM] Log buffer cleared.</div>';
}

// --- 5. INITIALIZE EVENT LISTENERS ---
window.addEventListener('DOMContentLoaded', () => {
  initWebSocket();
  initSpeechEngine();

  // Mic Button Click
  micButton.addEventListener('click', toggleListening);

  // Quick Voice Chips Click
  document.querySelectorAll('.voice-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const cmd = chip.getAttribute('data-command');
      userTextDisplay.textContent = `"${cmd}"`;
      sendVoiceCommandQuery(cmd);
    });
  });

  // Sync Telemetry Button
  document.getElementById('refreshFleetBtn').addEventListener('click', () => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      logActivity('sys', 'Requesting fleet telemetry sync...');
    }
  });
});
