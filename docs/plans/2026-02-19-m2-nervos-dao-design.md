# M2 Design: Nervos DAO Protocol Integration

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:writing-plans` to create the implementation plan from this design, then `superpowers:executing-plans` to implement task-by-task.

**Goal:** Full Nervos DAO integration — deposit, two-phase withdrawal (withdraw + unlock), real-time compensation tracking, APY display, and a dashboard with countdown timers and maturity status. Replaces the current "Coming in M2" placeholder.

**Timeline**: 4 weeks (Month 2 of grant)
**Budget**: $3,375 (22.5% of grant)
**Releases**: v1.3.0 (Week 6), v1.4.0 (Week 8)
**Networks**: Both mainnet and testnet
**Reference**: [Nervos DAO forum proposal](https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879)

**Accepted Deliverables** (from DAO proposal):

1. DAO Transaction Builder: specialized DAO outputs (Deposit, Withdrawal, Unlocking)
2. DAO Lifecycle Management: two-phase unlock (withdraw -> wait for maturity -> unlock)
3. In-App Economics: real-time compensation tracking and APY calculation
4. Visualization: DAO dashboard with lock countdown timers and maturity status
5. Testing with various deposit amounts and lock periods

---

## Architecture

Approach A: Full JNI Extension. Three new Rust JNI functions for compensation/epoch math (uses `ckb-dao-utils` already in Cargo). Kotlin handles tx assembly, UI, and state. Modeled after [Neuron wallet](https://github.com/nervosnetwork/neuron) patterns.

```
DaoScreen (Compose UI)
    |
    v
DaoViewModel (7-state machine, adaptive 10s/30s polling)
    |
    v
GatewayRepository (new DAO suspend functions)
    |
    +---> TransactionBuilder (deposit/withdraw/unlock tx assembly)
    +---> LightClientNative (existing queries + 3 new JNI functions)
    +---> DaoConstants (code hash, cell dep per network)
```

Data flow per operation:

| Operation | Flow |
|-----------|------|
| List deposits | `nativeGetCells(daoTypeScript)` -> filter by cell data -> fetch headers -> compensation calc -> `DaoDeposit` list |
| Deposit CKB | User enters amount -> `TransactionBuilder` assembles tx with DAO type script + 8-byte zero data -> sign -> `nativeSendTransaction` |
| Withdraw (Phase 1) | Select deposit -> fetch deposit header -> build tx converting deposit cell to withdrawing cell (data = deposit block#) -> sign -> send |
| Unlock (Phase 2) | Select mature withdrawing cell -> fetch both headers -> `nativeCalculateMaxWithdraw` for output capacity -> build tx with `since` epoch lock -> sign -> send |

---

## Nervos DAO Protocol Constants

```kotlin
object DaoConstants {
    // Same code hash on both networks
    const val DAO_CODE_HASH = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e"
    const val DAO_HASH_TYPE = "type"
    const val DAO_ARGS = "0x"

    // Cell dep tx hashes differ per network
    const val DAO_CELL_DEP_TX_MAINNET = "0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c"
    const val DAO_CELL_DEP_TX_TESTNET = "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f"
    const val DAO_CELL_DEP_INDEX = "0x2"

    const val WITHDRAW_EPOCHS = 180          // One compensation cycle
    const val HOURS_PER_EPOCH = 4            // ~4 hours per CKB epoch
    const val MIN_DEPOSIT_SHANNONS = 10_200_000_000L  // 102 CKB
    const val RESERVE_SHANNONS = 6_200_000_000L       // 62 CKB for future fees

