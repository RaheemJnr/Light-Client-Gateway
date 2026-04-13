# Key Storage Redesign — Defense-in-Depth for Pocket Node

**Date:** 2026-04-13
**Branch:** feature/m3-multi-wallet
**Status:** Approved

## Problem

KeyManager stores private keys and mnemonic phrases in Android's EncryptedSharedPreferences (ESP). ESP is deprecated (April 2025) and has known device-specific failures (Samsung S24 crashes, StrongBox inconsistencies, `KeyPermanentlyInvalidatedException` on fingerprint/lock screen changes). If the Android Keystore key is invalidated, the ESP file becomes permanently unreadable — the user's only copy of their key material is lost with no recovery path.

### Current Architecture (single point of failure)

```
User's private key + mnemonic
        |
        v
EncryptedSharedPreferences (one file per wallet)
        |
        v
Android Keystore AES key (hardware or software)
        |
        X  ← Keystore invalidated = keys lost forever
```

### Research Findings

Surveyed 6 open-source wallets (BlueWallet, Mycelium, Unstoppable, Schildbach, Blockstream Green, Trust Wallet):

- **BlueWallet** dual-writes to secure store + encrypted Realm — survives single-store corruption
- **Schildbach** auto-backs up keys to a separate protobuf file after every mutation
- **Unstoppable** wipes the entire app on Keystore invalidation — assumes user backed up mnemonic
- **Mycelium** uses software-only AES with PIN-derived key — immune to Keystore invalidation
- **No wallet blocks deposits before mnemonic backup** — all use soft enforcement (warnings, badges)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Storage architecture | Hybrid dual-store (ESP primary + PIN-encrypted file backup) | Survives Keystore death, matches BlueWallet/Schildbach pattern |
| Backup encryption | PIN-derived AES-256-GCM via PBKDF2 (600K iterations) | No Keystore dependency; proven by Mycelium |
| Backup write frequency | Every key mutation | Keeps backup in sync; mutations are rare (wallet create/import) |
| Recovery flow | Two-tier: PIN first, mnemonic fallback after 3 failures | Smooth UX for common case, escape hatch for edge case |
| Mnemonic backup enforcement | Soft-gate (persistent warnings, not blocking) | Industry standard; no wallet blocks deposits |
| Biometrics vs PIN | PIN always required; biometrics is convenience layer on top | PIN is the backup encryption key; biometric auth retrieves Keystore-encrypted PIN |
| Rollout strategy | Layered: Phase 1 (add backup), Phase 2 (migrate primary to Room), Phase 3 (remove ESP) | Each phase is independently safe; backup provides safety net for migration |

## Phase 1: PIN-Encrypted Backup File

### Backup File Format

One file per wallet at `context.filesDir/key_backups/{walletId}.enc`:

```
[16-byte salt][12-byte IV][AES-256-GCM encrypted payload][16-byte GCM auth tag]
```

Decrypted payload (JSON):

```json
{
  "privateKey": "hex...",
  "mnemonic": "word1 word2 ... word12",
  "walletType": "mnemonic",
  "mnemonicBackedUp": false,
  "createdAt": "2026-04-13T...",
  "version": 1
}
```

### Key Derivation

- Algorithm: `PBKDF2WithHmacSHA256`
- Iterations: 600,000 (OWASP 2024 recommendation)
- Salt: random 16 bytes, stored as file prefix
- Output: 256-bit AES-GCM key

### KeyBackupManager (new class)

```
KeyBackupManager
├── writeBackup(walletId, keyMaterial, pin)    — encrypt + atomic write (.tmp → rename)
├── readBackup(walletId, pin)                  — decrypt + parse, null on wrong PIN
├── reEncryptAll(oldPin, newPin)               — re-encrypt all backups on PIN change
├── hasBackup(walletId)                        — check if backup file exists
└── deleteBackup(walletId)                     — remove backup on wallet delete
```

