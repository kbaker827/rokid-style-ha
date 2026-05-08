# Rokid Style Home Assistant

Voice-controlled Home Assistant bridge for Rokid AI Glasses Style.

This Android/Kotlin port adapts the original Rokid HA iOS companion into a standalone audio-first app. It connects to Home Assistant over WebSocket, listens for spoken commands, runs service calls, and speaks status updates or confirmations.

## Features

- Home Assistant WebSocket auth and state subscription
- Voice command parsing for common smart home actions
- Android TextToSpeech confirmations
- Foreground microphone service
- Minimal launcher and settings screens

## Build

Open the folder in Android Studio and build the `app` module.

## Notes

Configure the Home Assistant URL and long-lived access token in Settings.