    val DAO_DEPOSIT_DATA = ByteArray(8)      // 8 zero bytes
}
```

Sources:
- [RFC-0023: DAO Deposit and Withdraw](https://docs.ckb.dev/docs/rfcs/0023-dao-deposit-withdraw/0023-dao-deposit-withdraw)
- [RFC-0024: Genesis Script List](https://github.com/nervosnetwork/rfcs/blob/master/rfcs/0024-ckb-genesis-script-list/0024-ckb-genesis-script-list.md)

---

## Data Models

### DAO Cell Status -- 7-state enum (from Neuron)

```kotlin
enum class DaoCellStatus {
    DEPOSITING,    // deposit tx sent, awaiting confirmation
    DEPOSITED,     // confirmed on-chain, withdraw available
    WITHDRAWING,   // phase 1 tx sent, awaiting confirmation
    LOCKED,        // phase 1 confirmed, lock period not met
    UNLOCKABLE,    // lock period met, ready to unlock
    UNLOCKING,     // phase 2 tx sent, awaiting confirmation
    COMPLETED      // fully unlocked, funds returned
}
```

Status determination priority (from Neuron): unlock path > withdraw path > deposit path.

```kotlin
fun determineDaoStatus(
    cell: Cell,
    hasPendingWithdraw: Boolean,
    hasPendingUnlock: Boolean,
    currentEpoch: EpochInfo,
    unlockEpoch: EpochInfo?
): DaoCellStatus = when {
    hasPendingUnlock            -> DaoCellStatus.UNLOCKING
    unlockEpoch != null && currentEpoch.value >= unlockEpoch.value
                                -> DaoCellStatus.UNLOCKABLE
    unlockEpoch != null         -> DaoCellStatus.LOCKED
    hasPendingWithdraw          -> DaoCellStatus.WITHDRAWING
    isWithdrawingCell(cell)     -> DaoCellStatus.LOCKED  // phase 1 confirmed
    isPendingDeposit(cell)      -> DaoCellStatus.DEPOSITING
    else                        -> DaoCellStatus.DEPOSITED
}
```

### Core Data Classes

```kotlin
data class DaoDeposit(
    val outPoint: OutPoint,
    val capacity: Long,                   // deposited shannons
    val status: DaoCellStatus,
    val depositBlockNumber: Long,
    val depositBlockHash: String,
    val depositEpoch: EpochInfo,
    val withdrawBlockNumber: Long?,       // null if still deposited
    val withdrawBlockHash: String?,
    val withdrawEpoch: EpochInfo?,
    val compensation: Long,               // earned shannons
    val unlockEpoch: EpochInfo?,          // earliest epoch for phase 2
    val lockRemainingHours: Int?,
    val compensationCycleProgress: Float, // 0.0-1.0
    val cyclePhase: CyclePhase
)

data class EpochInfo(
    val number: Long,
    val index: Long,
    val length: Long
) {
    val value: Double get() = number + index.toDouble() / length
}

enum class CyclePhase { NORMAL, SUGGESTED, ENDING }

data class DaoOverview(
    val totalLocked: Long,
    val totalCompensation: Long,
    val currentApc: Double,
    val activeCount: Int,
    val completedCount: Int
)
```

### UI State

```kotlin
data class DaoUiState(
    val overview: DaoOverview = DaoOverview(0L, 0L, 0.0, 0, 0),
    val activeDeposits: List<DaoDeposit> = emptyList(),
    val completedDeposits: List<DaoDeposit> = emptyList(),
    val selectedTab: DaoTab = DaoTab.ACTIVE,
    val isLoading: Boolean = true,
    val error: String? = null,
    val pendingAction: DaoAction? = null
)

enum class DaoTab { ACTIVE, COMPLETED }

sealed class DaoAction {
    data class Depositing(val amount: Long) : DaoAction()
    data class Withdrawing(val outPoint: OutPoint) : DaoAction()
    data class Unlocking(val outPoint: OutPoint) : DaoAction()
}
```

---

## JNI Layer -- 3 New Rust Functions

### Declarations in `LightClientNative.kt`

```kotlin
// Extract C, AR, S, U from 32-byte dao header field
external fun nativeExtractDaoFields(daoHex: String): String
// Returns JSON: {"c":"0x...","ar":"0x...","s":"0x...","u":"0x..."}

// Calculate max withdrawable capacity (deposit + compensation)
external fun nativeCalculateMaxWithdraw(
    depositHeaderDaoHex: String,
    withdrawHeaderDaoHex: String,
    depositCapacity: Long,
    occupiedCapacity: Long
): Long

// Calculate the since value (absolute epoch) for phase 2 unlock
external fun nativeCalculateUnlockEpoch(
    depositEpochHex: String,
    withdrawEpochHex: String
): String
```

### Rust Implementation Outline

Uses existing `ckb-dao-utils` crate (already in Cargo deps):

```rust
use ckb_dao_utils::extract_dao_data;
use ckb_types::core::EpochNumberWithFraction;

#[no_mangle]
pub extern "C" fn Java_..._nativeExtractDaoFields(...)  -> jstring
// parse 32-byte dao field -> JSON {c, ar, s, u}

#[no_mangle]
pub extern "C" fn Java_..._nativeCalculateMaxWithdraw(...) -> jlong
// AR_w / AR_d * (capacity - occupied) + occupied

#[no_mangle]
pub extern "C" fn Java_..._nativeCalculateUnlockEpoch(...) -> jstring
// round up to next 180-epoch boundary -> since value
```

### Why Only 3 Functions

| What | Handled by | Reason |
|------|-----------|--------|
| List DAO cells | Existing `nativeGetCells` | Already works with type script filter |
| Fetch headers | Existing `nativeGetHeader` | Already works |
| Send transactions | Existing `nativeSendTransaction` | Already works |
| AR extraction / compensation | **New JNI** | u128 precision, reference impl |
| Since epoch calculation | **New JNI** | Epoch fraction arithmetic |
| TX assembly | Kotlin `TransactionBuilder` | Already supports arbitrary tx types |
| APC display | Kotlin utility | Display-only, approximate |

---

## Repository & Transaction Builder

### New GatewayRepository Functions

```kotlin
// Query
suspend fun getDaoDeposits(): Result<List<DaoDeposit>>
suspend fun getDaoOverview(): Result<DaoOverview>
suspend fun getCurrentEpoch(): Result<EpochInfo>

