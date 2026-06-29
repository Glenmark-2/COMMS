# RIDE COMPANION — Project Vision

## Purpose
A dedicated ride companion mobile app for Android, designed for long-distance bicycle rides with a small group of friends (3 riders). The architecture prioritizes reliability, simplicity, and an excellent real-world riding experience rather than scalability.

## Priority Order
1. **Reliable voice communication** — the heart of the app
2. **Accurate bicycle navigation**
3. **Live group location**
4. **Offline resilience**
5. **Safety features**
6. **Ride information and statistics**

## User Flow
```
Open App → Create Ride → Friends Join → Ride
```
No phone calls, no ringing, no voice rooms, no manual reconnecting. Voice should feel persistent — like a motorcycle intercom (Cardo/Sena), not Discord.

---

## Core Design Philosophy: Connectivity Adaptive

The app intelligently switches between communication methods depending on conditions. The user never manually selects modes.

| Condition | Behavior |
|-----------|----------|
| Strong Internet | High-quality continuous voice |
| Weak Internet | Lower bitrate voice |
| Very unstable Internet | Adaptive codecs, aggressive reconnection |
| Nearby devices | P2P technologies when beneficial |
| No Internet | Offline features continue, queue sync, resume on reconnect |

### Transport Technologies to Leverage
- WebRTC / LiveKit (primary cloud voice)
- Android Nearby Connections API (P2P fallback)
- Wi-Fi Direct, Bluetooth LE/Classic (future)
- Meshtastic/LoRa (future hardware integration)
- Communication layer must be **modular and transport-agnostic**

---

## Voice Communication

Goals:
- Continuous group intercom (always-on)
- Very low latency
- Automatic reconnection
- Noise suppression & wind reduction
- Echo cancellation
- Automatic gain control
- Adaptive bitrate
- Excellent battery efficiency
- Navigation announcements duck voice volume

Technologies: WebRTC, Opus, RNNoise, VAD, Push-To-Talk fallback

---

## Group Ride / Session

A ride session contains:
- **Leader** (creates ride, sets destination)
- **Members** (join with session code)
- **Destination** (set once by leader, shared to all)
- **Shared route** (everyone gets same navigation)
- **Live locations** (all riders visible on map)
- **Ride statistics** & recording

If the route changes, everyone's navigation updates.

---

## Maps & Navigation

- Based on **OpenStreetMap**
- **Bicycle-specific routing** (bike lanes, elevation, surface type)
- Offline map downloads supported
- Routing engines to consider: GraphHopper, Valhalla, OpenRouteService, OSRM

Features:
- Bike routing with elevation profiles
- Turn-by-turn voice guidance
- Re-routing on deviation
- Route preview before ride
- Waypoint support
- GPX import/export
- Ride history

---

## Live Location

Every rider continuously shares:
- GPS coordinates
- Heading & speed
- Battery level
- Connectivity status
- Accuracy & movement state

Map display should clearly show:
- Who is ahead / behind
- Distance between riders
- Current speed of each rider
- Connection quality per rider
- Last update timestamp
- Last known position when disconnected

---

## Battery Optimization

Adaptive GPS update frequency:
- Stopped → low frequency
- Slow riding → medium
- Fast riding → high
- Screen off → minimal
- Navigation active → route-aware

Battery must be considered in **every** implementation decision.

---

## Safety Features

Architecture must support (even if implemented later):
- Crash detection (accelerometer spike + immobility)
- Long stop detection
- SOS broadcast
- Last known position sharing
- Emergency contact notification
- Ride recovery after crash

---

## Offline-First

Every feature should work without Internet where possible:
- Offline maps
- Ride recording continues
- GPS tracking continues
- Queued data synchronization
- Cached routes & destinations
- Automatic recovery on reconnect

Internet **enhances** the app rather than being **required**.

---

## User Experience

Designed for **cyclists riding**:
- Large touch targets
- Minimal distractions
- High contrast, glanceable
- Easy one-handed operation
- Voice-first interaction
- Map emphasizes current route, not clutter
- Navigation always readable at a glance
- Dark theme optimized for outdoor visibility

---

## Technical Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM, Repository pattern |
| DI | Hilt |
| Database | Room |
| Maps | MapLibre (OpenStreetMap) |
| Voice | LiveKit (WebRTC/Opus) |
| P2P Fallback | Android Nearby Connections |
| Backend | Node.js on Render (free tier) |
| Networking | Retrofit + OkHttp |
| Location | Google Play Services Location |
| Sensors | Accelerometer (crash detection) |

---

## Development Phases

### Phase 1 — MVP (Current)
- [x] Project structure & modules
- [x] Session create/join flow
- [x] Backend deployed (Render)
- [x] LiveKit Cloud integration
- [x] MapLibre initialization
- [ ] Real OpenStreetMap tiles
- [ ] Premium UI/UX overhaul
- [ ] Live rider locations on map
- [ ] Basic bike routing & navigation
- [ ] Voice intercom quality

### Phase 2 — Advanced Ride Features
- [ ] Turn-by-turn voice guidance
- [ ] Offline map downloads
- [ ] GPX import/export
- [ ] Ride recording & history
- [ ] Elevation profiles
- [ ] Re-routing

### Phase 3 — Connectivity Improvements
- [ ] Adaptive bitrate voice
- [ ] P2P fallback (Nearby Connections)
- [ ] Offline queue synchronization
- [ ] Background service optimization

### Phase 4 — Hardware Integration
- [ ] Bluetooth headset optimization
- [ ] Meshtastic/LoRa support
- [ ] Heart rate monitor integration
- [ ] Handlebar mount mode

---

## Key Principle

> Every architectural decision should answer one question:
> **"Does this improve the experience of three friends riding together for long distances?"**
