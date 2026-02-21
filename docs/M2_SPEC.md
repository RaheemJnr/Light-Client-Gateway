# M2_SPEC.md — Milestone 2: Nervos DAO Protocol Integration

**Timeline**: Month 2 (~4 weeks)
**Budget**: $3,375 (22.5% of grant)
**Releases**: v1.3.0 (Week 6), v1.4.0 (Week 8)

## Accepted Deliverables

From the [accepted DAO proposal](https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879):

1. DAO Transaction Builder: specialized DAO outputs (Deposit, Withdrawal, Unlocking) currently missing from standard JNI interfaces
2. DAO Lifecycle Management: two-phase unlock process (withdraw → wait for maturity → unlock)
3. In-App Economics: real-time compensation tracking and APY calculation by querying header information
4. Visualization: DAO dashboard with lock countdown timers and maturity status
5. Testing with various deposit amounts and lock periods
6. Releases: v1.3.0 (Week 6), v1.4.0 (Week 8)

---

## Approach: Full JNI Extension (Approach A)

Three new Rust JNI functions expose `ckb-dao-utils` (already in Cargo deps) for compensation calculation and epoch math. Kotlin side handles tx assembly via existing `TransactionBuilder`, UI, and state management. UI modeled after [Neuron wallet](https://github.com/nervosnetwork/neuron) patterns (7-state status machine, overview + records list, compensation progress bar).

### Reference Documentation

- [RFC-0023: DAO Deposit and Withdraw](https://docs.ckb.dev/docs/rfcs/0023-dao-deposit-withdraw/0023-dao-deposit-withdraw)
- [RFC-0024: Genesis Script List](https://github.com/nervosnetwork/rfcs/blob/master/rfcs/0024-ckb-genesis-script-list/0024-ckb-genesis-script-list.md)
- [Neuron DAO Implementation](https://github.com/nervosnetwork/neuron/tree/develop/packages/neuron-ui/src/components/NervosDAO)
- [Nervos DAO Withdrawal Process](https://www.nervos.org/knowledge-base/nervosdao_withdrawal_process_explained)

---

## Feature 1: DAO Data Models & Constants

### Overview

Define protocol constants, data models, and the 7-state status enum. These form the foundation that all other features depend on.

### Nervos DAO Protocol Constants

```
DAO_CODE_HASH    = 0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e
DAO_HASH_TYPE    = "type"
DAO_ARGS         = "0x" (empty)

Cell Dep (Mainnet): tx_hash = 0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c, index = 0x2, dep_type = "code"
Cell Dep (Testnet): tx_hash = 0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f, index = 0x2, dep_type = "code"

WITHDRAW_EPOCHS        = 180          (one compensation cycle)
HOURS_PER_EPOCH        = 4            (~4 hours per CKB epoch)
MIN_DEPOSIT_SHANNONS   = 10,200,000,000  (102 CKB)
RESERVE_SHANNONS       = 6,200,000,000   (62 CKB for future fees)
DAO_DEPOSIT_DATA       = 0x0000000000000000 (8 zero bytes)
```

### Files to Create

#### `data/gateway/DaoConstants.kt`

Object containing all protocol constants above. Network-aware `daoCellDep(network: NetworkType): CellDep` helper that returns the correct cell dep per network. Follows the same pattern as `CellDep.SECP256K1_TESTNET` / `CellDep.SECP256K1_MAINNET` in `CkbModels.kt:30-44`.

#### `data/gateway/models/DaoModels.kt`

**DaoCellStatus** — 7-state enum modeled after Neuron's `getDAOCellStatus.ts`:

| State | Condition | Meaning |
|-------|-----------|---------|
| `DEPOSITING` | Deposit tx sent, awaiting confirmation | Spinner |
| `DEPOSITED` | Confirmed on-chain, no withdraw info | "Withdraw" button enabled |
| `WITHDRAWING` | Phase 1 tx sent, awaiting confirmation | Spinner |
| `LOCKED` | Phase 1 confirmed, current epoch < unlock epoch | Countdown |
| `UNLOCKABLE` | Phase 1 confirmed, current epoch >= unlock epoch | "Unlock" button enabled |
| `UNLOCKING` | Phase 2 tx sent, awaiting confirmation | Spinner |
| `COMPLETED` | Fully unlocked, funds returned | Moved to "Completed" tab |

Status determination priority (from Neuron): unlock path → withdraw path → deposit path.

**DaoDeposit** — data class representing one DAO deposit:
- `outPoint: OutPoint` — cell reference
- `capacity: Long` — deposited shannons
- `status: DaoCellStatus`
- `depositBlockNumber: Long`, `depositBlockHash: String`, `depositEpoch: EpochInfo`
- `withdrawBlockNumber: Long?`, `withdrawBlockHash: String?`, `withdrawEpoch: EpochInfo?` (null if still deposited)
- `compensation: Long` — earned compensation in shannons
- `unlockEpoch: EpochInfo?` — earliest epoch for phase 2
- `lockRemainingHours: Int?` — hours until unlockable
- `compensationCycleProgress: Float` — 0.0-1.0 progress in current 180-epoch cycle
- `cyclePhase: CyclePhase` — NORMAL, SUGGESTED, or ENDING

**EpochInfo** — parsed epoch data:
- `number: Long`, `index: Long`, `length: Long`
- Computed property `value: Double = number + index.toDouble() / length`

**CyclePhase** — enum: `NORMAL` (0-80%), `SUGGESTED` (80-95%), `ENDING` (95-100%)

**DaoOverview** — aggregate stats:
- `totalLocked: Long`, `totalCompensation: Long`, `currentApc: Double`, `activeCount: Int`, `completedCount: Int`

**DaoUiState** — ViewModel state:
- `overview: DaoOverview`, `activeDeposits: List<DaoDeposit>`, `completedDeposits: List<DaoDeposit>`
- `selectedTab: DaoTab` (ACTIVE / COMPLETED)
- `isLoading: Boolean`, `error: String?`, `pendingAction: DaoAction?`

**DaoAction** — sealed class: `Depositing(amount)`, `Withdrawing(outPoint)`, `Unlocking(outPoint)`

### Testing

- Unit test `DaoCellStatus` determination logic with all 7 states
- Unit test `EpochInfo.value` calculation
- Unit test `CyclePhase` derivation from progress values

---

## Feature 2: JNI Layer — 3 New Rust Functions

### Overview

Expose `ckb-dao-utils` from Rust via JNI for compensation calculation and epoch math. These are the only new Rust functions needed — all other DAO data fetching uses existing JNI queries (`nativeGetCells`, `nativeGetHeader`, `nativeSendTransaction`).

### Rationale for JNI vs Kotlin

The compensation formula uses u128 arithmetic for AR (Accumulated Rate) ratios. The epoch-based since value calculation involves fractional epoch arithmetic and 180-epoch cycle boundary rounding. Both are easy to get wrong in Kotlin and already implemented correctly in `ckb-dao-utils` (Rust). Display-only APC calculation can stay in Kotlin since it's approximate.

### Files to Modify

#### `external/ckb-light-client/light-client-lib/src/jni_bridge/mod.rs`

Add new module import:
```
pub mod dao;
pub use dao::*;
```

#### `external/ckb-light-client/light-client-lib/src/jni_bridge/query.rs`

Reference for JNI function pattern: `to_jstring()` helper at line 37, `check_running!` macro at line 27.

### Files to Create

#### `external/ckb-light-client/light-client-lib/src/jni_bridge/dao.rs`

Three JNI functions following the same pattern as `query.rs`:

**`nativeExtractDaoFields(daoHex: String) → String`**
- Parse 32-byte hex DAO header field into 4 u64 values (C, AR, S, U)
- Little-endian byte order: bytes 0-8 = C, 8-16 = AR, 16-24 = S, 24-32 = U
- Return JSON: `{"c":"0x...","ar":"0x...","s":"0x...","u":"0x..."}`
- Uses `ckb_dao_utils::extract_dao_data()` or manual byte parsing

**`nativeCalculateMaxWithdraw(depositHeaderDaoHex, withdrawHeaderDaoHex, depositCapacity, occupiedCapacity) → Long`**
- Formula: `(capacity - occupied) * AR_withdraw / AR_deposit + occupied`
- AR values extracted from dao header fields
- Returns max withdrawable shannons (deposit + compensation)
- Uses `ckb_dao_utils` calculation functions

**`nativeCalculateUnlockEpoch(depositEpochHex, withdrawEpochHex) → String`**
- Parse epoch hex values into `EpochNumberWithFraction`
- Calculate: `depositedEpochs = withdrawEpoch - depositEpoch`
- Round up to next 180-epoch boundary: `ceil(depositedEpochs / 180) * 180`
- Encode as absolute epoch since value with flags
- Return hex string for use in `CellInput.since`

### Files to Modify

#### `LightClientNative.kt` (after line 190, before the Callback Interfaces section)

Add 3 new external fun declarations:
```kotlin
external fun nativeExtractDaoFields(daoHex: String): String?
external fun nativeCalculateMaxWithdraw(
    depositHeaderDaoHex: String,
    withdrawHeaderDaoHex: String,
    depositCapacity: Long,
    occupiedCapacity: Long
): Long
external fun nativeCalculateUnlockEpoch(
    depositEpochHex: String,
    withdrawEpochHex: String
): String?
```

### Testing

- Test `nativeExtractDaoFields` with known DAO header hex values from CKB explorer
- Test `nativeCalculateMaxWithdraw` with known deposit/withdraw headers and verify against CKB SDK reference values
- Test `nativeCalculateUnlockEpoch` with various epoch combinations:
  - Deposit at epoch 100, withdraw at epoch 150 → unlock at epoch 280 (100 + 180)
  - Deposit at epoch 100, withdraw at epoch 350 → unlock at epoch 460 (100 + 360)
  - Edge: withdraw right at 180-epoch boundary

---

## Feature 3: Repository & Transaction Builder — DAO Operations

### Overview

Add 4 new DAO functions to `GatewayRepository` and 3 new tx builder methods to `TransactionBuilder`. These handle the complete DAO lifecycle: querying deposits, building deposit/withdraw/unlock transactions.

### Nervos DAO Transaction Structures (from RFC-0023)

**Deposit Transaction:**
- Cell deps: secp256k1 dep_group + DAO code dep
- Inputs: normal CKB cells (gathered to cover deposit amount + fee)
- Outputs[0]: `capacity` = deposit amount, `lock` = user's lock script, `type` = DAO type script, `data` = `0x0000000000000000`
- Outputs[1]: change (if >= 61 CKB)
- Header deps: none
- Witnesses: standard secp256k1 signing

**Phase 1 Withdraw Transaction:**
- Cell deps: secp256k1 dep_group + DAO code dep
- Inputs[0]: the DAO deposit cell
- Outputs[0]: same capacity, same lock, same type script, `data` = deposit block number as 8-byte little-endian
- Header deps: `[depositBlockHash]`
- Witnesses: standard secp256k1 signing

**Phase 2 Unlock Transaction:**
- Cell deps: secp256k1 dep_group + DAO code dep
- Inputs[0]: the withdrawing cell, `since` = absolute epoch value (from `nativeCalculateUnlockEpoch`)
- Outputs[0]: `capacity` = maxWithdraw - fee, NO type script, `data` = `0x`, user's lock
- Header deps: `[depositBlockHash, withdrawBlockHash]`
- Witnesses: lock field = 65-byte signature, input_type = null, output_type = 8-byte little-endian index of deposit header in header_deps (0x0000000000000000 = index 0)

### DAO Cell Querying Strategy

Current `getCells()` in `GatewayRepository.kt:605-657` uses `JniSearchKey` with `script_type: "lock"`. For DAO cells, we need to query by `script_type: "type"` with the DAO type script. This requires constructing a `JniSearchKey` with:
- `script` = DAO type script (code_hash, hash_type, args)
- `scriptType` = "type"
- `filter` = `JniSearchKeyFilter(script = userLockScript)` to limit results to user's cells
- `withData` = true (needed to distinguish deposit vs withdrawing cells)

The cell data distinguishes states:
- `0x0000000000000000` = deposit cell (8 zero bytes)
- `0x` + 16 hex chars (8-byte LE block number) = withdrawing cell

### Files to Modify

#### `GatewayRepository.kt` (add after `sendTransaction()` at ~line 719)

**`getDaoDeposits(): Result<List<DaoDeposit>>`**
1. Build `JniSearchKey` with DAO type script, `scriptType = "type"`, filter by user lock script
2. `nativeGetCells(...)` → parse `JniPagination<JniCell>`
3. Filter out spent cells (same pattern as existing `getCells()` at lines 611-649)
4. For each live DAO cell:
   a. Determine if deposit or withdrawing from cell data
   b. Fetch deposit block header via `nativeGetHeader` → extract epoch, dao field
   c. If withdrawing: fetch withdraw block header too
   d. Call `nativeCalculateMaxWithdraw` → compute compensation
   e. Call `nativeCalculateUnlockEpoch` if withdrawing → compute unlock epoch
   f. Get current epoch from tip header
   g. Run `determineDaoStatus()` to assign 7-state status
   h. Compute `compensationCycleProgress` and `cyclePhase`
5. Return sorted list of `DaoDeposit`

**`getDaoOverview(): Result<DaoOverview>`**
- Aggregate from `getDaoDeposits()`: sum locked, sum compensation, count active/completed
- APC calculation from genesis timestamp and current tip timestamp (approximate, display-only)

**`getCurrentEpoch(): Result<EpochInfo>`**
- Parse epoch from `nativeGetTipHeader()` response (`JniHeaderView.epoch`)

**`depositToDao(amountShannons: Long): Result<String>`**
1. Validate: amount >= 102 CKB (MIN_DEPOSIT_SHANNONS)
2. Get user's available cells (existing `getCells()`)
3. Call `TransactionBuilder.buildDaoDeposit(...)` → unsigned tx
4. Sign → `sendTransaction()` → return tx hash

**`withdrawFromDao(depositOutPoint: OutPoint): Result<String>`**
1. Look up the deposit cell from known deposits
2. Fetch deposit block header (for header_deps and block number)
3. Call `TransactionBuilder.buildDaoWithdraw(...)` → unsigned tx
4. Sign → `sendTransaction()` → return tx hash

**`unlockDao(withdrawingOutPoint, depositBlockHash, withdrawBlockHash): Result<String>`**
1. Fetch both block headers
2. `nativeCalculateMaxWithdraw(...)` → max withdrawable capacity
3. `nativeCalculateUnlockEpoch(...)` → since value
4. Call `TransactionBuilder.buildDaoUnlock(...)` → unsigned tx
5. Sign → `sendTransaction()` → return tx hash

#### `TransactionBuilder.kt` (add after `buildTransfer()` at ~line 156)

**`buildDaoDeposit(amount, availableCells, senderScript, privateKey, network): Transaction`**
- Same cell selection pattern as `buildTransfer()` (lines 62-93)
- Output[0]: capacity = amount, lock = sender, type = DAO type script, data = 8 zero bytes
- Output[1]: change if >= 61 CKB
- Cell deps: secp256k1 dep_group + DAO code dep (2 deps, not 1)
- Header deps: empty
- Sign with existing `signTransaction()`

**`buildDaoWithdraw(depositCell, depositBlockNumber, depositBlockHash, senderScript, privateKey, network): Transaction`**
- Input: the deposit cell
- Output: mirror capacity/lock/type, data = depositBlockNumber as 8-byte LE hex
- Cell deps: secp256k1 + DAO
- Header deps: `[depositBlockHash]`
- Need additional input cells if fee > 0 (to pay tx fee from non-DAO cells)
- Sign with `signTransaction()`

**`buildDaoUnlock(withdrawingCell, maxWithdraw, sinceValue, depositBlockHash, withdrawBlockHash, fee, senderScript, privateKey, network): Transaction`**
- Input: withdrawing cell with `since = sinceValue`
- Output: capacity = maxWithdraw - fee, lock = sender, NO type script, data = `0x`
- Cell deps: secp256k1 + DAO
- Header deps: `[depositBlockHash, withdrawBlockHash]`
- Witness: lock = signature, output_type = `0x0000000000000000` (8-byte LE index 0 pointing to deposit header in header_deps)
- Requires modification to `signTransaction()` to accept `outputType` parameter for WitnessArgs (currently passes `null` at line 226)
- `serializeWitnessArgs(signature, null, outputType)` at line 226 needs the outputType bytes

### Testing

- Unit test `buildDaoDeposit`: verify output type script = DAO, data = 8 zero bytes, 2 cell deps
- Unit test `buildDaoWithdraw`: verify data = block number LE, header_deps present
- Unit test `buildDaoUnlock`: verify since value, header_deps order, witness output_type field
- Integration test: full deposit → withdraw → unlock cycle on testnet
- Edge cases: deposit exactly 102 CKB, deposit with reserve, insufficient balance

---

## Feature 4: DaoViewModel — State Management & Polling

### Overview

ViewModel with adaptive polling that drives the DAO dashboard. Modeled after `HomeViewModel` (5s/30s polling) and `SendViewModel` (3s tx confirmation polling).

### Files to Create

#### `ui/screens/dao/DaoViewModel.kt`

**@HiltViewModel with DaoUiState StateFlow.**

**Polling strategy** (adapted from Neuron's 10s interval):
- Default: 30s polling for `getDaoDeposits()` refresh
- When `pendingAction != null`: 10s polling (faster to detect tx confirmation)
- Started in `init {}` via `viewModelScope.launch`

**User actions:**
- `deposit(amountShannons: Long)` — sets `pendingAction = DaoAction.Depositing(amount)`, calls `repository.depositToDao()`, clears pendingAction on failure
- `withdraw(deposit: DaoDeposit)` — sets `pendingAction = DaoAction.Withdrawing(outPoint)`, calls `repository.withdrawFromDao()`
- `unlock(deposit: DaoDeposit)` — sets `pendingAction = DaoAction.Unlocking(outPoint)`, calls `repository.unlockDao()`
- `selectTab(tab: DaoTab)` — switches Active/Completed tab
- `clearError()` — clears error from state

**Pending action resolution** (auto-clear when tx confirms):
- `Depositing` → clears when a new DEPOSITED cell appears
- `Withdrawing` → clears when target cell transitions to LOCKED or UNLOCKABLE
- `Unlocking` → clears when target cell reaches COMPLETED or disappears

**Error handling:**
- Repository failures → `_uiState.update { it.copy(error = e.message) }`
- Shown via Snackbar in DaoScreen

### Testing

- Unit test: pending action resolution logic
- Unit test: polling interval switches between 10s/30s based on pendingAction

---

## Feature 5: DAO Dashboard UI — Visualization

### Overview

Replace the current placeholder `DaoScreen.kt` (lines 28-91) with a full DAO dashboard. No new navigation routes needed — the screen lives within the existing `BottomTab.DAO` in `MainScreen.kt:99-101`.

### Files to Create

#### `ui/screens/dao/DaoScreen.kt` (full rewrite, replaces placeholder)

**Layout structure:**

```
Scaffold
├── Overview Card (Surface, dark card #1A1A1A)
│   ├── "Nervos DAO" title
│   ├── Locked total (large text)
│   ├── Total compensation (green #1ED882 accent)
│   ├── Current APC percentage (subtitle)
│   └── Deposit button (primary, full-width)
│
├── TabRow (Active / Completed with counts)
│
└── LazyColumn
    ├── DaoDepositCard (per active deposit) ... or
    └── Empty state (when no deposits)
```

**Empty state** (when no deposits exist):
- Lock icon, "Earn rewards with Nervos DAO" heading
- "Deposit CKB to earn ~2.5% annual compensation. 180-epoch lock cycles."
- "Make First Deposit" button → opens deposit bottom sheet

**Deposit bottom sheet** (`ModalBottomSheet`):
- Amount input field with available balance display
- Min 102 CKB validation label
- Reserve 62 CKB checkbox (prevents locking entire balance)
- Estimated rewards: 30-day and 360-day projections based on current APC
- Cancel / Deposit buttons
- Expandable "Nervos DAO Rules" info section (4 rules from Neuron: min 102 CKB, 180-epoch cycle, withdraw anytime, unlock after cycle)

**Withdraw confirmation** (`AlertDialog`):
- Shows deposit amount and earned compensation
- Warning about remaining lock time: "Your funds will remain locked until the current 180-epoch cycle ends (~X days Y hours)"
- Cancel / Withdraw buttons

**Unlock confirmation** (`AlertDialog`):
- Shows deposit amount, compensation earned, total received, fee deduction
- Cancel / Unlock buttons

#### `ui/screens/dao/components/DaoDepositCard.kt`

Per-deposit record card:
- Amount in CKB + compensation earned ("+X.XX CKB" in green)
- Status badge (colored by DaoCellStatus, see table below)
- `CompensationProgressBar` showing epoch cycle progress
- Countdown text: "Unlockable in Xd Yh" or "Ready to unlock!"
- Action button: Withdraw (DEPOSITED) / Unlock (UNLOCKABLE) / disabled spinner (pending states) / disabled with countdown (LOCKED)

**Status badge colors:**

| Status | Color | Label |
|--------|-------|-------|
| DEPOSITING | amber (#F59E0B) | "Depositing..." |
| DEPOSITED | green (#1ED882) | "Active" |
| WITHDRAWING | amber (#F59E0B) | "Withdrawing..." |
| LOCKED | gray (#A0A0A0) | "Locked — Xd Yh" |
| UNLOCKABLE | bright green (#1ED882) | "Ready to Unlock" |
| UNLOCKING | amber (#F59E0B) | "Unlocking..." |
| COMPLETED | muted green (#4ADE80) | "Completed" |

#### `ui/screens/dao/components/CompensationProgressBar.kt`

Custom `Canvas` composable showing the progress through the current 180-epoch compensation cycle.

Parameters: `progress: Float` (0.0-1.0), `phase: CyclePhase`

3-zone coloring (from Neuron's `CompensationProgressBar`):
- NORMAL (0-80%): green (#1ED882)
- SUGGESTED (80-95%): amber (#F59E0B)
- ENDING (95-100%): red (#EF4444)

Track: dark gray (#252525). Rounded end cap on progress indicator.

#### `ui/screens/dao/components/DepositBottomSheet.kt`

Extracted composable for the deposit flow. Contains the amount input, validation, reserve checkbox, reward estimates, and rules section.

### Files to Modify

#### `DaoScreen.kt` (full rewrite)

Replace entire placeholder (lines 28-91) with the dashboard described above.

#### `MainScreen.kt:99-101`

Update composable to pass `hiltViewModel()`:
```kotlin
composable(BottomTab.DAO.route) {
    DaoScreen(viewModel = hiltViewModel())
}
```

#### `MainScreen.kt:117`

Consider changing DAO tab icon from `Icons.Filled.Lock` to `Icons.Filled.AccountBalance` or keep Lock — either works.

#### `HomeScreen.kt:774-800` (Stake button)

Enable the Stake button:
- Change `enabled = false` to `enabled = true` (line 777)
- Change `onClick = {}` to navigate to DAO tab (line 776)
- Remove the M2 badge overlay (lines 785-800)

#### `HomeScreen.kt` (composable parameters)

Add `onNavigateToDao: () -> Unit` callback parameter. Wire to Stake button onClick.

#### `MainScreen.kt`

Pass `onNavigateToDao` callback from MainScreen to HomeScreen that programmatically selects BottomTab.DAO.

### Testing

- Manual: verify overview card displays correct totals
- Manual: verify deposit bottom sheet validation (< 102 CKB rejected, > available rejected)
- Manual: verify status badges cycle through all 7 states during deposit→withdraw→unlock flow
- Manual: verify compensation progress bar renders 3 zones correctly
- Manual: verify empty state shows on fresh wallet with no DAO deposits

---

## Implementation Schedule

### Week 1: JNI Layer + Data Models + Repository

| Day | Task |
|-----|------|
| 1 | Create `DaoConstants.kt` and `DaoModels.kt` — all data models, enums, status determination logic |
| 2 | Implement 3 Rust JNI functions in `dao.rs` — `nativeExtractDaoFields`, `nativeCalculateMaxWithdraw`, `nativeCalculateUnlockEpoch` |
| 3 | Wire JNI: add `dao.rs` to `mod.rs`, add 3 `external fun` declarations to `LightClientNative.kt`, verify Cargo build compiles |
| 4 | Implement `GatewayRepository.getDaoDeposits()` — DAO cell fetch, header resolution, status determination, compensation calc |
| 5 | Implement `GatewayRepository.getDaoOverview()` and `getCurrentEpoch()`, APC calculation utility |
| 6 | Implement `TransactionBuilder.buildDaoDeposit()` and `GatewayRepository.depositToDao()` |
| 7 | Implement `buildDaoWithdraw()`, `buildDaoUnlock()`, `withdrawFromDao()`, `unlockDao()`, modify `signTransaction()` for outputType witness |

### Week 2: ViewModel + Dashboard UI → v1.3.0

| Day | Task |
|-----|------|
| 1 | Implement `DaoViewModel` — state management, adaptive polling (10s/30s), user actions, pending action resolution |
| 2 | Rewrite `DaoScreen.kt` — overview card, empty state, Scaffold layout |
| 3 | Build `DaoDepositCard.kt` — deposit record card with status badges and action buttons |
| 4 | Build `CompensationProgressBar.kt` (3-zone Canvas) + countdown display logic |
| 5 | Build `DepositBottomSheet.kt` — amount input, validation (102 CKB min), reserve checkbox |
| 6 | Build withdraw/unlock `AlertDialog` confirmations, wire all actions through ViewModel |
| 7 | **Release v1.3.0**: DAO deposit, withdraw, unlock flows functional on both networks |

### Week 3: Polish + Economics + Testing

| Day | Task |
|-----|------|
| 1 | Active/Completed tab filtering, sort deposits by deposit time |
| 2 | Pending action tracking — spinners on cards, adaptive polling confirmed working |
| 3 | Error handling: insufficient balance, lock period not met, tx failures, Snackbar display |
| 4 | Reward estimates in deposit sheet (30-day / 360-day projections from current APC) |
| 5 | Enable Home "Stake" button — wire onClick to navigate to DAO tab, remove M2 badge |
| 6 | Testnet end-to-end: deposit → wait → withdraw → wait → unlock with various amounts (102, 200, 500, 1000 CKB) |
| 7 | Mainnet testing with small deposits (102-200 CKB) |

### Week 4: Edge Cases + Documentation → v1.4.0

| Day | Task |
|-----|------|
| 1 | Edge case testing: deposit exactly 102 CKB, deposit entire balance with reserve, multiple concurrent deposits |
| 2 | Edge case: withdraw timing at cycle boundaries, attempt unlock before maturity (verify graceful failure) |
| 3 | Network switching: verify DAO cells are network-isolated, correct cell deps per network, no cross-network leakage |
| 4 | Version bump to v1.4.0, update CLAUDE.md with DAO section and key file references |
| 5 | Battery/performance profiling with DAO polling active alongside existing sync polling |
| 6 | DAO rules info section — expandable in deposit sheet with link to RFC-0023 |
| 7 | **Release v1.4.0**: Polished DAO with economics, edge cases handled, both networks tested |

---

## Release Artifacts

### v1.3.0 (Week 6)
- 3 new JNI functions for DAO computation
- Full DAO dashboard replacing "Coming in M2" placeholder
- Deposit, withdraw (phase 1), unlock (phase 2) transaction flows
- 7-state status tracking with real-time adaptive polling
- Compensation progress bar with countdown timers
- Both mainnet and testnet support
- Multiple concurrent deposits

### v1.4.0 (Week 8)
- Reward estimates (30-day / 360-day) in deposit sheet
- Home screen "Stake" button enabled and wired to DAO tab
- Edge case handling and comprehensive error states
- Testing across various deposit amounts and lock periods
- Performance-validated polling strategy
- DAO rules info section

---

## Dependencies Summary

### No New Dependencies

All DAO functionality uses existing libraries:

| Library | Purpose in M2 |
|---------|---------------|
| `ckb-dao-utils` (Rust, already in Cargo.toml line 32) | JNI compensation calculation, AR extraction |
| `ckb-dao` (Rust, already in Cargo.toml line 33) | DAO protocol types |
| `ckb-types` (Rust, already in Cargo.toml line 21) | Epoch parsing, packed types |
| `TransactionBuilder` (Kotlin, existing) | DAO tx assembly, molecule encoding, signing |
| `kotlinx.serialization` (Kotlin, existing) | JNI response parsing |
| Compose + Material 3 (existing) | Dashboard UI |

---

## New Files Summary

| File | Feature |
|------|---------|
| `data/gateway/DaoConstants.kt` | Protocol constants, network-aware cell dep helper |
| `data/gateway/models/DaoModels.kt` | DaoDeposit, EpochInfo, DaoOverview, DaoCellStatus, CyclePhase, DaoUiState |
| `ui/screens/dao/DaoViewModel.kt` | State management, adaptive polling, user actions |
| `ui/screens/dao/DaoScreen.kt` | Full rewrite replacing placeholder |
| `ui/screens/dao/components/DaoDepositCard.kt` | Per-deposit record card |
| `ui/screens/dao/components/CompensationProgressBar.kt` | 3-zone epoch progress Canvas |
| `ui/screens/dao/components/DepositBottomSheet.kt` | Deposit flow with validation |
| `external/ckb-light-client/light-client-lib/src/jni_bridge/dao.rs` | 3 Rust JNI function implementations |

## Modified Files Summary

| File | Changes |
|------|---------|
| `LightClientNative.kt:190` | 3 new `external fun` declarations (after `callRpc`) |
| `GatewayRepository.kt:~719` | 4 new DAO suspend functions after `sendTransaction()`, APC utility |
| `TransactionBuilder.kt:~156` | 3 new DAO tx builder methods after `buildTransfer()`, modify `signTransaction()` for outputType witness |
| `HomeScreen.kt:774-800` | Enable Stake button, remove M2 badge, add onNavigateToDao callback |
| `MainScreen.kt:99-101` | Pass hiltViewModel() to DaoScreen, wire Stake navigation callback |
| `jni_bridge/mod.rs:25-41` | Add `pub mod dao; pub use dao::*;` |
| `build.gradle.kts:20-21` | Version bumps (1.3.0, 1.4.0) |

---

## Key Technical Risks

1. **Rust JNI compilation**: New `dao.rs` must compile for all 4 Android ABIs (ARM64, ARMV7, x86, x86_64). Mitigation: test Cargo build early (Week 1, Day 3).

2. **AR precision**: Accumulated Rate uses u128 multiplication. Mitigation: delegated to Rust `ckb-dao-utils` (not reimplemented in Kotlin).

3. **Since value encoding**: Phase 2 unlock requires precise absolute epoch encoding with flags. Mitigation: delegated to Rust `nativeCalculateUnlockEpoch`.

4. **WitnessArgs outputType**: Phase 2 requires `output_type` field in witness (currently always `null` in `signTransaction()`). Mitigation: extend `signTransaction()` with optional `outputType` parameter — low risk, `serializeWitnessArgs` already supports it at line 409-418.

5. **DAO cell query**: Requires `script_type: "type"` in search key (current code only uses `"lock"`). Mitigation: `JniSearchKey` already supports `scriptType` parameter — just change the value.

6. **Network switching**: DAO cells use different cell dep tx hashes per network. Mitigation: `DaoConstants.daoCellDep(network)` handles this, same pattern as existing secp256k1 cell deps.