// Transactions
suspend fun depositToDao(amountShannons: Long): Result<String>   // returns tx hash
suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String>
suspend fun unlockDao(
    withdrawingOutPoint: OutPoint,
    depositBlockHash: String,
    withdrawBlockHash: String
): Result<String>
```

### getDaoDeposits() Flow

1. `nativeGetCells(daoTypeScript)` -> raw DAO cells
2. For each cell: inspect data (8 zeros = deposited, block# = withdrawing)
3. Fetch headers via `nativeGetHeader` for deposit/withdraw blocks
4. Call `nativeCalculateMaxWithdraw` for compensation
5. Call `nativeCalculateUnlockEpoch` for lock status
6. `determineDaoStatus()` for 7-state resolution
7. Split into active vs completed lists

### TransactionBuilder Extensions

```kotlin
// Deposit: DAO type script + 8-byte zero data
fun buildDaoDepositOutput(capacity: Long, lockScript: Script, network: NetworkType): CellOutput

// Phase 1: mirror deposit cell, data = block# as 8-byte LE, header_deps = [depositBlockHash]
fun buildDaoWithdrawTx(depositCell: Cell, depositBlockNumber: Long,
    depositBlockHash: String, network: NetworkType): RawTransaction

// Phase 2: capacity = maxWithdraw - fee, no type script, since = epoch lock,
//   header_deps = [depositBlockHash, withdrawBlockHash],
//   witness type = deposit header index as 8-byte LE
fun buildDaoUnlockTx(withdrawingCell: Cell, maxWithdraw: Long, sinceValue: String,
    depositBlockHash: String, withdrawBlockHash: String,
    fee: Long, network: NetworkType): RawTransaction
```

### Network-Aware Cell Dep

```kotlin
fun daoCellDep(network: NetworkType): CellDep = CellDep(
    outPoint = OutPoint(
        txHash = when (network) {
            NetworkType.MAINNET -> DAO_CELL_DEP_TX_MAINNET
            NetworkType.TESTNET -> DAO_CELL_DEP_TX_TESTNET
        },
        index = DAO_CELL_DEP_INDEX
    ),
    depType = "code"
)
```

---

## DaoViewModel

```kotlin
@HiltViewModel
class DaoViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaoUiState())
    val uiState: StateFlow<DaoUiState> = _uiState.asStateFlow()

    init { startPolling() }

    // Adaptive polling: 10s with pending actions, 30s otherwise
    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refreshDaoData()
                delay(if (_uiState.value.pendingAction != null) 10_000L else 30_000L)
            }
        }
    }

    fun deposit(amountShannons: Long) { /* set pendingAction, call repository */ }
    fun withdraw(deposit: DaoDeposit) { /* set pendingAction, call repository */ }
    fun unlock(deposit: DaoDeposit) { /* set pendingAction, call repository */ }
    fun selectTab(tab: DaoTab) { /* update state */ }
    fun clearError() { /* update state */ }
}
```

Pending actions auto-clear when polling detects state transition (e.g., DEPOSITING -> DEPOSITED).

---

## DAO Dashboard UI

### Screen Layout (replaces placeholder DaoScreen.kt)

```
Scaffold
|
+-- Overview Card (sticky)
|   +-- "Nervos DAO" title
|   +-- Locked: "500.00 CKB"
|   +-- Compensation: "+2.34 CKB" (green)
|   +-- APC: "~2.5%"
|   +-- [ Deposit ] button
|
+-- Tab Row: Active (N) | Completed (N)
|
+-- LazyColumn
    +-- DaoDepositCard (per record)
        +-- Amount + compensation
        +-- Status badge (colored)
        +-- CompensationProgressBar (3-zone Canvas)
        +-- Countdown text
        +-- Action button (Withdraw / Unlock / spinner)
