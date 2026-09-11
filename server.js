const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');
const os = require('os');
const cors = require('cors');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Real-time Connected devices registry (Only active real devices)
const devices = {};

// Connected WebSocket clients mapping: ws -> { deviceId, type }
const clients = new Map();

// Broadcast device state update to all UI clients
function broadcastDevices() {
  const payload = JSON.stringify({
    type: 'DEVICES_UPDATE',
    devices: devices,
    timestamp: Date.now()
  });

  wss.clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  });
}

// Broadcast general event (e.g. Call Alert, Notification, Speech response)
function broadcastEvent(eventType, data) {
  const payload = JSON.stringify({
    type: eventType,
    data: data,
    timestamp: Date.now()
  });

  wss.clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  });
}

// Smart Natural Language & Voice Intent Engine
function parseVoiceCommand(text) {
  const q = text.toLowerCase().trim();
  let target = 'all';
  let action = null;
  let voiceReply = '';
  let meta = {};

  // Detect target device
  if (q.includes('phone 1') || q.includes('phone1') || q.includes('pehla phone') || q.includes('first phone')) {
    target = 'phone_1';
  } else if (q.includes('phone 2') || q.includes('phone2') || q.includes('dusra phone') || q.includes('second phone')) {
    target = 'phone_2';
  } else if (q.includes('laptop')) {
    target = 'laptop';
  } else if (q.includes('pc') || q.includes('computer') || q.includes('desktop')) {
    target = 'pc';
  }

  // 1. CALL INTENTS
  if (q.includes('call') || q.includes('phone lagao') || q.includes('dial')) {
    action = 'CALL';
    // Match name or number
    let match = q.match(/(?:call|dial|phone lagao to|ko call lagao|lagao)\s+([a-zA-Z0-9\s]+?)(?:\s+from|\s+on|\s+se|$)/i);
    let contact = match && match[1] ? match[1].replace(/phone|laptop|pc|1|2/gi, '').trim() : 'Emergency Contact';
    if (!contact) contact = 'Contact';
    if (target === 'all') target = 'phone_1'; // default phone for calls
    
    meta.contact = contact;
    voiceReply = `Calling ${contact} from ${devices[target]?.name || 'Phone 1'}, Sir.`;
  }
  // 2. LOCK INTENTS
  else if (q.includes('lock') || q.includes('band kar') || q.includes('screen lock')) {
    action = 'LOCK';
    if (target === 'all') target = 'pc';
    voiceReply = `Locking ${devices[target]?.name || 'device'} immediately, Sir.`;
  }
  // 3. UNLOCK INTENTS
  else if (q.includes('unlock') || q.includes('kholo') || q.includes('open screen')) {
    action = 'UNLOCK';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Unlocking screen on ${devices[target]?.name || 'device'}, Sir.`;
  }
  // 4. SCREEN ON / OFF
  else if (q.includes('screen off') || q.includes('display off') || q.includes('screen band')) {
    action = 'SCREEN_OFF';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Turning screen display off on ${devices[target]?.name || 'device'}.`;
  } else if (q.includes('screen on') || q.includes('wake') || q.includes('jaga') || q.includes('chalu')) {
    action = 'SCREEN_ON';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Screen awakened on ${devices[target]?.name || 'device'}.`;
  }
  // 5. SLEEP / HIBERNATE PC
  else if (q.includes('sleep') || q.includes('soja') || q.includes('hibernate')) {
    action = 'SLEEP';
    target = target === 'all' ? 'pc' : target;
    voiceReply = `Putting ${devices[target]?.name || 'PC'} into sleep mode.`;
  }
  // 6. RING / FIND PHONE
  else if (q.includes('ring') || q.includes('find') || q.includes('dhundo') || q.includes('bajao') || q.includes('alarm')) {
    action = 'RING_ALARM';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Ringing loud alarm on ${devices[target]?.name || 'device'} to help you find it.`;
  }
  // 7. TORCH / FLASHLIGHT INTENTS
  else if (q.includes('torch on') || q.includes('flashlight on') || q.includes('torch chalu') || q.includes('flash on')) {
    action = 'TORCH_ON';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Turning on flashlight on ${devices[target]?.name || 'phone'}.`;
  } else if (q.includes('torch off') || q.includes('flashlight off') || q.includes('torch band') || q.includes('flash off')) {
    action = 'TORCH_OFF';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Turning off flashlight on ${devices[target]?.name || 'phone'}.`;
  }
  // 8. SPEAK / TTS THROUGH PHONE
  else if (q.includes('bolo') || q.includes('speak') || q.includes('say')) {
    action = 'SPEAK';
    if (target === 'all') target = 'phone_1';
    let textToSpeak = q.replace(/(?:bolo|speak|say|on phone|phone 1|phone 2|phone par)/gi, '').trim();
    if (!textToSpeak) textToSpeak = "Hello from Aura Central Command.";
    meta.text = textToSpeak;
    voiceReply = `Speaking on ${devices[target]?.name || 'phone'}: "${textToSpeak}"`;
  }
  // 9. SCREENSHOT INTENT
  else if (q.includes('screenshot') || q.includes('screen shot')) {
    action = 'SCREENSHOT';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Capturing screenshot on ${devices[target]?.name || 'device'}.`;
  }
  // 10. NAVIGATION (HOME / BACK / NOTIFICATIONS)
  else if (q.includes('home')) {
    action = 'HOME';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Going to Home screen on ${devices[target]?.name || 'phone'}.`;
  } else if (q.includes('back')) {
    action = 'BACK';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Pressing back on ${devices[target]?.name || 'phone'}.`;
  }
  // 11. GPS LOCATION INTENT
  else if (q.includes('location') || q.includes('kahan hai') || q.includes('where is') || q.includes('locate') || q.includes('dhundo')) {
    action = 'GET_LOCATION';
    if (target === 'all') target = 'phone_1';
    voiceReply = `Locating ${devices[target]?.name || 'device'} now, Sir. Tracking coordinates on your dashboard.`;
  }
  // 12. BATTERY STATUS
  else if (q.includes('battery') || q.includes('charge') || q.includes('kitni charge')) {
    action = 'BATTERY_CHECK';
    let batDetails = Object.values(devices).map(d => `${d.name}: ${d.battery}%`).join(', ');
    voiceReply = batDetails ? `Sir, device battery levels are: ${batDetails}.` : `Sir, no devices currently connected.`;
  }
  // 13. DEFAULT FALLBACK
  else {
    action = 'GENERAL_QUERY';
    voiceReply = `Understood, Sir. Analyzing command: "${text}". All systems are online and operational.`;
  }

  return { target, action, voiceReply, meta, originalQuery: text };
}

// WebSocket Connection handling
wss.on('connection', (ws, req) => {
  console.log('[Aura Hub] New client connected');

  // Send initial device status
  ws.send(JSON.stringify({
    type: 'DEVICES_UPDATE',
    devices: devices,
    timestamp: Date.now()
  }));

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());
      console.log('[Aura Hub] Received:', data.type, data);

      switch (data.type) {
        // Device Registration (PC Agent, Phone Agent, Dashboard)
        case 'REGISTER':
        case 'mobile': {
          const { deviceId, name, deviceType, type } = data;
          const actualType = deviceType || type || 'mobile';
          clients.set(ws, { deviceId: deviceId || 'phone_1', type: actualType });
          if (deviceId && deviceId !== 'master_ui') {
            if (!devices[deviceId]) {
              devices[deviceId] = {
                id: deviceId,
                name: name || deviceId,
                type: actualType,
                status: 'online',
                battery: 100,
                isCharging: false,
                screen: 'ON',
                callState: null,
                lastSeen: Date.now()
              };
            } else {
              devices[deviceId].status = 'online';
              devices[deviceId].lastSeen = Date.now();
              if (name) devices[deviceId].name = name;
            }
          }
          broadcastDevices();
          break;
        }

        // Device Telemetry (Battery, Screen State, Incoming Calls)
        case 'TELEMETRY': {
          const { deviceId, name, battery, isCharging, screen, callState } = data;
          if (deviceId && deviceId !== 'master_ui') {
            // Always ensure client is mapped for command routing
            clients.set(ws, { deviceId, type: 'mobile' });

            if (!devices[deviceId]) {
              devices[deviceId] = {
                id: deviceId,
                name: name || deviceId,
                type: 'mobile',
                status: 'online',
                battery: battery !== undefined ? battery : 100,
                isCharging: isCharging || false,
                screen: screen || 'ON',
                callState: callState || null,
                lastSeen: Date.now()
              };
            } else {
              if (name) devices[deviceId].name = name;
              if (battery !== undefined) devices[deviceId].battery = battery;
              if (isCharging !== undefined) devices[deviceId].isCharging = isCharging;
              if (screen !== undefined) devices[deviceId].screen = screen;
              if (callState !== undefined) devices[deviceId].callState = callState;
              devices[deviceId].lastSeen = Date.now();
              devices[deviceId].status = 'online';
            }
          }
          broadcastDevices();
          break;
        }

        // Direct Command from Dashboard to specific device
        case 'EXECUTE_COMMAND': {
          const { targetDevice, action, params } = data;
          console.log(`[Aura Hub] Executing action "${action}" on device "${targetDevice}"`);

          // Update local simulated state immediately for UI feedback
          if (devices[targetDevice]) {
            if (action === 'LOCK') devices[targetDevice].screen = 'LOCKED';
            if (action === 'UNLOCK') devices[targetDevice].screen = 'UNLOCKED';
            if (action === 'SCREEN_OFF') devices[targetDevice].screen = 'OFF';
            if (action === 'SCREEN_ON') devices[targetDevice].screen = 'ON';
            if (action === 'CALL') devices[targetDevice].callState = { active: true, caller: params?.contact || 'Dialing...', type: 'outgoing' };
            if (action === 'END_CALL') devices[targetDevice].callState = null;
          }

          // Forward command to the target hardware client (e.g. Python PC Agent / Phone Agent)
          wss.clients.forEach(client => {
            const info = clients.get(client);
            if (info && (info.deviceId === targetDevice || targetDevice === 'all') && client.readyState === WebSocket.OPEN) {
              client.send(JSON.stringify({
                type: 'COMMAND',
                action,
                params,
                timestamp: Date.now()
              }));
            }
          });

          broadcastDevices();

          // Send confirmation back
          broadcastEvent('COMMAND_CONFIRMED', {
            targetDevice,
            action,
            message: `Command "${action}" executed on ${devices[targetDevice]?.name || targetDevice}.`
          });
          break;
        }

        // Voice Command from Siri / Jarvis UI
        case 'VOICE_COMMAND': {
          const intent = parseVoiceCommand(data.query);
          console.log('[Aura Hub] Voice Intent Parsed:', intent);

          // Execute action if not just battery check
          if (intent.action && intent.target && intent.action !== 'BATTERY_CHECK' && intent.action !== 'GENERAL_QUERY') {
            // Forward to target device
            wss.clients.forEach(client => {
              const info = clients.get(client);
              if (info && (info.deviceId === intent.target || intent.target === 'all') && client.readyState === WebSocket.OPEN) {
                client.send(JSON.stringify({
                  type: 'COMMAND',
                  action: intent.action,
                  params: intent.meta,
                  timestamp: Date.now()
                }));
              }
            });

            // Update device simulated state
            if (devices[intent.target]) {
              if (intent.action === 'LOCK') devices[intent.target].screen = 'LOCKED';
              if (intent.action === 'UNLOCK') devices[intent.target].screen = 'UNLOCKED';
              if (intent.action === 'SCREEN_OFF') devices[intent.target].screen = 'OFF';
              if (intent.action === 'SCREEN_ON') devices[intent.target].screen = 'ON';
              if (intent.action === 'CALL') devices[intent.target].callState = { active: true, caller: intent.meta?.contact || 'Dialing...', type: 'outgoing' };
            }
            broadcastDevices();
          }

          // Reply with Voice Feedback & UI Update
          broadcastEvent('VOICE_RESPONSE', {
            intent,
            voiceReply: intent.voiceReply,
            query: data.query
          });
          break;
        }

        // Incoming Call Simulation / Real Trigger
        case 'TRIGGER_INCOMING_CALL': {
          const { targetDevice, caller, number } = data;
          if (devices[targetDevice]) {
            devices[targetDevice].callState = { active: true, caller: caller || 'Unknown Caller', number: number || '+91 98765 43210', type: 'incoming' };
          }
          broadcastDevices();
          broadcastEvent('INCOMING_CALL_ALERT', {
            device: devices[targetDevice] || { name: targetDevice },
            caller: caller || 'Unknown',
            number: number || '+91 98765 43210',
            voiceAlert: `Sir, incoming call on ${devices[targetDevice]?.name || 'Phone'} from ${caller || 'someone'}.`
          });
          break;
        }

        // Phone Notification Event (WhatsApp, SMS, Telegram, Instagram, Gmail, etc.)
        case 'PHONE_NOTIFICATION': {
          const { deviceId, appName, packageName, title, text, timestamp } = data;
          console.log(`[Aura Hub] 📩 Notification from ${deviceId} [${appName}]: ${title} -> ${text}`);
          
          broadcastEvent('PHONE_NOTIFICATION_ALERT', {
            deviceId,
            deviceName: devices[deviceId]?.name || deviceId,
            appName: appName || 'App',
            packageName: packageName || '',
            title: title || 'New Notification',
            text: text || '',
            timestamp: timestamp || Date.now(),
            voiceAlert: `Sir, new notification from ${appName}: ${title}, ${text}`
          });
          break;
        }

        // Device GPS Location Telemetry
        case 'DEVICE_LOCATION': {
          const { deviceId, name, latitude, longitude, accuracy, timestamp } = data;
          console.log(`[Aura Hub] 📍 GPS Location from ${deviceId}: ${latitude}, ${longitude} (±${accuracy}m)`);
          
          if (devices[deviceId]) {
            devices[deviceId].location = {
              latitude,
              longitude,
              accuracy: accuracy ? Math.round(accuracy) : 10,
              timestamp: timestamp || Date.now(),
              mapsUrl: `https://www.google.com/maps?q=${latitude},${longitude}`
            };
          }
          
          broadcastDevices();
          broadcastEvent('DEVICE_LOCATION_UPDATE', {
            deviceId,
            name: name || devices[deviceId]?.name || deviceId,
            latitude,
            longitude,
            accuracy: accuracy ? Math.round(accuracy) : 10,
            timestamp: timestamp || Date.now(),
            mapsUrl: `https://www.google.com/maps?q=${latitude},${longitude}`,
            voiceAlert: `Sir, device located at coordinates latitude ${latitude.toFixed(4)}, longitude ${longitude.toFixed(4)}.`
          });
          break;
        }

        default:
          break;
      }
    } catch (err) {
      console.error('[Aura Hub] Error parsing message:', err);
    }
  });

  ws.on('close', () => {
    const info = clients.get(ws);
    if (info && info.deviceId && info.deviceId !== 'master_ui') {
      console.log(`[Aura Hub] Device disconnected / switched off: ${info.deviceId}`);
      if (devices[info.deviceId]) {
        devices[info.deviceId].status = 'offline';
        devices[info.deviceId].lastSeen = Date.now();
      }
    }
    clients.delete(ws);
    broadcastDevices();
  });
});

