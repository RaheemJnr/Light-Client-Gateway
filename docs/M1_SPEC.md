# M1_SPEC.md — Milestone 1: Mainnet Ready & Hardware-Backed Security

**Timeline**: Month 1 (~4 weeks)
**Budget**: $3,375 (22.5% of grant) + $1,500 (10% commencement)
**Releases**: v1.1.0 (Week 2), v1.2.0 (Week 4)

## Accepted Deliverables

From the [accepted DAO proposal](https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879):

1. Production mainnet configuration with cell dependencies and bootnode settings
2. BIP39 mnemonic generation with TEE/StrongBox encryption
3. Biometric authentication (fingerprint/face) with PIN fallback
4. Comprehensive mainnet testing with real transactions
5. Open-source repository on GitHub
6. Releases: v1.1.0 (Week 2), v1.2.0 (Week 4)

---

## Feature 1: BIP39 Mnemonic with TEE/StrongBox Encryption

### Overview

Replace raw private key generation with BIP39 mnemonic-based key derivation. Users receive a 12-word seed phrase for backup and can recover their wallet by entering the mnemonic. The derived private key is encrypted using Android's Trusted Execution Environment (TEE) or StrongBox hardware security module.

### CKB Derivation Path

```
BIP44: m/44'/309'/0'/0/0
  44'  = BIP44 purpose
  309' = CKB coin type (SLIP-0044 registered)
  0'   = first account
  0    = external chain
  0    = first address index
```

### Library Selection

**Primary**: `cash.z.ecc.android:kotlin-bip39`
- Pure Kotlin, lightweight, well-maintained (Zcash team)
- Provides mnemonic generation and seed derivation (PBKDF2-SHA512)
- BIP32 key derivation must be added manually

**Fallback**: `org.bitcoinj:bitcoinj-core`
- Full BIP39 + BIP32 + BIP44 support
- Heavier dependency but battle-tested
- Use if kotlin-bip39 lacks needed functionality

### Files to Create

#### `data/wallet/MnemonicManager.kt`

```kotlin
@Singleton
class MnemonicManager @Inject constructor() {

    fun generateMnemonic(wordCount: WordCount = WordCount.TWELVE): List<String>
    fun validateMnemonic(words: List<String>): Boolean
    fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray
    fun derivePrivateKey(seed: ByteArray, accountIndex: Int = 0, addressIndex: Int = 0): ByteArray
    fun mnemonicToPrivateKey(words: List<String>, passphrase: String = ""): ByteArray

    enum class WordCount(val entropyBits: Int) {
        TWELVE(128),
        TWENTY_FOUR(256)
    }
}
```

**Implementation Details**:
- Generate entropy with `SecureRandom`
- Convert entropy to mnemonic words via BIP39 word list
- Derive 512-bit seed using PBKDF2-SHA512 (2048 rounds, mnemonic as password, "mnemonic" + passphrase as salt)
- Derive master key using HMAC-SHA512 with key "Bitcoin seed"
- Walk BIP44 path `m/44'/309'/0'/0/0` using hardened child key derivation
- Return 32-byte private key from final derivation

#### `ui/screens/onboarding/MnemonicBackupScreen.kt`

New Compose screen for the mnemonic backup flow:

**Step 1 — Display**: Show 12 words in a numbered 3x4 grid
- Prevent screenshots: `window.setFlags(FLAG_SECURE)`
- Show "I have written these down" checkbox

**Step 2 — Verify**: User selects correct words for 3 random positions
- Scramble word order for selection
- Highlight correct/incorrect selections
- Require all 3 correct to proceed

**Step 3 — Success**: Confirmation message, navigate to Home

#### `ui/screens/onboarding/MnemonicImportScreen.kt`

New Compose screen for mnemonic recovery:
- 12 individual text fields (or paste-friendly single field)
- Word autocomplete from BIP39 word list
- Real-time validation feedback
- "Import" button triggers wallet recovery with RECENT sync mode

### Files to Modify

#### `data/wallet/KeyManager.kt`

**Major changes** — Add mnemonic-aware wallet creation alongside existing raw key support:

