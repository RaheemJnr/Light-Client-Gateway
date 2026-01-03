# CKB Wallet Gateway

A complete mobile wallet solution for [Nervos CKB](https://www.nervos.org/) blockchain, featuring an Android app with Jetpack Compose and a Rust backend gateway that interfaces with the CKB light client.

![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/kotlin-1.9+-blue)
![Rust](https://img.shields.io/badge/rust-1.70+-orange)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## Features

- **Wallet Management**: Secure key generation and encrypted storage
- **Balance Tracking**: Real-time balance updates with sync progress
- **Send Transactions**: Full transaction lifecycle with status tracking
- **Transaction History**: Detailed transaction views with confirmations
- **QR Code Scanner**: Scan CKB addresses for easy transfers
- **Flexible Sync Modes**: Choose how much blockchain history to sync
- **Testnet Support**: Currently supports CKB testnet (Pudge)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                      │
│                    Jetpack Compose + Hilt                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP/REST
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                 Rust Gateway Server (Axum)                   │
│                    REST API Wrapper                          │
└──────────────────────────┬──────────────────────────────────┘
                           │ JSON-RPC
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    CKB Light Client                          │
│                  (Nervos Network Testnet)                    │
└─────────────────────────────────────────────────────────────┘
```

## Project Structure

```
ckb-wallet-gateway/
├── android/                    # Android app
│   └── app/
│       └── src/main/java/com/example/ckbwallet/
│           ├── data/
│           │   ├── crypto/     # Blake2b hashing
│           │   ├── gateway/    # API client & repository
│           │   ├── transaction/# Transaction builder
│           │   └── wallet/     # Key management
│           ├── di/             # Hilt dependency injection
│           └── ui/
│               └── screens/    # Compose UI screens
├── server/                     # Rust gateway server
│   └── src/
│       ├── main.rs             # Entry point
│       ├── routes.rs           # API endpoints
│       ├── light_client.rs     # CKB light client wrapper
│       └── models.rs           # Data models
├── deployment/                 # Docker & VPS deployment files
├── docs/                       # Documentation
├── scripts/                    # Utility scripts
└── WEEKLY_PROGRESS_LOGS.md     # Development progress
```

## Prerequisites

### For the Gateway Server
- Rust 1.70 or higher
- [CKB Light Client](https://github.com/nervosnetwork/ckb-light-client) running

### For the Android App
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 26+ (target SDK 35)
- Physical device or emulator with camera (for QR scanning)

## Quick Start

### 1. Start the CKB Light Client

```bash
# Clone and build the light client
git clone https://github.com/nervosnetwork/ckb-light-client.git
cd ckb-light-client
cargo build --release -p light-client-bin

# Run with testnet configuration
./target/release/ckb-light-client run --config-file config/testnet.toml
```

The light client will start syncing with the CKB testnet. This may take some time for the initial sync.

### 2. Start the Gateway Server

```bash
cd server

# Create environment file (optional)
echo "LIGHT_CLIENT_URL=http://127.0.0.1:9000" > .env
echo "SERVER_PORT=8080" >> .env

# Run the server
cargo run
```

The gateway server will start at `http://localhost:8080`.

### 3. Run the Android App

```bash
cd android

# Build and run (ensure emulator is running or device is connected)
./gradlew installDebug
```

**Note for Emulators**: The app is configured to connect to `http://10.0.2.2:8080` which routes to your host machine's localhost(recommended).

**Note for Physical Devices**: Update the gateway URL in `app/build.gradle.kts`:
```kotlin
buildConfigField("String", "GATEWAY_URL", "\"http://YOUR_LOCAL_IP:8080\"")
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v1/status` | Gateway health check |
| `POST` | `/v1/accounts/register` | Register address for tracking |
| `GET` | `/v1/accounts/:address/status` | Account sync status |
| `GET` | `/v1/accounts/:address/balance` | Get account balance |
| `GET` | `/v1/accounts/:address/cells` | Get spendable cells (UTXOs) |
| `GET` | `/v1/accounts/:address/transactions` | Transaction history |
| `POST` | `/v1/transactions/send` | Broadcast signed transaction |
| `GET` | `/v1/transactions/:tx_hash/status` | Transaction status & confirmations |

### Example: Register an Account

```bash
# Register with default sync (last 30 days)
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq..."}'

# Register with full history sync
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1q...", "from_block": "genesis"}'
```

## Sync Modes

The wallet supports flexible sync options for different use cases:

| Mode | Description | Use Case |
|------|-------------|----------|
| **New Wallet** | Sync from current tip only | Fresh wallets with no history |
| **Recent** (default) | Last ~30 days | Most users |
| **Full History** | From genesis block | Complete transaction history |
| **Custom** | Specific block height | Advanced users |

## Testing with Testnet CKB

1. Generate a wallet in the app (automatic on first launch)
2. Copy your testnet address from the Receive screen
3. Get testnet CKB from the [Nervos Faucet](https://faucet.nervos.org/)
4. Wait for sync to complete and see your balance update

## Tech Stack

### Android App
- **Language**: Kotlin 1.9+
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with Repository pattern
- **DI**: Hilt
- **Networking**: Ktor Client
- **Crypto**: CKB SDK Java, secp256k1-kmp, BouncyCastle
- **Camera**: CameraX + ML Kit (barcode scanning)
- **Storage**: EncryptedSharedPreferences, Room

### Gateway Server
- **Language**: Rust 2021 Edition
- **Framework**: Axum 0.7
- **Async Runtime**: Tokio
- **HTTP Client**: Reqwest
- **Serialization**: Serde

## Development

### Building the Server

```bash
cd server
cargo build --release
```

### Building the Android App

```bash
cd android
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build (requires signing)
```

### Running Tests

```bash
# Server tests
cd server && cargo test

# Android tests (coming soon)
cd android && ./gradlew test
```

## Configuration

### Server Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LIGHT_CLIENT_URL` | `http://127.0.0.1:9000` | CKB light client RPC URL |
| `SERVER_PORT` | `8080` | Gateway server port |
| `RUST_LOG` | `info` | Log level (debug, info, warn, error) |

### Android Build Config

Located in `android/app/build.gradle.kts`:

```kotlin
// Gateway URL for debug builds (10.0.2.2 = host localhost from emulator)
buildConfigField("String", "GATEWAY_URL", "\"http://10.0.2.2:8080\"")
```

## Deployment

For production deployment to a VPS, see the [Deployment Guide](deployment/README.md).

### Quick Deploy to VPS

```bash
# SSH into your VPS (Ubuntu 22.04 / Debian 12)
ssh root@YOUR_SERVER_IP

# Run the automated setup script
curl -sSL https://raw.githubusercontent.com/RaheemJnr/Light-Client-Gateway/main/deployment/setup-vps.sh -o setup-vps.sh
chmod +x setup-vps.sh
sudo ./setup-vps.sh

# With SSL certificate for a domain
sudo ./setup-vps.sh --domain api.yourdomain.com
```

### Using Docker Compose

```bash
cd deployment
docker compose up -d
```

See [deployment/README.md](deployment/README.md) for detailed instructions.

## Roadmap

- [x] Core wallet functionality
- [x] Transaction sending and receiving
- [x] Transaction status polling
- [x] QR code scanning
- [x] Flexible sync modes
- [x] Transaction history with details
- [ ] Mainnet support
- [ ] Unit tests
- [ ] BIP39 mnemonic backup/recovery
- [ ] Biometric authentication
- [ ] Multiple wallet support

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Security

- Private keys are stored in Android's EncryptedSharedPreferences
- Keys never leave the device
- All transactions are signed locally before being sent to the gateway
- The gateway server only broadcasts pre-signed transactions

**⚠️ Warning**: This is development software. Use at your own risk and only on testnet until fully audited for mainnet use.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Resources

- [Nervos CKB Documentation](https://docs.nervos.org/)
- [CKB Light Client](https://github.com/nervosnetwork/ckb-light-client)
- [CKB SDK Java](https://github.com/nervosnetwork/ckb-sdk-java)
- [Nervos Testnet Faucet](https://faucet.nervos.org/)
- [CKB Explorer (Testnet)](https://pudge.explorer.nervos.org/)

## Acknowledgments

- Neon and Matt
- Nervos Foundation for the CKB SDK and light client
- CKBuilders Cohort
