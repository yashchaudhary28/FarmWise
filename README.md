# FarmWise

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](Android/)

An Android app for farmers that runs AI models directly on your phone. No internet required once you've downloaded the models.

We built this because reliable internet isn't always available in rural areas, but farmers still need access to modern tools. Everything runs locally on your device, so your farm data stays private.

## What it does

**On-device AI processing**
- All processing happens on your phone
- Works offline in remote locations
- Your data never leaves your device

**Farming tools**
- Take photos of crops to check for health issues
- Chat with AI about farming questions
- Generate reports and documentation
- Try different AI models to see what works best

**Performance monitoring**
- See how fast the AI is running
- Compare different models
- Load your own specialized models if you have them

## Getting started

**Requirements**
- Android 12 or newer
- About 4GB of storage space for the AI models
- Camera permission for taking crop photos

**Installation**
1. Download the APK from releases
2. Allow installation from unknown sources in Android settings
3. Install and open the app
4. Download the AI models you want to use

## How it works

**Built with**
- Google AI Edge for running models on-device
- LiteRT for fast model execution
- Jetpack Compose for the UI
- Kotlin
- Hilt for dependency injection

**Main parts**
- Model manager for downloading and switching between AI models
- Image analysis for processing crop photos
- Chat interface for asking farming questions
- Tools for generating reports and documentation

## Project structure

```
FarmWise/
├── Android/                 # Android app code
│   └── src/
│       └── app/
│           ├── src/main/java/com/google/ai/edge/gallery/
│           │   ├── ui/              # UI components
│           │   ├── data/            # Data models and repositories
│           │   ├── di/              # Dependency injection
│           │   └── worker/          # Background tasks
│           └── build.gradle.kts     # Build config
├── model_allowlist.json     # List of supported AI models
└── README.md               # This file
```

## Development

**Building from source**
1. Clone this repo
2. Open the `Android` directory in Android Studio
3. Sync with Gradle
4. Build and run on your device

**Dependencies**
- Android SDK 35
- Kotlin 1.9+
- Jetpack Compose
- Google AI Edge APIs
- Hilt

## What you can do with it

- Take photos of your crops and get health assessments
- Ask farming questions and get AI advice
- Generate reports and documentation
- Make decisions without needing internet access

## Contributing

Contributions welcome! Check out [CONTRIBUTING.md](CONTRIBUTING.md) first.

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

## Issues

Found a bug? Check the [Bug Reporting Guide](Bug_Reporting_Guide.md) or open an issue.

## Support

Questions? Open an issue and we'll help out.