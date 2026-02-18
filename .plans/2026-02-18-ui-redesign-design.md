# UI/UX Redesign Design Doc — Pocket Node

**Date:** 2026-02-18
**Scope:** Full UI redesign + Nervos team bug fixes
**Status:** Approved by user — pending Stitch mockup generation

---

## 1. Context & Goals

The current UI has several structural problems identified through:
- Internal audit of all screens
- Nervos team developer review (Feb 2026)
- Benchmark research: CKBull, JoyID, Rainbow Wallet, MetaMask, Coinbase Wallet

### Primary Goals
1. Make the wallet intuitive for non-technical CKB users
2. Fix all bugs raised in the Nervos team review
3. Introduce bottom navigation (industry standard)
4. Add missing features (QR receive, Send Max, block explorer link, fiat toggle)
5. Pre-build the DAO tab placeholder for M2

---

## 2. Nervos Team Bugs to Fix

| # | Bug | Screen | Priority |
|---|-----|--------|----------|
| 1 | Transaction "Time" shows "Confirmed" instead of timestamp | Activity/Home | P0 |
| 2 | Block Hash displays `0x0` | Transaction Detail | P0 |
| 3 | Mainnet address shows `ckt` prefix instead of `ckb` | Send | P0 |
| 4 | Scanning JoyID QR code jumps to Home instead of populating address | Send | P0 |
| 5 | Amount field accepts more than 8 decimal places | Send | P0 |

### Nervos Team Feature Requests (implement as part of redesign)
- Display connected peer count on home screen
- Display specific block height alongside sync percentage
- QR code on Receive screen
- "Send All / Max" button on Send screen
- Transaction hash links to block explorer
- Input/Output detail in transaction view

---

## 3. Navigation Architecture

### Pattern: Hybrid Navigation
- **Bottom nav bar** for primary sections (4 tabs)
- **Top app bar** for screen-specific context and actions
- **Send/Receive** as modal flows launched from Home (not tabs)

### Bottom Tabs

| Tab | Icon | Content | State |
|-----|------|---------|-------|
| Home | Wallet/House | Balance, quick actions, recent txs | Live |
| Activity | History/List | Full tx history, date-grouped, filterable | Live |
| DAO | Lock/Stake | Nervos DAO deposits and withdrawals | M2 placeholder |
| Settings | Gear | Security, network, backup, node status | Live |

### Top Bar (Home)
```text
[● App icon]  Pocket Node  [CKB Mainnet pill]  [⟳ Block 18.3M]  [Settings ⚙]
```
- Network pill: green dot = Mainnet, amber dot = Testnet
- Sync pill: shows "⟳ Block X,XXX,XXX" while syncing, "✓ Synced" when complete
- Settings gear navigates to Settings tab (not dropdown)

---

## 4. Screen Designs

### 4.1 Home Screen

**Layout (top to bottom):**
1. **Balance Hero Card**
   - Header: "Wallet Balance" + CKB/USD toggle
   - Large balance: `12,450.00 CKB`
   - Fiat conversion: `≈ $1,245.00 USD` (from CoinGecko API, shows "—" if unavailable)
   - Address: `ckb1qyq...abc4` [copy icon] (truncated, tap to expand)
   - Peer count: `● 4 peers connected` (green dot)

2. **Quick Actions Row**
   - Send (filled button)
   - Receive (outlined button)
   - Stake [disabled, "M2"] (ghost button)

3. **Recent Transactions Section**
   - Header: "Recent Transactions" + "See All →" (links to Activity tab)
   - Last 5 transactions: direction arrow, amount, truncated address, time, status badge
   - Empty state: illustration + "No transactions yet"

4. **Sync Status Bar** (only visible when syncing)
   - Progress bar: `[▓▓▓▓▓▓▓░░░░] 71%  Block 18,247,832 / ~18,312,000`
   - Hides when fully synced (replaced by nothing)

**Removing from current Home:**
- `⋮` overflow dropdown menu → moved to Settings tab
- Standalone refresh button → pull-to-refresh gesture
- Sync status banner (always-visible) → collapsible progress bar only when active

---

### 4.2 Activity Screen