```kotlin
// NEW constants
companion object {
    private const val KEY_PRIVATE_KEY = "private_key"
    private const val KEY_MNEMONIC = "mnemonic_words"              // encrypted mnemonic
    private const val KEY_MNEMONIC_BACKED_UP = "mnemonic_backed_up" // boolean flag
    private const val KEY_WALLET_TYPE = "wallet_type"              // "mnemonic" or "raw_key"
}

// NEW methods
fun generateWalletWithMnemonic(): Pair<WalletInfo, List<String>>
fun importWalletFromMnemonic(words: List<String>, passphrase: String = ""): WalletInfo
fun hasMnemonicBackup(): Boolean
fun setMnemonicBackedUp(backedUp: Boolean)
fun storeMnemonic(words: List<String>)      // encrypted storage
fun getMnemonic(): List<String>?             // for re-display during backup
fun getWalletType(): String                  // "mnemonic" or "raw_key"
```

**TEE/StrongBox encryption for mnemonic storage**:
```kotlin
private val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .setRequestStrongBoxBacked(true)  // Use StrongBox if available
    .build()
```

> StrongBox is available on Pixel 3+ and Samsung Galaxy S9+. If unavailable, falls back to TEE-backed encryption which is available on all Android 8+ devices.

#### `data/gateway/GatewayRepository.kt`

Minor changes:
```kotlin
// MODIFY: createNewWallet() to use mnemonic flow
suspend fun createNewWallet(): Result<Pair<WalletInfo, List<String>>>

// ADD: Import from mnemonic
suspend fun importFromMnemonic(
    words: List<String>,
    passphrase: String = "",
    syncMode: SyncMode = SyncMode.RECENT
): Result<WalletInfo>
```

#### `ui/screens/onboarding/OnboardingScreen.kt`

- Add third option: "Recover from Seed Phrase"
- "Create New Wallet" now leads to MnemonicBackupScreen before Home
- "Import Existing Wallet" becomes expandable: mnemonic OR raw key

#### `ui/screens/onboarding/OnboardingViewModel.kt`

```kotlin
// MODIFY
fun createNewWallet()  // stores mnemonic, navigates to backup screen

// ADD
fun importFromMnemonic(words: List<String>)
val mnemonicWords: StateFlow<List<String>>   // for backup screen
```

#### `ui/navigation/NavGraph.kt`

Add routes:
```kotlin
object MnemonicBackup : Screen("mnemonic_backup")
object MnemonicImport : Screen("mnemonic_import")
```

#### `ui/screens/home/HomeScreen.kt`

- Add "Backup Seed Phrase" in dropdown menu (if wallet type is mnemonic)
- Show backup reminder banner if `hasMnemonicBackup() == false`

#### `di/AppModule.kt`

Add MnemonicManager provider:
```kotlin
@Provides @Singleton
fun provideMnemonicManager(): MnemonicManager = MnemonicManager()
```

### Dependencies to Add

In `gradle/libs.versions.toml`:
```toml
[versions]
bip39 = "1.0.8"

[libraries]
kotlin-bip39 = { group = "cash.z.ecc.android", name = "kotlin-bip39", version.ref = "bip39" }
```

In `app/build.gradle.kts`:
```kotlin
implementation(libs.kotlin.bip39)
```

### Testing

- Unit test `MnemonicManager`: generate, validate, derive with known test vectors
- Known test vector: mnemonic -> seed -> private key -> CKB address (use CKB SDK reference)
- Test 12-word and 24-word generation
- Test invalid mnemonic rejection (wrong word, wrong count, wrong checksum)
- Test derivation path `m/44'/309'/0'/0/0` matches existing CKB wallet implementations

---

## Feature 2: Biometric Authentication with PIN Fallback

### Overview

Gate wallet access behind Android BiometricPrompt. Users must authenticate with fingerprint or face recognition before wallet content is visible. Falls back to a 6-digit PIN or device credential on devices without biometrics.

### API Strategy

| API Level | Approach |
|-----------|----------|
| 28+ (Android 9+) | `BiometricPrompt` with `BIOMETRIC_STRONG` + `DEVICE_CREDENTIAL` |
| 26-27 (Android 8.x) | `KeyguardManager.isDeviceSecure()` + custom PIN |

### Files to Create

#### `data/auth/AuthManager.kt`

