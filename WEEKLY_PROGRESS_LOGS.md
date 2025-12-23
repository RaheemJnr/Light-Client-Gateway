# Weekly Progress Logs

## CKB Wallet Gateway - Android Development Progress

---

## Week 1: November 25 - December 1, 2025

### Overview
Initial project setup and core cryptographic infrastructure implementation.

### Completed Tasks

#### Project Setup
- [x] Initialized Android project with Kotlin and Jetpack Compose
- [x] Configured Gradle build with necessary dependencies
- [x] Set up Hilt for dependency injection
- [x] Integrated Ktor client for network communication
- [x] Added secp256k1-kmp library for cryptographic operations

#### Core Cryptography Implementation
- [x] Implemented `Blake2b` hasher class with CKB personalization
  - Created `Blake2bHasher` for incremental hashing support
  - Implemented proper initialization with CKB-specific personalization bytes
  - Added `update()` and `finalize()` methods for streaming hash computation
- [x] Integrated secp256k1 library for elliptic curve operations
  - Key generation and verification
  - Public key derivation from private key
  - Message signing capabilities

#### Data Models
- [x] Created CKB-specific data models in `CkbModels.kt`
  - `Script`, `OutPoint`, `CellDep`, `CellInput`, `CellOutput`
  - `Transaction`, `Cell`, `TransactionRecord`
  - `NetworkType` enum for testnet/mainnet support
- [x] Created API models in `ApiModels.kt`
  - Request/Response models for gateway communication
  - `BalanceResponse`, `CellsResponse`, `SendTransactionRequest`

### Challenges & Solutions
- **Challenge**: Understanding CKB's unique cell model and transaction structure
- **Solution**: Studied CKB documentation and implemented models that accurately represent the UTXO-like cell structure

---

## Week 2: December 2 - December 8, 2025

### Overview
Wallet management, key handling, and transaction building implementation.

### Completed Tasks

#### Key Management (`KeyManager.kt`)
- [x] Implemented secure key storage using EncryptedSharedPreferences
- [x] Added wallet generation with secure random private key creation
- [x] Implemented wallet import from private key hex string
- [x] Created public key derivation using `Secp256k1.pubkeyCreate()`
- [x] Implemented lock script derivation with Blake2b hashing

#### Address Utilities (`AddressUtils.kt`)
- [x] Implemented Bech32/Bech32m encoding for CKB addresses
- [x] Added support for both short format (secp256k1-blake160) and full format addresses
- [x] Created address parsing to extract lock scripts
- [x] Added network-aware address encoding (testnet: `ckt`, mainnet: `ckb`)

#### Transaction Builder (`TransactionBuilder.kt`)
- [x] Implemented cell selection algorithm for inputs
- [x] Created transaction output construction with proper capacity handling
- [x] Implemented change output calculation with minimum cell capacity check (61 CKB)
- [x] Added transaction fee handling (default: 100,000 shannons)

#### Gateway Integration
- [x] Created `GatewayApi.kt` with Ktor HTTP client
- [x] Implemented API endpoints:
  - Account registration
  - Balance queries
  - Cell fetching
  - Transaction history
  - Transaction submission

### Challenges & Solutions
- **Challenge**: Proper serialization of witness arguments for signing
- **Solution**: Implemented custom `serializeWitnessArgs()` with correct molecule encoding

---

## Week 3: December 9 - December 17, 2025

### Overview
Transaction signing, bug fixes, and UI integration completion.

### Completed Tasks

#### Transaction Signing Implementation
- [x] Implemented transaction hash computation using Blake2b
- [x] Created witness message construction for signing
- [x] Integrated secp256k1 compact signature generation

#### Critical Bug Fixes

##### Blake2b Usage Fix
- **Issue**: Code was calling `blake2b.update()` and `blake2b.digest()` directly on `Blake2b` class
- **Fix**: Changed to use `Blake2b().newHasher()` to get `Blake2bHasher` instance, and `finalize()` instead of `digest()`

