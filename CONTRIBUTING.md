# Contributing to Pocket Node

Thank you for your interest in contributing to Pocket Node! This document covers the development setup, coding conventions, and PR process.

## Prerequisites

- **Android Studio** (latest stable)
- **JDK 17**
- **Android SDK** (min SDK 26, target SDK 35, compile SDK 36)
- **Rust toolchain** with Android cross-compilation targets (for JNI library changes):
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```
- **Android NDK** (auto-detected by Gradle or at `~/Library/Android/sdk/ndk/`)

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/RaheemJnr/Light-Client-Gateway.git
   cd Light-Client-Gateway
   ```

2. Open the `android/` directory in Android Studio.

3. Build and run:
   ```bash
   cd android
   ./gradlew assembleDebug -x cargoBuild  # Skip JNI build for Kotlin-only work
   ./gradlew installDebug                  # Install on connected device
   ```

4. Run tests:
   ```bash
   cd android
   ./gradlew test -x cargoBuild
   ```

## Code Style

### Kotlin

- Follow standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `@Serializable` with `@SerialName("snake_case")` for JSON models
- Use `Result<T>` with `runCatching {}` for error handling in repositories
- Use `StateFlow` for observable state in ViewModels
- Prefer `_uiState.update { it.copy(...) }` for state mutations

### Jetpack Compose

- One `@Composable` screen function per file (e.g., `HomeScreen.kt`)
- Use `hiltViewModel()` for ViewModel injection
- Use `collectAsState()` to observe StateFlow
- Material 3 components exclusively (no XML layouts)

### Naming

- ViewModels: `{Screen}ViewModel` with `{Screen}UiState` data class
- Screens: `@Composable fun {Name}Screen()`
- Packages: `com.rjnr.pocketnode.{layer}.{feature}`
- Branches: `feature/{issue-number}-short-description`

## Branch Workflow

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/{issue-number}-short-description
   ```

2. Make your changes and commit:
   ```bash
   git commit -m "feat: brief description of change"
   ```

3. Push and open a PR against `main`:
   ```bash
   git push -u origin feature/{issue-number}-short-description
   ```

### Commit Messages

Use conventional-style prefixes:

- `feat:` — new feature
- `fix:` — bug fix
- `refactor:` — code restructuring without behavior change
- `chore:` — build, CI, dependency updates
- `docs:` — documentation only

## Pull Requests

- Link the related issue in the PR description
- Describe what changed and why
- Ensure CI passes (build + tests)
- Keep PRs focused — one issue per PR when possible

## Security

- Never commit secrets, private keys, or keystore files
- Never log sensitive data (private keys, mnemonics, PINs)
- Release builds strip all `Log.*` calls via ProGuard — but still avoid logging sensitive data in source
- See [SECURITY.md](SECURITY.md) for the vulnerability reporting process

## Questions?

Open an issue or start a discussion on the repository.