```kotlin
@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isBiometricAvailable(): BiometricStatus
    fun isBiometricEnrolled(): Boolean
    fun isAuthConfigured(): Boolean
    fun setBiometricEnabled(enabled: Boolean)
    fun isBiometricEnabled(): Boolean
    fun hasDeviceCredential(): Boolean
    fun getAllowedAuthenticators(): Int

    enum class BiometricStatus {
        AVAILABLE,       // Hardware present and enrolled
        NO_HARDWARE,     // No biometric hardware
        NOT_ENROLLED,    // Hardware present but nothing enrolled
        UNAVAILABLE      // Temporarily unavailable
    }
}
```

Uses `BiometricManager.from(context).canAuthenticate()` to check status.

#### `data/auth/PinManager.kt`

```kotlin
@Singleton
class PinManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun setPin(pin: String)
    fun verifyPin(pin: String): Boolean
    fun hasPin(): Boolean
    fun removePin()
    fun getRemainingAttempts(): Int
    fun recordFailedAttempt()
    fun resetFailedAttempts()
    fun isLockedOut(): Boolean
    fun getLockoutRemainingMs(): Long

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L  // 30 seconds
    }
}
```

**PIN hashing**: Use existing Blake2b with a per-device salt stored in EncryptedSharedPreferences:
```kotlin
private fun hashPin(pin: String): String {
    val salt = getOrCreateSalt()
    val input = (salt + pin).toByteArray()
    val hash = org.nervos.ckb.crypto.Blake2b.digest(input)
    return hash.joinToString("") { "%02x".format(it) }
}
```

#### `ui/screens/auth/AuthScreen.kt`

```kotlin
@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit
)
```

- Shows app logo/name on launch
- Automatically triggers `BiometricPrompt` on composition
- Shows "Unlock with Fingerprint" button for manual retry
- Shows "Use PIN" fallback button
- Handles `onAuthenticationSucceeded`, `onAuthenticationError`, `onAuthenticationFailed`

**BiometricPrompt setup**:
```kotlin
val promptInfo = BiometricPrompt.PromptInfo.Builder()
    .setTitle("Unlock Pocket Node")
    .setSubtitle("Authenticate to access your wallet")
    .setAllowedAuthenticators(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    .build()
```

#### `ui/screens/auth/AuthViewModel.kt`

```kotlin
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val pinManager: PinManager
) : ViewModel() {

    data class AuthUiState(
        val isAuthenticated: Boolean = false,
        val showBiometricPrompt: Boolean = true,
        val showPinEntry: Boolean = false,
        val error: String? = null
    )
}
```

#### `ui/screens/auth/PinEntryScreen.kt`

```kotlin
@Composable
fun PinEntryScreen(
    mode: PinMode,       // SETUP, CONFIRM, VERIFY
    onPinVerified: () -> Unit,
    onPinSet: () -> Unit,
    onCancel: () -> Unit
)

enum class PinMode { SETUP, CONFIRM, VERIFY }
```

- 6-digit PIN pad with large touch targets (Material 3 styling)
- Dot indicators showing entered digits
- Shake animation on wrong PIN
- Lockout countdown timer display
- "Forgot PIN?" leads to wallet recovery flow (import mnemonic)

#### `ui/screens/auth/PinViewModel.kt`

```kotlin
@HiltViewModel
class PinViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {

    data class PinUiState(
        val enteredDigits: Int = 0,
        val mode: PinMode = PinMode.VERIFY,
        val error: String? = null,
        val isLockedOut: Boolean = false,
        val lockoutRemainingSeconds: Int = 0,
        val remainingAttempts: Int = 5
    )
}
```

#### `ui/screens/settings/SecuritySettingsScreen.kt`

```kotlin
@Composable
fun SecuritySettingsScreen(
    onNavigateBack: () -> Unit
)
```

- Toggle: Enable/disable biometric authentication
- Button: Set/Change PIN
- Status: Show current security configuration
- Warning: If no auth is configured

### Files to Modify

#### `MainActivity.kt`

Add auth gate before main content:
```kotlin
val requiresAuth = repository.hasWallet() && authManager.isAuthConfigured()
val startDestination = when {
    !repository.hasWallet() -> Screen.Onboarding.route
    requiresAuth -> Screen.Auth.route
    else -> Screen.Home.route
}
```