```kotlin
// Before (incorrect)
val blake2b = Blake2b()
blake2b.update(txHash)
val message = blake2b.digest()

// After (correct)
val blake2b = Blake2b().newHasher()
blake2b.update(txHash)
val message = blake2b.finalize()
```

##### Secp256k1 Compact Signature Fix
- **Issue**: `Secp256k1.compact(signature)` method doesn't exist in the library
- **Fix**: Changed to use `Secp256k1.signCompact()` which directly returns 65-byte compact signature

```kotlin
// Before (incorrect)
val signature = Secp256k1.sign(message, privateKey)
val compactSig = Secp256k1.compact(signature)

// After (correct)
val signature = Secp256k1.signCompact(message, privateKey)
val compactSig = signature.copyOfRange(0, 64)
val recoveryId = signature[64]
```

##### Cell Model Property Access Fix
- **Issue**: Code referenced `cell.cellOutput.type` but `Cell` class has properties directly
- **Fix**: Changed to access `cell.type`, `cell.capacity` directly

#### SendViewModel Fixes
- [x] Fixed incomplete `balance.` expression to `balance?.capacityAsLong() ?: 0L`
- [x] Fixed non-existent `keyManager.getAddress()` to use `keyManager.getWalletInfo().testnetAddress`
- [x] Removed incorrect nullable check on `getPrivateKey()`

#### GatewayRepository Enhancements
- [x] Added `sendTransaction()` method for submitting signed transactions
- [x] Updated `getCells()` to accept optional address parameter
- [x] Updated `refreshBalance()` to accept optional address parameter
- [x] Fixed Result type handling in ViewModel calls

### Testing & Validation
- [x] Verified all compilation errors resolved
- [x] Confirmed Android app builds successfully
- [x] Validated transaction building flow end-to-end

---

## Summary

### Key Deliverables
1. **Cryptographic Layer**: Blake2b hashing with CKB personalization, secp256k1 signing
2. **Wallet Management**: Secure key storage, address generation, import/export
3. **Transaction System**: Cell selection, transaction building, signing, submission
4. **Gateway Integration**: Full API client for CKB indexer gateway

### Files Modified/Created
- `data/crypto/Blake2b.kt` - Blake2b hasher implementation
- `data/wallet/KeyManager.kt` - Secure wallet management
- `data/wallet/AddressUtils.kt` - CKB address encoding/decoding
- `data/transaction/TransactionBuilder.kt` - Transaction construction and signing
- `data/gateway/GatewayApi.kt` - HTTP client for gateway
- `data/gateway/GatewayRepository.kt` - Repository pattern for data access
- `data/gateway/models/CkbModels.kt` - CKB data structures
- `data/gateway/models/ApiModels.kt` - API request/response models
- `ui/screens/send/SendViewModel.kt` - Send transaction UI logic

### Next Steps
- [ ] Implement transaction status polling
- [ ] Add QR code scanning for addresses
- [ ] Implement transaction history UI
- [ ] Add mainnet support toggle
- [ ] Write unit tests for cryptographic functions
- [ ] Add error handling improvements and user feedback

---

## Week 4: December 18, 2025

### Overview
Integrated official CKB SDK Java library to replace custom cryptographic implementations, ensuring address generation compatibility with CKB faucet and ecosystem.

### Completed Tasks

#### CKB SDK Integration
- [x] Added official `ckb-sdk-java` v4.0.0 dependencies to project
  - `org.nervos.ckb:core:4.0.0`
  - `org.nervos.ckb:utils:4.0.0`
  - `org.bouncycastle:bcprov-jdk15on:1.70`
- [x] Resolved Guava dependency conflict with Android's ListenableFuture
  - Added capability resolution strategy in `build.gradle.kts`

#### Blake2b Replacement
- [x] Replaced custom 144-line Blake2b implementation with SDK wrapper
- [x] Now uses BouncyCastle's `Blake2bDigest` with proper CKB personalization
- [x] Maintains same API for backwards compatibility