### KeyManager Integration

Every method that writes to ESP also calls `KeyBackupManager.writeBackup()`:

- `generateWalletWithMnemonic()`
- `importWalletFromMnemonic()`
- `savePrivateKey()`
- `storeKeysForWallet()`
- `setMnemonicBackedUp()` / `setMnemonicBackedUpForWallet()`
- `deleteWallet()` / `deleteWalletKeys()` → calls `deleteBackup()`

### Failure Handling

- Backup write failure: log warning, do not fail the primary ESP write
- Backup read wrong PIN: allow 3 attempts, then fall through to mnemonic recovery
- No PIN set: `KeyBackupManager` is a no-op (backup cannot be created without a PIN)

## Phase 1: Recovery Flow

### Detection

`KeyManager.encryptedPrefs` sets `walletResetDueToCorruption = true` when both StrongBox and software Keystore fail. This flag triggers the recovery flow.

### Tier 1 — PIN Recovery

1. App detects `walletResetDueToCorruption == true`
2. Check if `key_backups/{walletId}.enc` exists
3. Show: "Your wallet data needs recovery. Enter your PIN to restore."
4. User enters PIN → `KeyBackupManager.readBackup(walletId, pin)`
5. Success: re-initialize ESP with fresh Keystore key, write recovered key material, re-write backup with new salt
6. Failure: allow 3 attempts total

### Tier 2 — Mnemonic Fallback

After 3 PIN failures or if no backup file exists:

1. Show: "Enter your 12-word recovery phrase to restore your wallet."
2. Functionally equivalent to `importWalletFromMnemonic()` — creates fresh ESP, writes new backup
3. Transaction/balance cache in Room survives (tied to address, not Keystore)

### Multi-Wallet Recovery

Iterate over all `key_backups/*.enc` files. Same PIN unlocks all (different salts). If any wallet fails to decrypt, skip it and surface at end: "1 of 3 wallets could not be recovered with this PIN. Enter its mnemonic to restore it."

## Phase 1: Soft-Gate Warning System

### Warning Hierarchy

**Tier 1 — Persistent banner on Home screen:**
- Shown when `mnemonicBackedUp == false` OR no PIN/biometrics set
- Material 3 `Card` with warning color
- Context-aware action button:
  - Both incomplete → "Secure your wallet" → opens SecurityChecklistScreen
  - Only PIN missing → "Set up a PIN" → goes directly to PIN setup
  - Only mnemonic missing → "Back up recovery phrase" → goes to backup flow
- Banner disappears when all steps complete

**Tier 2 — Modal on Receive screen:**
- Shown when either condition is unmet
- Non-blocking `AlertDialog`
- "Back up now" (primary) / "I understand the risk" (secondary)
- Shown once per app session

**Tier 3 — Post-first-deposit reminder:**
- Triggered on first balance change from 0 → non-zero
- One-time modal: "You now have funds. Set up a PIN and write down your recovery phrase."

### SecurityChecklistScreen

Dedicated screen showing security setup progress:
- [ ] PIN or biometrics enabled
- [ ] Recovery phrase verified

Progress indicator: "2 of 2 complete" with checkmarks.

### Mnemonic Verification Quiz

1. Show 12 words, ask user to write them down
2. Quiz 3 random positions: "What is word #3?" / "What is word #9?" / "What is word #7?"
3. All correct → `setMnemonicBackedUp(true)`
4. Wrong → allow retry, no lockout

## Phase 1: PinManager Integration

### PIN Availability Windows

The raw PIN is only available during:
- **PIN creation** — first-time setup
- **PIN entry** — app unlock
- **PIN change** — old + new PIN

### PIN Lifecycle Events

| Event | KeyBackupManager Action |
|-------|------------------------|
| PIN created (first time) | Write backup for all existing wallets |
| App unlock (PIN entered) | Cache PIN in AuthManager session |
| PIN changed | `reEncryptAll(oldPin, newPin)` |
| Wallet created/imported | Write backup using session PIN |
| Mnemonic backup flag changed | Re-write backup using session PIN |
| Wallet deleted | Delete backup file |