Inject AuthManager:
```kotlin
@Inject lateinit var authManager: AuthManager
```

#### `ui/navigation/NavGraph.kt`

Add routes:
```kotlin
object Auth : Screen("auth")
object PinSetup : Screen("pin_setup")
object PinEntry : Screen("pin_entry")
object SecuritySettings : Screen("security_settings")
```

Add composable entries with proper navigation (Auth pops itself on success).

#### `ui/screens/home/HomeScreen.kt`

- Add "Security Settings" option in dropdown menu
- Navigate to SecuritySettingsScreen

#### `di/AppModule.kt`

Add providers:
```kotlin
@Provides @Singleton
fun provideAuthManager(@ApplicationContext context: Context): AuthManager = AuthManager(context)

@Provides @Singleton
fun providePinManager(@ApplicationContext context: Context): PinManager = PinManager(context)
```

### Dependencies to Add

In `gradle/libs.versions.toml`:
```toml
[versions]
biometric = "1.1.0"

[libraries]
androidx-biometric = { group = "androidx.biometric", name = "biometric", version.ref = "biometric" }
```

In `app/build.gradle.kts`:
```kotlin
implementation(libs.androidx.biometric)
```

### Testing

- Test BiometricPrompt with Android test APIs
- Test PIN set/verify/lockout cycle (unit test PinManager)
- Test hash consistency: same PIN always produces same hash
- Test lockout after MAX_ATTEMPTS, verify timer
- Manual: enable biometric -> close app -> reopen -> verify prompt shows
- Edge case: biometric enrolled then removed from device settings

---

## Feature 3: Mainnet Production Readiness

### Overview

Harden the app for real mainnet CKB transactions. Review all configurations, add address validation, improve error handling, and configure release signing.

### Files to Create

#### `data/gateway/NetworkValidator.kt`

```kotlin
object NetworkValidator {
    fun validateAddressNetwork(address: String, expected: NetworkType): Boolean {
        return when (expected) {
            NetworkType.MAINNET -> address.startsWith("ckb1")
            NetworkType.TESTNET -> address.startsWith("ckt1")
        }
    }

    fun validateTransactionOutputs(outputs: List<CellOutput>, network: NetworkType): Boolean
    fun validateMinimumCapacity(capacityShannons: Long): Boolean  // >= 61 CKB
}
```

### Files to Modify

#### `app/build.gradle.kts`

```kotlin
// Update version
versionCode = 3
versionName = "1.1.0"

// Add release signing config
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore/release.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: ""
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        isShrinkResources = true  // ADD resource shrinking
    }
}
```

Remove vestigial `GATEWAY_URL` build config fields.

#### `data/gateway/GatewayRepository.kt`

- Add network mismatch detection before `sendTransaction()`
- Add retry logic in `initializeNode()` for JNI errors
- Improve `refreshBalance()` edge case handling
- Gate debug logging behind `BuildConfig.DEBUG`

#### `data/transaction/TransactionBuilder.kt`

- Add address network validation (reject `ckt` addresses on mainnet)
- Validate transaction size before signing
- Verify mainnet secp256k1 cell dep constants are correct

#### `CkbModels.kt`

- Verify `SECP256K1_CODE_HASH` matches mainnet
- Verify `CellDep.SECP256K1_MAINNET` is correct

#### `assets/mainnet.toml`

- Verify all bootnodes are current and reachable
- Review `max_peers` for mobile battery optimization

#### `proguard-rules.pro`

```
# Keep CKB SDK classes
-keep class org.nervos.ckb.** { *; }

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }

# Keep secp256k1-kmp JNI
-keep class fr.acinq.secp256k1.** { *; }

# Keep kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }
```

#### `AndroidManifest.xml`

```xml
android:allowBackup="false"
android:extractNativeLibs="true"
```

### Testing

- Manual testing on CKB mainnet with small amounts (< 100 CKB)
- Verify mainnet address generation matches CKB explorer
- Verify transaction signing matches CKB SDK reference
- Test transaction with exact 61 CKB minimum
- Test change output edge cases (amount + fee == available, dust change < 61 CKB)
- Test network mismatch rejection (send to ckt address on mainnet)
- Battery usage profiling with light client running