```kotlin
// New implementation using official SDK
class Blake2b @Inject constructor() {
    fun hash(input: ByteArray): ByteArray {
        return CkbBlake2b.digest(input)
    }
    fun newHasher(): Blake2bHasher = Blake2bHasher()
}
```

#### Address Utils Replacement
- [x] Replaced custom Bech32/Bech32m implementation with SDK's `Address` class
- [x] Proper encoding for both short format (secp256k1-blake160) and full format
- [x] Network-aware address generation (testnet: `ckt`, mainnet: `ckb`)

```kotlin
// Now using official SDK Address class
fun encode(script: Script, network: NetworkType): String {
    val ckbScript = org.nervos.ckb.type.Script(codeHashBytes, argsBytes, hashType)
    val address = Address(ckbScript, ckbNetwork)
    return address.encode()
}
```

#### Key Management Updates
- [x] Updated to use CKB SDK's `ECKeyPair` for key operations
- [x] Integrated `Sign.signMessage()` for proper recoverable signatures
- [x] Uses `SignatureData.getSignature()` for 65-byte signature format

#### Transaction Builder Updates
- [x] Replaced signing with CKB SDK's `Sign` class
- [x] Proper transaction hash computation using official Blake2b
- [x] Correct witness serialization for CKB transactions

### Dependencies Added

```kotlin
// gradle/libs.versions.toml
ckbSdk = "4.0.0"
bouncycastle = "1.70"

// Libraries
ckb-sdk-core = { group = "org.nervos.ckb", name = "core", version.ref = "ckbSdk" }
ckb-sdk-utils = { group = "org.nervos.ckb", name = "utils", version.ref = "ckbSdk" }
bouncycastle = { group = "org.bouncycastle", name = "bcprov-jdk15on", version.ref = "bouncycastle" }
```

### Files Modified
- `gradle/libs.versions.toml` - Added CKB SDK dependencies
- `app/build.gradle.kts` - Added SDK implementation and Guava conflict resolution
- `data/crypto/Blake2b.kt` - Replaced with SDK wrapper (144 → 41 lines)
- `data/wallet/AddressUtils.kt` - Replaced with SDK Address class (179 → 111 lines)
- `data/wallet/KeyManager.kt` - Updated to use SDK ECKeyPair and Sign
- `data/transaction/TransactionBuilder.kt` - Updated signing with SDK
- `di/AppModule.kt` - Removed Blake2b dependency from KeyManager

### Testing & Validation
- [x] Build successful with `./gradlew :app:assembleDebug`
- [x] All compilation errors resolved
- [x] Address format now compatible with CKB faucet

### Challenges & Solutions
- **Challenge**: Guava JRE version conflicts with Android's ListenableFuture
- **Solution**: Added capability resolution strategy to prefer Guava's implementation

```kotlin
configurations.all {
    resolutionStrategy {
        capabilitiesResolution {
            withCapability("com.google.guava:listenablefuture") {
                select("com.google.guava:guava:0")
            }
        }
    }
}
```

---

## Summary

### Key Deliverables
1. **Cryptographic Layer**: Blake2b hashing with CKB personalization, secp256k1 signing
2. **Wallet Management**: Secure key storage, address generation, import/export
3. **Transaction System**: Cell selection, transaction building, signing, submission
4. **Gateway Integration**: Full API client for CKB indexer gateway
5. **Official SDK Integration**: Using battle-tested Nervos CKB SDK for crypto operations

### Files Modified/Created
- `data/crypto/Blake2b.kt` - Blake2b hasher implementation (SDK wrapper)
- `data/wallet/KeyManager.kt` - Secure wallet management (SDK integration)
- `data/wallet/AddressUtils.kt` - CKB address encoding/decoding (SDK Address)
- `data/transaction/TransactionBuilder.kt` - Transaction construction and signing
- `data/gateway/GatewayApi.kt` - HTTP client for gateway
- `data/gateway/GatewayRepository.kt` - Repository pattern for data access
- `data/gateway/models/CkbModels.kt` - CKB data structures
- `data/gateway/models/ApiModels.kt` - API request/response models
- `ui/screens/send/SendViewModel.kt` - Send transaction UI logic