### Session PIN Caching

`AuthManager` holds `private var sessionPin: String?`:
- Set on successful PIN verification
- Set on biometric unlock (retrieves Keystore-encrypted PIN copy)
- Cleared on app background (`ProcessLifecycleOwner` callback)
- Never written to disk, never logged

### Biometrics

PIN is always required as the foundational secret. Biometric auth is a convenience layer that retrieves a Keystore-encrypted copy of the PIN. This ensures:
- Recovery flow (Tier 1) always works with PIN
- Backup encryption doesn't depend on Keystore availability
- Matches banking app pattern: PIN first, biometrics optional

## Phase 2: Primary Store Migration to Keystore+Room

### KeyMaterialEntity (new Room entity)

```kotlin
@Entity(tableName = "key_material")
data class KeyMaterialEntity(
    @PrimaryKey val walletId: String,
    val encryptedPrivateKey: ByteArray,
    val encryptedMnemonic: ByteArray?,
    val iv: ByteArray,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val updatedAt: Long
)
```

### KeystoreEncryptionManager (new class)

- Keystore alias: `pocket_node_key_material`
- AES-256-GCM, no StrongBox requirement (PIN backup is the safety net)
- `encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>` → (ciphertext, iv)
- `decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray`

### Migration Flow

1. Check `key_material` table empty AND ESP has wallets
2. For each wallet: read from ESP, encrypt with Keystore, write to Room
3. Verify round-trip: decrypt from Room, compare with ESP original
4. Mark migration complete (plain SharedPreferences flag)
5. ESP data left in place for one release cycle

## Phase 3: ESP Removal

- Delete ESP files on startup
- Remove `createEncryptedPrefs`, `createEncryptedPrefsForWallet` methods
- Remove all ESP read/write paths from `KeyManager`
- `KeyManager` reads/writes exclusively through `KeyMaterialDao` + `KeystoreEncryptionManager`

## File Changes

### New Files

| File | Phase | Responsibility |
|------|-------|---------------|
| `data/wallet/KeyBackupManager.kt` | 1 | PIN-encrypted backup file I/O |
| `ui/screens/recovery/RecoveryScreen.kt` | 1 | Two-tier recovery UI |
| `ui/screens/security/SecurityChecklistScreen.kt` | 1 | PIN + mnemonic backup checklist |
| `data/crypto/KeystoreEncryptionManager.kt` | 2 | Keystore AES-GCM operations |
| `data/database/entity/KeyMaterialEntity.kt` | 2 | Room entity for encrypted keys |
| `data/database/dao/KeyMaterialDao.kt` | 2 | Room DAO for key material |

### Modified Files

| File | Phase | Changes |
|------|-------|---------|
| `data/wallet/KeyManager.kt` | 1 | Dual-write to ESP + KeyBackupManager on every mutation |
| `data/auth/AuthManager.kt` | 1 | Session PIN caching, clear on background |
| `data/auth/PinManager.kt` | 1 | Expose PIN availability for backup writes |
| `ui/screens/home/HomeScreen.kt` | 1 | Persistent security banner |
| `ui/screens/receive/ReceiveScreen.kt` | 1 | Modal warning if not backed up |
| `ui/navigation/NavGraph.kt` | 1 | Recovery and SecurityChecklist routes |
| `di/AppModule.kt` | 1+2 | Provide KeyBackupManager, KeystoreEncryptionManager |
| `data/database/Migrations.kt` | 2 | Add key_material table migration |

### Untouched Files

- `TransactionBuilder.kt`
- `LightClientNative.kt`
- `WalletPreferences.kt`
- `MnemonicManager.kt`
- Room tables: transactions, balance_cache, header_cache, dao_cells
