# Contributing to OpenRower

Thank you for your interest in contributing! Contributions are welcome via issues and pull requests.

## Getting Started

1. Fork the repository and create a branch from `main`.
2. For the **Android app**: open the project in Android Studio (Electric Eel or newer). The app targets SDK 36, minSdk 31.
3. For the **Arduino sketch**: open `OpenRowerArduino/OpenRowerArduino.ino` in the Arduino IDE (1.8+ or 2.x). No external libraries are required.

## How to Contribute

- **Bug reports** — open an issue with steps to reproduce, expected vs. actual behaviour, and device/OS details.
- **Feature requests** — open an issue to discuss the idea before starting work.
- **Pull requests** — keep changes focused. Link the related issue in the PR description.

## Code Style

- Kotlin: follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Arduino (C++): keep the interrupt handler lean; document any timing assumptions.

## Physics Model

If you change the rowing physics (drag factor, stroke detection, pace calculation), please include the reasoning and any reference papers or real-world measurements in the PR description.

## License

By contributing you agree that your contributions will be licensed under the [MIT License](LICENSE).