### Next Steps
- [ ] Test faucet CKB receipt with new address format
- [ ] Implement transaction status polling
- [ ] Add QR code scanning for addresses
- [ ] Implement transaction history UI
- [ ] Add mainnet support toggle
- [ ] Write unit tests for cryptographic functions

---

---

## Week 5: December 19 - December 23, 2025

### Overview
Implemented transaction status polling, QR code scanning, enhanced sync modes, and comprehensive transaction history UI with detailed views.

### Completed Tasks

#### Transaction Status Polling
- [x] Implemented dynamic transaction status tracking in `SendViewModel`
- [x] Created `TransactionState` enum with states: IDLE, SENDING, PENDING, PROPOSED, CONFIRMED, FAILED
- [x] Added polling mechanism with configurable intervals (3 seconds)
- [x] Implemented confirmation counter (requires 3 confirmations for "fully confirmed")
- [x] Added real-time status messages during transaction lifecycle
- [x] Auto-refresh balance after transaction confirmation

```kotlin
enum class TransactionState {
    IDLE,           // No transaction in progress
    SENDING,        // Transaction being built and sent
    PENDING,        // Transaction submitted, waiting for confirmation
    PROPOSED,       // Transaction in proposal stage
    CONFIRMED,      // Transaction confirmed on chain
    FAILED          // Transaction failed
}
```

#### QR Code Scanner
- [x] Implemented `QrScannerScreen.kt` using CameraX and ML Kit
- [x] Added camera permission handling with Accompanist Permissions
- [x] Real-time barcode scanning with `BarcodeScanner`
- [x] Address validation for scanned QR codes
- [x] Error handling for invalid QR codes
- [x] Navigation integration to return scanned address

#### Enhanced Sync Mode Selection
- [x] Created `SyncMode` enum with flexible options:
  - `NEW_WALLET`: Sync from current tip only (fastest)
  - `RECENT`: Last 30 days (~200k blocks) - default
  - `FULL_HISTORY`: From genesis block (complete history)
  - `CUSTOM`: User-specified block height
- [x] Implemented sync options dialog in HomeScreen
- [x] Added sync progress indicator with percentage
- [x] Persistent sync preferences using SharedPreferences
- [x] Resync capability when changing sync mode

#### Transaction History UI Enhancements
- [x] Created `TransactionDetailSheet` bottom sheet component
- [x] Display transaction direction with color-coded icons
  - Green arrow down for incoming
  - Red arrow up for outgoing
  - Blue swap icon for self-transfers
- [x] Formatted balance change display with proper CKB decimals
- [x] Confirmation status with visual indicators
- [x] Timestamp formatting
- [x] Copy-to-clipboard for transaction hash
- [x] Block number and fee display

#### Server-Side Transaction Status API
- [x] Added `/v1/transactions/:tx_hash/status` endpoint
- [x] Returns confirmation count, block info, and timestamp
- [x] Status values: pending, proposed, committed, unknown

### Dependencies Added

```kotlin
// CameraX for camera access
camerax-core = { group = "androidx.camera", name = "camera-core" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle" }
camerax-view = { group = "androidx.camera", name = "camera-view" }

// ML Kit for barcode scanning
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning" }

// Permissions handling
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions" }
```