---

## Feature 4: Open Source & Release Pipeline

### Overview

Make the repository public, set up CI/CD with GitHub Actions, and establish a bi-weekly release process.

### Files to Create

#### `.github/workflows/android-ci.yml`

```yaml
name: Android CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi
      - name: Build JNI library
        run: cd external/ckb-light-client && ./build-android-jni.sh
      - name: Build Android app
        run: cd android && ./gradlew assembleDebug
      - name: Run tests
        run: cd android && ./gradlew test
      - uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: android/app/build/outputs/apk/debug/*.apk
```

#### `.github/workflows/release.yml`

Triggered on version tags (`v*`):
- Build signed release APK
- Create GitHub Release with APK attachment
- Upload to Google Play via Gradle Play Publisher (optional)

#### `CONTRIBUTING.md`

- Development environment setup
- Code style (Kotlin conventions, Compose patterns)
- PR process and review guidelines
- Issue templates reference

#### `SECURITY.md`

- Security contact information
- Responsible disclosure policy
- Key management security model
- Known security considerations (pre-audit)

#### `.github/ISSUE_TEMPLATE/bug_report.md`
#### `.github/ISSUE_TEMPLATE/feature_request.md`
#### `.github/PULL_REQUEST_TEMPLATE.md`

### Files to Modify

#### `README.md`

- Update for open-source audience
- Remove testnet-only warnings
- Update architecture diagram to reflect JNI (not server)
- Add build-from-source instructions
- Add security model description
- Add CI badges

#### `.gitignore`

Ensure secrets excluded:
```
keystore/
*.jks
*.keystore
local.properties
google-services.json
.env
```

### Release Process (Bi-weekly)

1. Feature freeze (Monday)
2. Create release branch `release/v1.X.0`
3. CI builds + runs tests
4. QA testing (2-3 days)
5. Tag `v1.X.0`, merge to main
6. GitHub Release with APK
7. Google Play Store update
8. F-Droid metadata update (post-M4)

### Google Play Preparation

- App icon (adaptive icon — already configured)
- Screenshots (phone form factor)
- Feature graphic (1024x500)
- Short description (80 chars max)
- Full description with feature list
- Privacy policy URL
- Content rating questionnaire
- Target audience declaration

---

## Implementation Schedule

### Week 1: BIP39 Mnemonic (→ included in v1.1.0)

| Day | Task |
|-----|------|
| 1 | Add kotlin-bip39 dependency, implement `MnemonicManager` core (generate, validate, seed derivation) |
| 2 | Implement BIP32/BIP44 key derivation for path `m/44'/309'/0'/0/0` |
| 3 | Modify `KeyManager` for mnemonic-aware creation, TEE/StrongBox `MasterKey` |
| 4 | Build `MnemonicBackupScreen` (display words, confirm, verify) |
| 5 | Build `MnemonicImportScreen` (word entry, validation, recovery) |
| 6 | Update `OnboardingScreen/ViewModel`, add `NavGraph` routes |
| 7 | Unit tests for `MnemonicManager`, integration testing |

### Week 2: Biometric + PIN Auth (→ v1.1.0 release)

| Day | Task |
|-----|------|
| 1 | Add biometric dependency, implement `AuthManager` |
| 2 | Implement `PinManager` (hash, verify, lockout) |
| 3 | Build `AuthScreen` with BiometricPrompt integration |
| 4 | Build `PinEntryScreen` (setup + verify modes) |
| 5 | Build `SecuritySettingsScreen`, integrate in `HomeScreen` menu |
| 6 | Modify `MainActivity` for auth gate, update `NavGraph` |
| 7 | **Release v1.1.0**: BIP39 mnemonic + biometric/PIN auth |

### Week 3: Mainnet Hardening (→ included in v1.2.0)

| Day | Task |
|-----|------|
| 1 | Create `NetworkValidator`, add address validation |
| 2 | Harden `GatewayRepository` error handling, add retry logic |
| 3 | Harden `TransactionBuilder` validation |
| 4 | Configure release signing, update ProGuard rules |
| 5 | Update `AndroidManifest`, gate debug logging |
| 6 | Manual mainnet testing with real CKB transactions |
| 7 | Battery profiling, edge case testing |