```

### Deposit Bottom Sheet (ModalBottomSheet)

- Amount input with available balance display
- Min 102 CKB validation
- Reserve 62 CKB checkbox (prevents locking everything)
- Est. 30-day / 360-day reward projections
- Expandable "Nervos DAO Rules" info section

### Withdraw/Unlock Confirmation (AlertDialog)

- Shows deposit amount, earned compensation, fees
- Withdraw dialog: warns about remaining lock time
- Unlock dialog: shows total amount received

### CompensationProgressBar (Custom Canvas Composable)

3-zone coloring per Neuron:
- NORMAL (0-80%): green (#1ED882)
- SUGGESTED (80-95%): amber (#F59E0B)
- ENDING (95-100%): red (#EF4444)

### Status Badge Colors

| Status | Color | Label |
|--------|-------|-------|
| DEPOSITING | amber | "Depositing..." |
| DEPOSITED | green | "Active" |
| WITHDRAWING | amber | "Withdrawing..." |
| LOCKED | gray | "Locked -- Xd Yh" |
| UNLOCKABLE | bright green | "Ready to Unlock" |
| UNLOCKING | amber | "Unlocking..." |
| COMPLETED | muted green | "Completed" |

### Empty State

Lock icon + "Earn rewards with Nervos DAO" message + "Make First Deposit" button.

---

## Implementation Schedule

### Week 1: JNI + Data Models + Repository

| Day | Task |
|-----|------|
| 1 | `DaoConstants.kt`, data models, `DaoCellStatus` enum |
| 2 | 3 Rust JNI functions (extract, maxWithdraw, unlockEpoch) |
| 3 | JNI declarations in `LightClientNative.kt`, verify Cargo build |
| 4 | `GatewayRepository.getDaoDeposits()` -- cell fetch, header resolution, status |
| 5 | `GatewayRepository.getDaoOverview()` + APC utility |
| 6 | `GatewayRepository.depositToDao()` via TransactionBuilder |
| 7 | `withdrawFromDao()` + `unlockDao()` -- phase 1 & phase 2 tx assembly |

### Week 2: ViewModel + Dashboard UI -> v1.3.0

| Day | Task |
|-----|------|
| 1 | `DaoViewModel` -- state, polling, actions |
| 2 | Overview card + empty state in `DaoScreen.kt` |
| 3 | `DaoDepositCard` + status badges |
| 4 | `CompensationProgressBar` (3-zone Canvas) + countdown |
| 5 | Deposit `ModalBottomSheet` with validation |
| 6 | Withdraw/unlock `AlertDialog` confirmations |
| 7 | **Release v1.3.0** |

### Week 3: Polish + Economics + Testing

| Day | Task |
|-----|------|
| 1 | Active/Completed tab filtering + sort |
| 2 | Pending action tracking, adaptive polling |
| 3 | Error handling (insufficient balance, lock not met, tx fail) |
| 4 | Reward estimates in deposit sheet (30d/360d) |
| 5 | Enable Home "Stake" quick action button |
| 6 | Testnet e2e: deposit -> withdraw -> unlock |
| 7 | Mainnet testing with small deposits |

### Week 4: Edge Cases + Docs -> v1.4.0

| Day | Task |
|-----|------|
| 1 | Edge: 102 CKB exact, full balance with reserve, concurrent deposits |
| 2 | Edge: withdraw at cycle boundaries, unlock before maturity |
| 3 | Network switching: DAO cells isolated per network, correct cell deps |
| 4 | Version bump, update CLAUDE.md |
| 5 | Battery/performance profiling with DAO polling |
| 6 | DAO rules info section |
| 7 | **Release v1.4.0** |

---

## Files Summary

### New Files

| File | Purpose |
|------|---------|
| `data/gateway/models/DaoModels.kt` | DaoDeposit, EpochInfo, DaoOverview, DaoCellStatus, CyclePhase |
| `data/gateway/DaoConstants.kt` | Code hash, cell dep tx hashes, epoch constants |
| `ui/screens/dao/DaoViewModel.kt` | State management, polling, user actions |
| `ui/screens/dao/DaoScreen.kt` | Full rewrite replacing placeholder |
| `ui/screens/dao/components/DaoDepositCard.kt` | Per-deposit record card |
| `ui/screens/dao/components/CompensationProgressBar.kt` | 3-zone epoch progress Canvas |
| `ui/screens/dao/components/DepositBottomSheet.kt` | Deposit flow with validation |
| `external/ckb-light-client/src/jni_dao.rs` | 3 Rust JNI function implementations |

### Modified Files

| File | Changes |
|------|---------|
| `LightClientNative.kt` | 3 new `external fun` declarations |
| `GatewayRepository.kt` | 4 new DAO suspend functions + APC utility |
| `TransactionBuilder.kt` | 3 new DAO tx builder methods |
| `HomeScreen.kt` | Enable "Stake" quick action -> DAO tab |
| `MainScreen.kt` | Pass navigation callback for Stake |
| `build.gradle.kts` | Version bumps (1.3.0, 1.4.0) |

### No New Dependencies

All handled by existing: `ckb-dao-utils` (Rust), `TransactionBuilder`, `kotlinx.serialization`, Compose + Material 3.