**Layout:**
- Filter tabs: All | Received | Sent
- Date-grouped list:
  ```text
  TODAY
  ↓  +500 CKB    ckb1...xyz4   14:32   ✓
  ↑  -100 CKB    ckb1...abc1   09:15   ✓

  YESTERDAY
  ↓  +200 CKB    ckb1...def2   23:40   ✓

  EARLIER
  ↑  -50 CKB     ckb1...ghi3   Jan 14  ✓
  ```
- Status badges: `✓ Confirmed` (green), `⏳ Pending` (amber, animated), `✗ Failed` (red)

**Transaction Detail Bottom Sheet:**
- TX Hash: `0x1a2b3c...` [Copy] [🔗 Explorer] ← links to explorer.nervos.org
- Block Number: `18,247,832`
- Block Hash: `0xabc123...` (fix: was showing `0x0`)
- Time: `Feb 15, 2026 14:32:01` (fix: was showing "Confirmed")
- Amount: `+500 CKB` (color: green for received, red for sent)
- Fee Paid: `0.001 CKB`
- Inputs section: list of input cells with address + amount
- Outputs section: list of output cells with address + amount

**Pagination:**
- Load first 50 transactions
- "Load more" button at bottom

---

### 4.3 Receive Screen

**Layout (centered, clean):**
```text
[← back]  Receive CKB

           CKB Mainnet Address

     ┌────────────────────┐
     │    [QR CODE 240dp] │
     └────────────────────┘

     ckb1qyq...4a3f9abc  [⬇ expand]

     [ Copy Address ]
     [ Share        ]

  Share this address to receive CKB tokens
```

**QR Code implementation:**
- Library: `com.google.zxing:core` (ZXing)
- Encodes: raw CKB address string
- Size: 240dp × 240dp, centered
- Network label above QR: "CKB Mainnet" or "CKB Testnet"

---

### 4.4 Send Screen

**Layout:**
```text
[← back]  Send CKB

Available: 12,450.00 CKB

Recipient Address
[ ckb1qyq... or paste address     ] [📷 Scan]

Address validation (inline, real-time):
  ✓ Valid CKB mainnet address
  ✗ Invalid address format
  ⚠ This is a testnet address on mainnet

Amount (CKB)
[ 0.00000000                       ] [Max]
  Min: 61 CKB · Max 8 decimal places

Estimated Fee: ~0.001 CKB

[ ══════════  Send CKB  ══════════ ]
```

**Fixes applied:**
- Address validation distinguishes `ckb` vs `ckt` prefix with clear warning
- `[Max]` button = total balance minus fee estimate
- Amount field: `inputType = decimal`, cap at 8 decimal places client-side
- QR scanner: after scan, populate address field (fix: was jumping to Home)
- JoyID address format: parse `joyid://...` URI and extract underlying CKB address

**Transaction Success State:**
```text
✓ Transaction Sent!

TX Hash: 0x1a2b3c4d...  [Copy] [🔗 View on Explorer]
Status: Pending confirmation...

[ Done ] [ View in Activity ]
```

---

### 4.5 DAO Screen (M2 Placeholder)

```text
[← back]  Nervos DAO

     ┌────────────────────────────────┐
     │  🔒  Nervos DAO                │
     │                                │
     │  Earn compensation by          │
     │  depositing CKB into the       │
     │  Nervos DAO.                   │
     │                                │
     │  Coming in M2                  │
     │                                │
     │  [ Notify me ] (disabled)      │
     └────────────────────────────────┘
```

---

### 4.6 Settings Screen

**Replaces the `⋮` dropdown entirely.**

```text
Security
  › PIN Lock                    [enabled ✓]
  › Biometric Unlock            [enabled ✓]

Wallet
  › Backup Wallet (Seed Phrase)
  › Import Wallet
  › Sync Options                [Recent ▾]

Network
  › Current Network             [CKB Mainnet ▾]
  › Node Status & Logs

About
  › Version 1.0.1
  › Open Source (GitHub link)
```

---

### 4.7 Onboarding (minimal changes)

Keep the current 2-option screen but improve:
- Replace generic shield icon with Pocket Node logo/icon
- Add subtle background gradient
- Improve typography weight and spacing
- Better card visual treatment (elevated cards with subtle border)

---

## 5. Visual Design Language

### Color Palette (placeholder — final colors from icon designer)