// REST API endpoint to get local network IP
function getLocalIP() {
  const interfaces = os.networkInterfaces();
  // First check Wi-Fi / Wireless adapter
  for (let devName in interfaces) {
    if (devName.toLowerCase().includes('wi-fi') || devName.toLowerCase().includes('wlan') || devName.toLowerCase().includes('wireless')) {
      for (let alias of interfaces[devName]) {
        if (alias.family === 'IPv4' && !alias.internal) {
          return alias.address;
        }
      }
    }
  }
  // Fallback to any non-virtual IPv4
  for (let devName in interfaces) {
    if (!devName.toLowerCase().includes('virtual') && !devName.toLowerCase().includes('ethernet 78')) {
      for (let alias of interfaces[devName]) {
        if (alias.family === 'IPv4' && alias.address !== '127.0.0.1' && !alias.internal) {
          return alias.address;
        }
      }
    }
  }
  return '192.168.31.33';
}

app.get('/api/info', (req, res) => {
  res.json({
    name: 'Aura Central Hub',
    version: '1.0.0',
    localIP: getLocalIP(),
    port: PORT,
    devices: devices
  });
});

server.listen(PORT, () => {
  const localIP = getLocalIP();
  console.log(`\n======================================================`);
  console.log(`🚀 [Aura Master Hub] Server is LIVE & Running!`);
  console.log(`💻 Local Access:        http://localhost:${PORT}`);
  console.log(`📱 Mobile/Phone Access: http://${localIP}:${PORT}`);
  console.log(`======================================================\n`);
});
