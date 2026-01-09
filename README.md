# Quest Foreground Camera Streaming (Experimental)

This project is an **experimental Android application for Meta Quest devices**
that demonstrates how to access passthrough cameras from a **Foreground Service**
and stream the camera images to a PC over a wired connection.

The main purpose of this project is to **simulate camera access while Quest Link is active**,
where direct camera access is normally restricted or unavailable.

---

## Motivation

On Meta Quest devices, camera access has several restrictions:

- Passthrough Camera API is **not supported over Quest Link**
- Camera access is tightly coupled to app lifecycle and UI visibility
- Background usage is limited by system policies

This project explores the following idea:

> Even while Quest Link is active, a Foreground Service–based Android app running on the headset
> can access passthrough cameras and stream the video to a PC via USB using `adb forward`.

This allows **pseudo camera access during Quest Link sessions**, which can be useful for:
- Computer vision research
- Tooling and debugging
- Experimental mixed reality workflows

This is **not an official or supported workflow**.

---

## Features

- Foreground Service–based camera access
- Access to **left and right passthrough RGB cameras**
- H.264 encoding using `MediaCodec`
- Separate TCP streaming servers per camera
- Wired streaming to PC using `adb forward`
- No UI required after service startup

---

## Architecture Overview

```

Quest Headset (Android)
┌─────────────────────────────┐
│ Foreground Service          │
│  - Camera2 (Passthrough)    │
│  - MediaCodec (H.264)       │
│  - TCP Server (localhost)   │
└─────────────┬───────────────┘
│ USB (adb forward)
▼
PC
┌─────────────────────────────┐
│ ffplay / ffmpeg             │
│ tcp://127.0.0.1:PORT        │
└─────────────────────────────┘

````

---

## Usage

### 1. Build and install
Build and install the app on a **Meta Quest 3 / 3S** device (Horizon OS v74+).

### 2. Start the service
Launch the app once to start the Foreground Service.
After that, the UI can be closed.

### 3. Forward ports to PC
```bash
adb forward tcp:18081 tcp:8081   # left camera
adb forward tcp:18082 tcp:8082   # right camera
````

### 4. View streams on PC

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop \
  -analyzeduration 2000000 -probesize 2000000 \
  -f h264 tcp://127.0.0.1:18081
```

(Use `18082` for the right camera.)

---

## Important Notes

* This project uses **passthrough cameras**, not MediaProjection.
* Actual behavior may change depending on Horizon OS version.

---

## Limitations

* Not officially supported by Meta
* Camera access may stop due to system policies
* Performance and stability are not guaranteed

---

## License

This project is licensed under the **MIT License**.

See the `LICENSE` file for details.