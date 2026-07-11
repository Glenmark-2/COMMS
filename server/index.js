const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const { AccessToken } = require('livekit-server-sdk');

const app = express();
app.use(express.json());

const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 3000;

const LIVEKIT_API_KEY = process.env.LIVEKIT_API_KEY || 'devkey';
const LIVEKIT_API_SECRET = process.env.LIVEKIT_API_SECRET || 'secret';
const LIVEKIT_URL = process.env.LIVEKIT_URL || 'ws://localhost:7880';

const sessions = new Map();
const clients = new Map();

// Sessions outlive the LiveKit token TTL (6h) by a margin, then get pruned so
// the in-memory maps never grow without bound.
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (now - session.createdAt > SESSION_TTL_MS) {
      sessions.delete(id);
      const ws = clients.get(id);
      if (ws) {
        for (const client of ws) client.close(1000, 'Session expired');
        clients.delete(id);
      }
      console.log(`Pruned expired session ${id}`);
    }
  }
}, 30 * 60 * 1000).unref();

async function createLiveKitToken(roomName, participantName, isHost) {
  const at = new AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET, {
    identity: participantName,
    ttl: '6h',
  });
  at.addGrant({
    roomJoin: true,
    room: roomName,
    canPublish: true,
    canSubscribe: true,
    roomAdmin: isHost,
  });
  return await at.toJwt();
}

// Health check for Render
app.get('/', (req, res) => {
  res.json({ status: 'ok', sessions: sessions.size });
});

app.post('/api/session/create', async (req, res) => {
  const { name, riderName } = req.body;
  if (!name || !riderName) {
    return res.status(400).json({ error: 'Missing session name or rider name' });
  }

  const sessionId = Math.random().toString(36).substring(2, 8).toUpperCase();

  try {
    const token = await createLiveKitToken(sessionId, riderName, true);

    const session = {
      id: sessionId,
      name: name,
      leader: riderName,
      destination: null,
      gpxPathJson: null,
      createdAt: Date.now(),
      riders: [{ name: riderName, isLeader: true }]
    };

    sessions.set(sessionId, session);
    clients.set(sessionId, new Set());

    // Build WebSocket URL dynamically based on request host
    const protocol = req.get('x-forwarded-proto') === 'https' ? 'wss' : 'ws';
    const host = req.get('host');

    console.log(`Created session ${sessionId}: "${name}" by host "${riderName}"`);

    res.json({
      sessionId: sessionId,
      name: name,
      token: token,
      liveKitUrl: LIVEKIT_URL,
      websocketUrl: `${protocol}://${host}/ws/${sessionId}/${riderName}`
    });
  } catch (e) {
    console.error('Failed to create session:', e);
    res.status(500).json({ error: 'Internal server error' });
  }
});

app.post('/api/session/join', async (req, res) => {
  const { sessionId, riderName } = req.body;
  if (!sessionId || !riderName) {
    return res.status(400).json({ error: 'Missing session ID or rider name' });
  }

  const session = sessions.get(sessionId.toUpperCase());
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  // Rejoining under the same name (app restart, network drop) must not add a
  // duplicate rider or count against the limit.
  const existing = session.riders.find(r => r.name === riderName);
  if (!existing && session.riders.length >= 3) {
    return res.status(400).json({ error: 'Session is full (max 3 riders)' });
  }

  try {
    if (!existing) {
      session.riders.push({ name: riderName, isLeader: false });
    }
    const token = await createLiveKitToken(session.id, riderName, false);

    const protocol = req.get('x-forwarded-proto') === 'https' ? 'wss' : 'ws';
    const host = req.get('host');

    console.log(`Rider "${riderName}" joined session ${session.id}`);

    res.json({
      sessionId: session.id,
      name: session.name,
      token: token,
      liveKitUrl: LIVEKIT_URL,
      websocketUrl: `${protocol}://${host}/ws/${session.id}/${riderName}`
    });
  } catch (e) {
    console.error('Failed to join session:', e);
    res.status(500).json({ error: 'Internal server error' });
  }
});

app.post('/api/session/:sessionId/destination', (req, res) => {
  const { sessionId } = req.params;
  const { destinationName, latitude, longitude, gpxPathJson } = req.body;

  const session = sessions.get(sessionId.toUpperCase());
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  session.destination = { name: destinationName, latitude, longitude };
  if (gpxPathJson) {
    session.gpxPathJson = gpxPathJson;
  }

  broadcastToSession(session.id, {
    type: 'ROUTE_UPDATE',
    destination: session.destination,
    gpxPathJson: session.gpxPathJson
  });

  console.log(`Session ${session.id} updated destination to "${destinationName}"`);
  res.sendStatus(200);
});

wss.on('connection', (ws, req) => {
  const parts = req.url.split('/');
  const sessionId = parts[2]?.toUpperCase();
  const riderName = parts[3];

  if (!sessionId || !sessions.has(sessionId)) {
    ws.close(1008, 'Session not found');
    return;
  }

  ws.sessionId = sessionId;
  ws.riderName = riderName;

  clients.get(sessionId).add(ws);
  console.log(`WebSocket Connected: Rider "${riderName}" in session ${sessionId}`);

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message);
      if (data.type === 'TELEMETRY' || data.type === 'SOS') {
        data.sender = riderName;
        broadcastToSession(sessionId, data, ws);
      }
    } catch (e) {
      console.error('Failed to parse WebSocket message', e);
    }
  });

  ws.on('close', () => {
    const sessionClients = clients.get(sessionId);
    if (sessionClients) {
      sessionClients.delete(ws);
    }
    console.log(`WebSocket Disconnected: Rider "${riderName}" from session ${sessionId}`);
  });
});

function broadcastToSession(sessionId, message, excludeWs = null) {
  const sessionClients = clients.get(sessionId);
  if (!sessionClients) return;

  const rawMessage = JSON.stringify(message);
  for (const client of sessionClients) {
    if (client !== excludeWs && client.readyState === WebSocket.OPEN) {
      client.send(rawMessage);
    }
  }
}

server.listen(PORT, () => {
  console.log(`Ride Companion Signaling Server running on port ${PORT}`);
});