### Week 4: Open Source + CI/CD (→ v1.2.0 release)

| Day | Task |
|-----|------|
| 1 | Set up GitHub Actions CI workflow |
| 2 | Set up release workflow |
| 3 | Create `CONTRIBUTING.md`, `SECURITY.md`, issue/PR templates |
| 4 | Update `README.md` for open source, clean git history |
| 5 | Google Play Store listing materials |
| 6 | Make repository public |
| 7 | **Release v1.2.0**: Mainnet-hardened, open source, CI/CD |

---

## Release Artifacts

### v1.1.0 (Week 2)
- BIP39 mnemonic generation and backup flow
- Mnemonic import/recovery
- Biometric authentication (fingerprint/face)
- PIN fallback authentication
- Security settings screen
- TEE/StrongBox encrypted key storage

### v1.2.0 (Week 4)
- Mainnet production configuration hardened
- Address network validation
- Improved error handling and retry logic
- Release signing configured
- GitHub Actions CI/CD
- Repository public on GitHub
- CONTRIBUTING.md, SECURITY.md, issue templates
- Updated README for open source

---

## Dependencies Summary

### New Dependencies (M1)

| Library | Version | Purpose |
|---------|---------|---------|
| `cash.z.ecc.android:kotlin-bip39` | 1.0.8 | BIP39 mnemonic generation and seed derivation |
| `androidx.biometric:biometric` | 1.1.0 | BiometricPrompt API for fingerprint/face auth |

### Existing Dependencies (reused)

| Library | Purpose in M1 |
|---------|---------------|
| `org.nervos.ckb:core` (4.0.0) | Blake2b for PIN hashing, secp256k1 for key derivation |
| `androidx.security:security-crypto` (1.1.0) | EncryptedSharedPreferences for mnemonic + PIN storage |
| `secp256k1-kmp-jni-android` (0.21.0) | Key derivation verification |

---

## New Files Summary

| File | Feature |
|------|---------|
| `data/wallet/MnemonicManager.kt` | BIP39 |
| `ui/screens/onboarding/MnemonicBackupScreen.kt` | BIP39 |
| `ui/screens/onboarding/MnemonicImportScreen.kt` | BIP39 |
| `data/auth/AuthManager.kt` | Biometric |
| `data/auth/PinManager.kt` | PIN |
| `ui/screens/auth/AuthScreen.kt` | Auth gate |
| `ui/screens/auth/AuthViewModel.kt` | Auth gate |
| `ui/screens/auth/PinEntryScreen.kt` | PIN |
| `ui/screens/auth/PinViewModel.kt` | PIN |
| `ui/screens/settings/SecuritySettingsScreen.kt` | Settings |
| `data/gateway/NetworkValidator.kt` | Mainnet |
| `.github/workflows/android-ci.yml` | CI/CD |
| `.github/workflows/release.yml` | CI/CD |
| `CONTRIBUTING.md` | Open source |
| `SECURITY.md` | Open source |

## Modified Files Summary

| File | Changes |
|------|---------|
| `KeyManager.kt` | Add mnemonic methods, TEE/StrongBox MasterKey, new storage keys |
| `GatewayRepository.kt` | Mnemonic wallet creation, network validation, error hardening |
| `TransactionBuilder.kt` | Address validation, tx size checks |
| `OnboardingScreen.kt` | Mnemonic-first flow, import options |
| `OnboardingViewModel.kt` | Mnemonic generation, import from mnemonic |
| `HomeScreen.kt` | Backup reminder, security settings menu item |
| `MainActivity.kt` | Auth gate injection and routing |
| `NavGraph.kt` | 6 new routes (MnemonicBackup, MnemonicImport, Auth, PinSetup, PinEntry, SecuritySettings) |
| `AppModule.kt` | Provide MnemonicManager, AuthManager, PinManager |
| `build.gradle.kts` | New deps, version bump, release signing |
| `libs.versions.toml` | Add bip39, biometric versions |
| `proguard-rules.pro` | Keep CKB SDK, BouncyCastle, secp256k1 classes |
| `AndroidManifest.xml` | Disable backup, extractNativeLibs |
| `README.md` | Open source updates |
| `.gitignore` | Ensure secrets excluded |