### Files Created/Modified
- `ui/screens/send/SendViewModel.kt` - Added transaction state machine and polling
- `ui/screens/send/SendScreen.kt` - Status UI with progress indicators
- `ui/screens/scanner/QrScannerScreen.kt` - New QR scanner implementation
- `ui/screens/home/HomeScreen.kt` - Added TransactionDetailSheet, sync options dialog
- `ui/screens/home/HomeViewModel.kt` - Sync mode handling, polling logic
- `data/gateway/GatewayRepository.kt` - Added getTransactionStatus(), resyncAccount()
- `data/gateway/GatewayApi.kt` - Added transaction status API call
- `data/gateway/models/ApiModels.kt` - Added TransactionStatusResponse, SyncMode

### Testing & Validation
- [x] Verified transaction status updates in real-time
- [x] Tested QR scanning with CKB address QR codes
- [x] Validated sync mode switching and resync
- [x] Confirmed transaction detail display accuracy

### Challenges & Solutions
- **Challenge**: Transaction polling consuming resources when app is backgrounded
- **Solution**: Cancel polling job in `onCleared()` and when transaction is fully confirmed

- **Challenge**: Sync progress not updating smoothly
- **Solution**: Implemented 5-second polling interval for sync status with periodic transaction refresh

---

## Summary

### Project Status: Feature Complete for Testnet

The CKB Wallet Gateway is now fully functional for testnet use with:

1. **Wallet Management**: Secure key generation, storage, and address derivation
2. **Balance Tracking**: Real-time balance updates with sync status awareness
3. **Transaction Sending**: Full transaction lifecycle with status tracking
4. **Transaction History**: Comprehensive history with detailed views
5. **QR Code Support**: Scan addresses for easy transfers
6. **Flexible Sync**: Multiple sync modes for different use cases
7. **Backend Gateway**: REST API wrapping CKB light client

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ HomeScreen  │  │ SendScreen  │  │ QrScannerScreen     │  │
│  │ + History   │  │ + Status    │  │ + ML Kit            │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│  ┌──────┴────────────────┴─────────────────────┴──────────┐ │
│  │                  ViewModels (Hilt DI)                   │ │
│  └──────────────────────────┬──────────────────────────────┘ │
│                             │                                │
│  ┌──────────────────────────┴──────────────────────────────┐ │
│  │              GatewayRepository + GatewayApi             │ │
│  │                    (Ktor HTTP Client)                   │ │
│  └──────────────────────────┬──────────────────────────────┘ │
│                             │                                │
│  ┌────────────────┐  ┌──────┴───────┐  ┌─────────────────┐  │
│  │  KeyManager    │  │ Transaction  │  │  AddressUtils   │  │
│  │  (Encrypted    │  │ Builder      │  │  (CKB SDK)      │  │
│  │   Storage)     │  │ (CKB SDK)    │  │                 │  │
│  └────────────────┘  └──────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ HTTP/REST
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Rust Gateway Server (Axum)                   │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  /v1/accounts/*     - Registration, Balance, Cells      ││
│  │  /v1/transactions/* - Send, Status                      ││
│  └─────────────────────────────────────────────────────────┘│
└──────────────────────────┬──────────────────────────────────┘
                           │ JSON-RPC
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    CKB Light Client                          │
│                  (Nervos Network Testnet)                    │
└─────────────────────────────────────────────────────────────┘
```

### Recommended Future Enhancements

#### High Priority
- [ ] **Mainnet Support**: Network toggle, mainnet address handling
- [ ] **Unit Tests**: Crypto functions, address encoding, transaction builder
- [ ] **Backup/Recovery**: BIP39 mnemonic, encrypted backups

#### Medium Priority
- [ ] **Transaction Filtering**: By date, direction, amount
- [ ] **Transaction Search**: Find by hash or amount
- [ ] **Biometric Auth**: Fingerprint/Face unlock
- [ ] **Multiple Wallets**: Support for multiple accounts

#### Lower Priority
- [ ] **Analytics Dashboard**: Balance history charts
- [ ] **API Caching**: Reduce network calls
- [ ] **Push Notifications**: Transaction confirmations
- [ ] **Widget Support**: Balance widget for home screen

---

*Last Updated: December 23, 2025*