| Role | Hex | Usage |
|------|-----|-------|
| Background | `#0D0D0D` | Screen backgrounds |
| Surface | `#1A1A1A` | Cards |
| Surface Variant | `#252525` | Input fields, secondary cards |
| Primary | `#1ED882` | Buttons, active states, icons |
| Primary Dark | `#17B86B` | Button pressed state |
| On Primary | `#000000` | Text on primary buttons |
| Testnet (Amber) | `#F59E0B` | Network badge on testnet |
| Error | `#FF4444` | Errors, destructive actions |
| Success | `#22C55E` | Confirmed tx, connected state |
| Pending | `#F59E0B` | Pending tx, syncing |
| Text Primary | `#FFFFFF` | Main text |
| Text Secondary | `#A0A0A0` | Captions, secondary info |

### Typography
- Balance (hero): 36sp, Bold, CKB green
- Screen title: 20sp, SemiBold
- Card title: 16sp, Medium
- Body text: 14sp, Regular
- Caption: 12sp, Regular, secondary color

### Component Patterns
- **Cards**: `RoundedCornerShape(16.dp)`, surface color, 1dp border at `surfaceVariant`
- **Buttons**: Primary filled = green, outlined = green border, ghost = no border
- **Bottom Nav**: Icons outlined (unselected) → filled (selected), with label
- **Badges**: Small pill with color (green confirmed, amber pending, red error)
- **Inputs**: `OutlinedTextField` with green focus ring

### Animations
- Screen transitions: shared element or fade (no heavy bounces)
- Pending tx: subtle pulsing amber dot
- Sync progress: smooth LinearProgressIndicator with no jumps
- Balance load: count-up number animation (subtle)

---

## 6. Bug Fix Implementation Notes

### Bug 1 — Timestamp shows "Confirmed"
- **Location:** `HomeScreen.kt` transaction item display, `GatewayRepository.kt` transaction parsing
- **Fix:** Parse `blockTimestamp` from JNI response and format as `"MMM dd HH:mm"` using `java.time.Instant`

### Bug 2 — Block Hash = 0x0
- **Location:** `GatewayRepository.kt` → `getTransactions()` → JNI response parsing
- **Fix:** Call `nativeGetHeader(blockNumber)` to fetch block hash separately, or ensure block hash is populated from JNI

### Bug 3 — Address prefix ckt on mainnet
- **Location:** `AddressUtils.kt` or `KeyManager.kt` → address derivation
- **Fix:** Verify `NetworkType.MAINNET` is passed to address generation; check `AddressUtils.generateAddress()` uses correct prefix

### Bug 4 — JoyID QR scan jumps to Home
- **Location:** `QrScannerScreen.kt` → result handling callback
- **Fix:** JoyID QR codes contain a URI like `joyid://...` or a URL; extract the embedded CKB address from the URI and return it to the send screen

### Bug 5 — Amount > 8 decimal places
- **Location:** `SendScreen.kt` → amount `OutlinedTextField`
- **Fix:** Add `visualTransformation` or `onValueChange` filter that truncates input beyond 8 decimal places

---

## 7. New Dependencies Required

| Dependency | Purpose | Library |
|-----------|---------|---------|
| QR code generation | Receive screen QR | `io.github.g0dkar:qrcode-kotlin` or `com.github.alexzhirkevich:qrose` |
| Price feed | CKB/USD fiat conversion | CoinGecko public API (no key needed for basic tier) |
| Block explorer | TX hash links | `explorer.nervos.org/transaction/{hash}` (just a URL, no SDK needed) |

---

## 8. Out of Scope (this PR)

- Multi-wallet support (M3)
- Nervos DAO actual functionality (M2)
- Address book (M4)
- Hardware wallet support
- Token/NFT support (not a Pocket Node feature)

---

## 9. Implementation Order (for writing-plans)

1. Bug fixes (P0 — unblock Nervos team review)
2. Bottom navigation + routing restructure
3. Home screen redesign
4. Receive screen + QR code
5. Send screen fixes + Max button + explorer link
6. Activity screen (full history + detail bottom sheet)
7. Settings screen (replace dropdown)
8. DAO placeholder tab
9. Onboarding visual polish
10. Stitch-generated component integration
11. Fiat price feed (CoinGecko) integration
12. Create GitHub issue + PR

---

*This design was produced through the brainstorming process with user approval at each section.*
