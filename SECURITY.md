# Security Policy

## Reporting Vulnerabilities

If you discover a security vulnerability in Pocket Node, please report it responsibly:

1. **Do NOT open a public issue.** Security vulnerabilities should be reported privately.
2. **Email**: [mumedian6@gmail.com](mailto:mumedian6@gmail.com) for critical vulnerabilities.
3. **GitHub**: Use [Security Advisories](https://github.com/RaheemJnr/pocket-node/security/advisories/new) for non-critical reports.
4. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will acknowledge receipt within 48 hours and provide a timeline for resolution.

## Security Model

### Key Storage

Private keys and BIP39 mnemonics are encrypted using Android's `EncryptedSharedPreferences` backed by the Android Keystore system:

- **Encryption**: AES-256-GCM
- **Key backing**: StrongBox HSM (Pixel 3+, Galaxy S9+) or TEE (all Android 8.0+ devices)
- **Key generation**: `MasterKey` with `setRequestStrongBoxBacked(true)`, automatic TEE fallback

### Authentication

- **Biometric**: `BiometricPrompt` with `BIOMETRIC_STRONG` authenticators
- **PIN fallback**: 6-digit PIN, hashed with Blake2b + per-device salt
- **Lockout**: 5 failed attempts triggers 30-second lockout

### Transaction Security

- All transaction signing is performed locally on-device
- Private keys never leave the device or are transmitted over the network
- Address network validation prevents cross-network sends (mainnet vs testnet)
- Transaction size checked against CKB protocol limits before broadcast

### Build Security

- `android:allowBackup="false"` prevents ADB backup of key material
- Release builds strip all `android.util.Log` calls via ProGuard (`-assumenosideeffects`)
- R8 code shrinking and resource shrinking enabled for release builds
- Release signing uses environment-variable-based keystore configuration

### Light Client

- The embedded CKB light client communicates directly with CKB mainnet peers via P2P
- RPC binds to `127.0.0.1:9000` (localhost only, not exposed externally)
- No intermediary servers — the app is fully self-sovereign

## Known Limitations

- This software has **not undergone a formal security audit**
- Single wallet support only (no wallet isolation between accounts)
- No certificate pinning for any network connections
- Light client P2P traffic is not encrypted beyond CKB protocol-level protections

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.1.x   | Yes       |
| < 1.1.0 | No        |

## Scope

The following are in scope for security reports:

- Private key or mnemonic exposure
- Authentication bypass
- Transaction manipulation
- Data leakage through logs, backups, or unencrypted storage
- JNI bridge vulnerabilities

The following are **out of scope**:

- CKB protocol-level vulnerabilities (report to [Nervos](https://github.com/nervosnetwork))
- Social engineering attacks
- Physical device access with a rooted/jailbroken device
- Denial of service against the light client P2P network
