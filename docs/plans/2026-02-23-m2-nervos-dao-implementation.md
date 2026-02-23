# M2 Nervos DAO — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Full Nervos DAO integration — deposit, two-phase withdrawal, compensation tracking, and a dashboard UI — replacing the current placeholder.

**Architecture:** MVVM layers built bottom-up: constants/models → Rust JNI (3 functions) → Repository + TransactionBuilder → ViewModel → Compose UI. Each layer depends on the one below. Follows existing patterns in `GatewayRepository`, `TransactionBuilder`, and `HomeViewModel`.

**Tech Stack:** Kotlin/Compose (UI + business logic), Rust via JNI (DAO math), ckb-dao-utils (Rust crate, already in Cargo.toml), Material 3, Hilt DI, StateFlow.

**Design doc:** `docs/plans/2026-02-19-m2-nervos-dao-design.md`
**Full spec:** `docs/M2_SPEC.md`
**Branch:** `feature/m2-nervos-dao`

---

## Task 1: DAO Constants & Protocol Models

**Issue:** #26

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoConstants.kt`
- Create: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/models/DaoModels.kt`

**Context:** These are pure data definitions with zero dependencies on JNI or Android. Everything else depends on these.

**Step 1: Create `DaoConstants.kt`**

Reference: existing `CellDep` companion pattern in `CkbModels.kt:30-44`.

```kotlin
package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellDep
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script

object DaoConstants {
    // DAO type script — same code hash on both networks
    const val DAO_CODE_HASH = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e"
    const val DAO_HASH_TYPE = "type"
    const val DAO_ARGS = "0x"

    // Cell dep tx hashes differ per network
    private const val DAO_CELL_DEP_TX_MAINNET = "0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c"
    private const val DAO_CELL_DEP_TX_TESTNET = "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f"
    private const val DAO_CELL_DEP_INDEX = "0x2"

    // Protocol constants
    const val WITHDRAW_EPOCHS = 180L
    const val HOURS_PER_EPOCH = 4
    const val MIN_DEPOSIT_SHANNONS = 10_200_000_000L  // 102 CKB
    const val RESERVE_SHANNONS = 6_200_000_000L       // 62 CKB
    val DAO_DEPOSIT_DATA = ByteArray(8)                // 8 zero bytes

    val DAO_TYPE_SCRIPT = Script(
        codeHash = DAO_CODE_HASH,
        hashType = DAO_HASH_TYPE,
        args = DAO_ARGS
    )

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
}
```

**Step 2: Create `DaoModels.kt`**

Reference: existing model patterns in `CkbModels.kt` and `ApiModels.kt`. Note `@Serializable` on all data classes.

```kotlin
package com.rjnr.pocketnode.data.gateway.models

import kotlinx.serialization.Serializable

// -- 7-state enum (modeled after Neuron's getDAOCellStatus.ts) --

enum class DaoCellStatus {
    DEPOSITING,    // deposit tx sent, awaiting confirmation
    DEPOSITED,     // confirmed on-chain, withdraw available
    WITHDRAWING,   // phase 1 tx sent, awaiting confirmation
    LOCKED,        // phase 1 confirmed, lock period not met
    UNLOCKABLE,    // lock period met, ready to unlock
    UNLOCKING,     // phase 2 tx sent, awaiting confirmation
    COMPLETED      // fully unlocked, funds returned
}

// -- Epoch arithmetic --

data class EpochInfo(
    val number: Long,
    val index: Long,
    val length: Long
) {
    val value: Double get() = number + index.toDouble() / length

    companion object {
        /** Parse CKB epoch hex (e.g. "0x7080018000001") into EpochInfo */
        fun fromHex(epochHex: String): EpochInfo {
            val epoch = epochHex.removePrefix("0x").toLong(16)
            val number = epoch and 0xFFFFFFL
            val index = (epoch shr 24) and 0xFFFFL
            val length = (epoch shr 40) and 0xFFFFL
            return EpochInfo(number = number, index = index, length = length)
        }
    }
}

// -- Cycle phase for compensation progress bar --

enum class CyclePhase {
    NORMAL,     // 0-80%
    SUGGESTED,  // 80-95%
    ENDING      // 95-100%
}

fun cyclePhaseFromProgress(progress: Float): CyclePhase = when {
    progress >= 0.95f -> CyclePhase.ENDING
    progress >= 0.80f -> CyclePhase.SUGGESTED
    else -> CyclePhase.NORMAL
}

// -- Per-deposit record --

data class DaoDeposit(
    val outPoint: OutPoint,
    val capacity: Long,                   // deposited shannons
    val status: DaoCellStatus,
    val depositBlockNumber: Long,
    val depositBlockHash: String,
    val depositEpoch: EpochInfo,
    val withdrawBlockNumber: Long? = null,
    val withdrawBlockHash: String? = null,
    val withdrawEpoch: EpochInfo? = null,
    val compensation: Long = 0L,          // earned shannons
    val unlockEpoch: EpochInfo? = null,   // earliest epoch for phase 2
    val lockRemainingHours: Int? = null,
    val compensationCycleProgress: Float = 0f,
    val cyclePhase: CyclePhase = CyclePhase.NORMAL
)

// -- Aggregate overview --

data class DaoOverview(
    val totalLocked: Long = 0L,
    val totalCompensation: Long = 0L,
    val currentApc: Double = 0.0,
    val activeCount: Int = 0,
    val completedCount: Int = 0
)

// -- ViewModel UI state --

enum class DaoTab { ACTIVE, COMPLETED }

sealed class DaoAction {
    data class Depositing(val amount: Long) : DaoAction()
    data class Withdrawing(val outPoint: OutPoint) : DaoAction()
    data class Unlocking(val outPoint: OutPoint) : DaoAction()
}

data class DaoUiState(
    val overview: DaoOverview = DaoOverview(),
    val activeDeposits: List<DaoDeposit> = emptyList(),
    val completedDeposits: List<DaoDeposit> = emptyList(),
    val selectedTab: DaoTab = DaoTab.ACTIVE,
    val isLoading: Boolean = true,
    val error: String? = null,
    val pendingAction: DaoAction? = null
)

// -- DAO header field extraction result --

@Serializable
data class DaoFields(
    val c: String,   // total capacity
    val ar: String,  // accumulated rate
    val s: String,   // secondary issuance
    val u: String    // occupied capacity
)

// -- Status determination function --

fun determineDaoStatus(
    isWithdrawingCell: Boolean,
    hasPendingWithdraw: Boolean,
    hasPendingUnlock: Boolean,
    hasPendingDeposit: Boolean,
    currentEpoch: EpochInfo?,
    unlockEpoch: EpochInfo?
): DaoCellStatus = when {
    hasPendingUnlock -> DaoCellStatus.UNLOCKING
    isWithdrawingCell && unlockEpoch != null && currentEpoch != null
        && currentEpoch.value >= unlockEpoch.value -> DaoCellStatus.UNLOCKABLE
    isWithdrawingCell -> DaoCellStatus.LOCKED
    hasPendingWithdraw -> DaoCellStatus.WITHDRAWING
    hasPendingDeposit -> DaoCellStatus.DEPOSITING
    else -> DaoCellStatus.DEPOSITED
}
```

**Step 3: Build to verify compilation**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/DaoConstants.kt \
        android/app/src/main/java/com/rjnr/pocketnode/data/gateway/models/DaoModels.kt
git commit -m "feat(dao): add DAO constants and data models (#26)"
```

---

## Task 2: Rust JNI — 3 New DAO Functions

**Issue:** #27

**Files:**
- Create: `external/ckb-light-client/light-client-lib/src/jni_bridge/dao.rs`
- Modify: `external/ckb-light-client/light-client-lib/src/jni_bridge/mod.rs:25-41`

**Context:** The Rust JNI bridge lives at `external/ckb-light-client/light-client-lib/src/jni_bridge/`. Existing functions follow the pattern in `query.rs:53-73`: `#[no_mangle] pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeXxx(env, _class) -> jtype`. Use `to_jstring()` helper from `query.rs:37` for JSON returns, `check_running!` macro from `query.rs:27`. The `ckb-dao-utils` and `ckb-types` crates are already in `Cargo.toml:32-33`.

**Step 1: Create `dao.rs` with 3 JNI functions**

```rust
//! DAO utility JNI functions for Nervos DAO compensation and epoch calculations.
//!
//! Three functions exposing `ckb-dao-utils` for:
//! - Extracting DAO header fields (C, AR, S, U)
//! - Calculating max withdrawable capacity (deposit + compensation)
//! - Calculating unlock epoch (since value for phase 2)

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use log::error;
use std::ptr;

use ckb_types::core::EpochNumberWithFraction;
use ckb_types::packed;
use ckb_types::prelude::*;

/// Helper: create a JNI string from a Rust string
fn dao_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            error!("Failed to create JString: {}", e);
            ptr::null_mut()
        }
    }
}

/// Helper: get a Rust String from a JNI JString
fn get_string(env: &mut JNIEnv, input: &JString) -> Option<String> {
    match env.get_string(input) {
        Ok(s) => Some(s.into()),
        Err(e) => {
            error!("Failed to get string from JNI: {}", e);
            None
        }
    }
}

/// Parse 32-byte DAO header field into 4 u64 values (C, AR, S, U).
/// Little-endian byte order: bytes 0-8 = C, 8-16 = AR, 16-24 = S, 24-32 = U.
/// Returns JSON: {"c":"0x...","ar":"0x...","s":"0x...","u":"0x..."}
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeExtractDaoFields(
    mut env: JNIEnv,
    _class: JClass,
    dao_hex: JString,
) -> jstring {
    let dao_str = match get_string(&mut env, &dao_hex) {
        Some(s) => s,
        None => return ptr::null_mut(),
    };

    let hex_str = dao_str.strip_prefix("0x").unwrap_or(&dao_str);
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 32 => b,
        Ok(b) => {
            error!("DAO field must be 32 bytes, got {}", b.len());
            return ptr::null_mut();
        }
        Err(e) => {
            error!("Failed to decode DAO hex: {}", e);
            return ptr::null_mut();
        }
    };

    let c = u64::from_le_bytes(bytes[0..8].try_into().unwrap());
    let ar = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
    let s = u64::from_le_bytes(bytes[16..24].try_into().unwrap());
    let u = u64::from_le_bytes(bytes[24..32].try_into().unwrap());

    let json = format!(
        r#"{{"c":"0x{:x}","ar":"0x{:x}","s":"0x{:x}","u":"0x{:x}"}}"#,
        c, ar, s, u
    );

    dao_to_jstring(&mut env, &json)
}

/// Calculate max withdrawable capacity (deposit + compensation).
/// Formula: (capacity - occupied) * AR_withdraw / AR_deposit + occupied
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeCalculateMaxWithdraw(
    mut env: JNIEnv,
    _class: JClass,
    deposit_header_dao_hex: JString,
    withdraw_header_dao_hex: JString,
    deposit_capacity: jlong,
    occupied_capacity: jlong,
) -> jlong {
    let deposit_dao_str = match get_string(&mut env, &deposit_header_dao_hex) {
        Some(s) => s,
        None => return -1,
    };
    let withdraw_dao_str = match get_string(&mut env, &withdraw_header_dao_hex) {
        Some(s) => s,
        None => return -1,
    };

    let parse_ar = |hex_str: &str| -> Option<u64> {
        let stripped = hex_str.strip_prefix("0x").unwrap_or(hex_str);
        let bytes = hex::decode(stripped).ok()?;
        if bytes.len() != 32 { return None; }
        Some(u64::from_le_bytes(bytes[8..16].try_into().ok()?))
    };

    let ar_deposit = match parse_ar(&deposit_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse deposit DAO AR");
            return -1;
        }
    };

    let ar_withdraw = match parse_ar(&withdraw_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse withdraw DAO AR");
            return -1;
        }
    };

    let capacity = deposit_capacity as u128;
    let occupied = occupied_capacity as u128;

    // Formula from RFC-0023
    let counted_capacity = capacity - occupied;
    let max_withdraw = counted_capacity * ar_withdraw / ar_deposit + occupied;

    max_withdraw as jlong
}

/// Calculate the since value (absolute epoch) for phase 2 unlock.
/// Parse epoch hex -> calc deposited epochs -> round up to 180-boundary -> encode.
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeCalculateUnlockEpoch(
    mut env: JNIEnv,
    _class: JClass,
    deposit_epoch_hex: JString,
    withdraw_epoch_hex: JString,
) -> jstring {
    let deposit_str = match get_string(&mut env, &deposit_epoch_hex) {
        Some(s) => s,
        None => return ptr::null_mut(),
    };
    let withdraw_str = match get_string(&mut env, &withdraw_epoch_hex) {
        Some(s) => s,
        None => return ptr::null_mut(),
    };

    let parse_epoch = |s: &str| -> Option<u64> {
        let stripped = s.strip_prefix("0x").unwrap_or(s);
        u64::from_str_radix(stripped, 16).ok()
    };

    let deposit_epoch_raw = match parse_epoch(&deposit_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse deposit epoch hex");
            return ptr::null_mut();
        }
    };
    let withdraw_epoch_raw = match parse_epoch(&withdraw_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse withdraw epoch hex");
            return ptr::null_mut();
        }
    };

    let deposit_epoch = EpochNumberWithFraction::from_full_value(deposit_epoch_raw);
    let withdraw_epoch = EpochNumberWithFraction::from_full_value(withdraw_epoch_raw);

    let deposit_number = deposit_epoch.number();
    let withdraw_number = withdraw_epoch.number();

    // Calculate deposited epochs (withdraw fraction > deposit fraction means +1)
    let deposited_epochs = if withdraw_epoch.index() * deposit_epoch.length()
        > deposit_epoch.index() * withdraw_epoch.length()
    {
        withdraw_number - deposit_number + 1
    } else {
        withdraw_number - deposit_number
    };

    // Round up to next 180-epoch boundary
    let lock_epochs = ((deposited_epochs + 179) / 180) * 180;
    let minimal_unlock_epoch = deposit_number + lock_epochs;

    // Encode as absolute epoch since value (0x20 prefix = absolute epoch flag)
    // Since field: bits 0-23 = epoch number, bits 24-39 = index (0), bits 40-55 = length (1)
    // With 0x20 prefix in the top byte for absolute epoch
    let since_epoch = EpochNumberWithFraction::new(minimal_unlock_epoch, 0, 1);
    // Absolute epoch flag: 0x2000000000000000
    let since_value = 0x2000_0000_0000_0000u64 | since_epoch.full_value();

    let result = format!("0x{:x}", since_value);
    dao_to_jstring(&mut env, &result)
}
```

**Step 2: Add module to `mod.rs`**

At `mod.rs:29` (after `pub mod rpc_handler;`), add:
```rust
pub mod dao;
```

At `mod.rs:41` (after `pub use rpc_handler::...;`), add:
```rust
pub use dao::*;
```

**Step 3: Verify Cargo build compiles**

Run: `cd external/ckb-light-client && cargo check --lib --features jni-bridge 2>&1 | tail -10`
Expected: no errors (warnings OK)

Note: If `hex` crate is not available, add `hex = "0.4"` to `Cargo.toml` dependencies, or use manual hex parsing. Check existing usage in the crate first.

**Step 4: Commit**

```bash
git add external/ckb-light-client/light-client-lib/src/jni_bridge/dao.rs \
        external/ckb-light-client/light-client-lib/src/jni_bridge/mod.rs
git commit -m "feat(dao): add 3 Rust JNI functions for DAO calculations (#27)"
```

---

## Task 3: Wire JNI Declarations in Kotlin

**Issue:** #27 (continued)

**Files:**
- Modify: `android/app/src/main/java/com/nervosnetwork/ckblightclient/LightClientNative.kt:189`

**Context:** Add 3 `external fun` declarations to the JNI bridge object. Reference: existing declarations at `LightClientNative.kt:109-129`.

**Step 1: Add 3 external fun declarations**

After `callRpc()` (around line 189), before the callback interfaces section, add:

```kotlin
// ========================================
// DAO Utility Functions
// ========================================

/** Extract C, AR, S, U from 32-byte DAO header field. Returns JSON. */
external fun nativeExtractDaoFields(daoHex: String): String?

/** Calculate max withdrawable capacity (deposit + compensation) in shannons. */
external fun nativeCalculateMaxWithdraw(
    depositHeaderDaoHex: String,
    withdrawHeaderDaoHex: String,
    depositCapacity: Long,
    occupiedCapacity: Long
): Long

/** Calculate the since value (absolute epoch) for phase 2 unlock. Returns hex string. */
external fun nativeCalculateUnlockEpoch(
    depositEpochHex: String,
    withdrawEpochHex: String
): String?
```

**Step 2: Build to verify compilation**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add android/app/src/main/java/com/nervosnetwork/ckblightclient/LightClientNative.kt
git commit -m "feat(dao): add JNI declarations for DAO utility functions (#27)"
```

---

## Task 4: DAO Type Script Registration

**Issue:** Part of #28

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt`

**Context:** The light client only indexes cells for registered scripts. Currently only the lock script is registered. We need to also register the DAO type script. There are 3 call sites for `nativeSetScripts` with `CMD_SET_SCRIPTS_ALL` (lines 398, 521, 707) that would wipe the DAO type script if not updated.

Strategy: Create a helper function that builds the full script list (lock + DAO type), then update all 3 call sites to use it.

**Step 1: Add helper function**

After the imports section, add a private helper in `GatewayRepository`:

```kotlin
/**
 * Build the full list of scripts to register (lock + DAO type).
 * Both scripts use the same block number to start indexing from.
 */
private fun buildScriptStatusList(lockScript: Script, blockNumberHex: String): String {
    val lockStatus = JniScriptStatus(
        script = lockScript,
        scriptType = "lock",
        blockNumber = blockNumberHex
    )
    val daoTypeStatus = JniScriptStatus(
        script = DaoConstants.DAO_TYPE_SCRIPT,
        scriptType = "type",
        blockNumber = blockNumberHex
    )
    return json.encodeToString(listOf(lockStatus, daoTypeStatus))
}
```

**Step 2: Update `registerAccount()` call site (line 394-398)**

Replace lines 394-398:
```kotlin
val list = listOf(scriptStatus)
val jsonStr = json.encodeToString(list)

Log.d(TAG, "📡 Calling nativeSetScripts with: $jsonStr")
val result = LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

With:
```kotlin
val blockNumberHex = "0x${finalBlockNum.toLongOrNull()?.toString(16) ?: "0"}"
val jsonStr = buildScriptStatusList(info.script, blockNumberHex)

Log.d(TAG, "📡 Calling nativeSetScripts with: $jsonStr")
val result = LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

And remove the now-unused `scriptStatus` variable (lines 386-390) since the helper builds both.

**Step 3: Update balance refresh call site (~line 515-521)**

Replace the block that builds `scriptStatus` and calls `nativeSetScripts`:
```kotlin
val scriptStatus = JniScriptStatus(
    script = info.script,
    scriptType = "lock",
    blockNumber = "0x${rescanFrom.toString(16)}"
)
val jsonStr = json.encodeToString(listOf(scriptStatus))
LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

With:
```kotlin
val blockNumberHex = "0x${rescanFrom.toString(16)}"
val jsonStr = buildScriptStatusList(info.script, blockNumberHex)
LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

**Step 4: Update post-send call site (~line 701-707)**

Same pattern — replace:
```kotlin
val scriptStatus = JniScriptStatus(
    script = info.script,
    scriptType = "lock",
    blockNumber = "0x${rescanFrom.toString(16)}"
)
val jsonStr = json.encodeToString(listOf(scriptStatus))
LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

With:
```kotlin
val blockNumberHex = "0x${rescanFrom.toString(16)}"
val jsonStr = buildScriptStatusList(info.script, blockNumberHex)
LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
```

**Step 5: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 6: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt
git commit -m "feat(dao): register DAO type script alongside lock script (#28)"
```

---

## Task 5: Repository — DAO Cell Querying & Operations

**Issue:** #28

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt` (add after `sendTransaction()` at ~line 719)

**Context:** Add `getDaoDeposits()`, `getDaoOverview()`, `getCurrentEpoch()` to `GatewayRepository`. These use the existing `nativeGetCells` with a DAO type script filter (instead of lock script), then enrich with header data and compensation calculations via the new JNI functions.

Reference patterns:
- Cell fetching: `getCells()` at lines 605-657
- Header fetching: `nativeGetHeader` usage pattern
- Search key: `JniSearchKey` with `scriptType = "type"` and `filter = JniSearchKeyFilter(script = userLockScript)` to limit to user's cells

**Step 1: Add `getCurrentEpoch()`**

```kotlin
suspend fun getCurrentEpoch(): Result<EpochInfo> = runCatching {
    val headerJson = LightClientNative.nativeGetTipHeader()
        ?: throw Exception("Failed to get tip header")
    val header = json.decodeFromString<JniHeaderView>(headerJson)
    EpochInfo.fromHex(header.epoch)
}
```

**Step 2: Add `getDaoDeposits()`**

```kotlin
suspend fun getDaoDeposits(): Result<List<DaoDeposit>> = runCatching {
    val info = _walletInfo.value ?: throw Exception("No wallet")

    // Query cells by DAO type script, filtered to user's lock script
    val searchKey = JniSearchKey(
        script = DaoConstants.DAO_TYPE_SCRIPT,
        scriptType = "type",
        filter = JniSearchKeyFilter(script = info.script),
        withData = true
    )
    val searchKeyJson = json.encodeToString(searchKey)

    val result = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, null)
        ?: throw Exception("Failed to get DAO cells")
    val pagination = json.decodeFromString<JniPagination<JniCell>>(result)

    val currentEpoch = getCurrentEpoch().getOrNull()

    pagination.objects.mapNotNull { jniCell ->
        val cell = jniCell.toCell()
        val data = cell.data.removePrefix("0x")

        // Determine if deposit or withdrawing cell
        val isWithdrawing = data.length == 16 && data != "0000000000000000"

        // Fetch deposit block header
        val headerJson = LightClientNative.nativeGetHeader(cell.blockNumber)
            ?: return@mapNotNull null
        val depositHeader = json.decodeFromString<JniHeaderView>(headerJson)
        val depositEpoch = EpochInfo.fromHex(depositHeader.epoch)

        var withdrawBlockNumber: Long? = null
        var withdrawBlockHash: String? = null
        var withdrawEpoch: EpochInfo? = null
        var compensation = 0L
        var unlockEpoch: EpochInfo? = null
        var lockRemainingHours: Int? = null

        if (isWithdrawing) {
            // Cell data contains deposit block number as 8-byte LE
            val depositBlockNum = data.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .let { bytes ->
                    var num = 0L
                    for (i in bytes.indices) {
                        num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                    }
                    num
                }
            val depositBlockHex = "0x${depositBlockNum.toString(16)}"

            // Fetch the original deposit block header
            val origDepositHeaderJson = LightClientNative.nativeGetHeader(depositBlockHex)
            val origDepositHeader = if (origDepositHeaderJson != null) {
                json.decodeFromString<JniHeaderView>(origDepositHeaderJson)
            } else depositHeader

            // The current cell's block is the withdraw block
            withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
            withdrawBlockHash = depositHeader.hash // actually this cell's block hash
            withdrawEpoch = depositEpoch // this cell's epoch

            // Calculate compensation
            val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                origDepositHeader.dao,
                depositHeader.dao,
                cell.capacity.removePrefix("0x").toLong(16),
                61_00000000L // occupied: 61 CKB for secp256k1 cell
            )
            compensation = maxWithdraw - cell.capacity.removePrefix("0x").toLong(16)

            // Calculate unlock epoch
            val sinceHex = LightClientNative.nativeCalculateUnlockEpoch(
                origDepositHeader.epoch,
                depositHeader.epoch
            )
            if (sinceHex != null) {
                // Parse the since value back to epoch
                val sinceVal = sinceHex.removePrefix("0x").toLong(16)
                val epochVal = sinceVal and 0x00FF_FFFF_FFFF_FFFFL // strip flags
                unlockEpoch = EpochInfo.fromHex("0x${epochVal.toString(16)}")

                if (currentEpoch != null && unlockEpoch.value > currentEpoch.value) {
                    val remainingEpochs = unlockEpoch.value - currentEpoch.value
                    lockRemainingHours = (remainingEpochs * DaoConstants.HOURS_PER_EPOCH).toInt()
                }
            }
        } else {
            // Deposited cell — calculate compensation using current tip header
            val tipJson = LightClientNative.nativeGetTipHeader()
            if (tipJson != null) {
                val tipHeader = json.decodeFromString<JniHeaderView>(tipJson)
                val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                    depositHeader.dao,
                    tipHeader.dao,
                    cell.capacity.removePrefix("0x").toLong(16),
                    61_00000000L
                )
                compensation = maxWithdraw - cell.capacity.removePrefix("0x").toLong(16)
            }
        }

        // Calculate cycle progress
        val depositedEpochs = if (currentEpoch != null) {
            (currentEpoch.value - depositEpoch.value).coerceAtLeast(0.0)
        } else 0.0
        val cycleProgress = ((depositedEpochs % DaoConstants.WITHDRAW_EPOCHS) / DaoConstants.WITHDRAW_EPOCHS).toFloat()

        val status = determineDaoStatus(
            isWithdrawingCell = isWithdrawing,
            hasPendingWithdraw = false, // TODO: track pending txs
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = currentEpoch,
            unlockEpoch = unlockEpoch
        )

        DaoDeposit(
            outPoint = cell.outPoint,
            capacity = cell.capacity.removePrefix("0x").toLong(16),
            status = status,
            depositBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16),
            depositBlockHash = depositHeader.hash,
            depositEpoch = depositEpoch,
            withdrawBlockNumber = withdrawBlockNumber,
            withdrawBlockHash = withdrawBlockHash,
            withdrawEpoch = withdrawEpoch,
            compensation = compensation.coerceAtLeast(0L),
            unlockEpoch = unlockEpoch,
            lockRemainingHours = lockRemainingHours,
            compensationCycleProgress = cycleProgress,
            cyclePhase = cyclePhaseFromProgress(cycleProgress)
        )
    }
}
```

**Step 3: Add `getDaoOverview()`**

```kotlin
suspend fun getDaoOverview(): Result<DaoOverview> = runCatching {
    val deposits = getDaoDeposits().getOrThrow()
    val active = deposits.filter { it.status != DaoCellStatus.COMPLETED }
    val completed = deposits.filter { it.status == DaoCellStatus.COMPLETED }

    DaoOverview(
        totalLocked = active.sumOf { it.capacity },
        totalCompensation = deposits.sumOf { it.compensation },
        currentApc = 2.47, // approximate, can be refined later
        activeCount = active.size,
        completedCount = completed.size
    )
}
```

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt
git commit -m "feat(dao): add DAO cell querying and overview in repository (#28)"
```

---

## Task 6: TransactionBuilder — DAO Deposit, Withdraw, Unlock

**Issue:** #29

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/transaction/TransactionBuilder.kt`

**Context:** Add 3 new tx builder methods after `buildTransfer()` (~line 206). Follow the same cell selection, fee estimation, and signing patterns. Key differences from regular transfers:
- Deposit: output has DAO type script + 8-byte zero data, 2 cell deps (secp256k1 + DAO)
- Withdraw (Phase 1): input is the deposit cell, output mirrors it with data = block# as 8-byte LE, header_deps = [depositBlockHash]
- Unlock (Phase 2): input has `since` = absolute epoch, output has NO type script, witness has `output_type` = 8-byte LE index 0, header_deps = [depositBlockHash, withdrawBlockHash]

**Step 1: Add `buildDaoDeposit()`**

After `buildTransfer()`, add:

```kotlin
fun buildDaoDeposit(
    amountShannons: Long,
    availableCells: List<Cell>,
    senderScript: Script,
    privateKey: ByteArray,
    network: NetworkType
): Transaction {
    require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
        "Minimum DAO deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
    }

    val (selectedCells, totalInput) = selectCells(availableCells, amountShannons + DEFAULT_FEE)
    if (totalInput < amountShannons + DEFAULT_FEE) {
        throw Exception("Insufficient balance. Need ${amountShannons + DEFAULT_FEE}, have $totalInput")
    }

    val fee = estimateTransferFee(selectedCells.size, 2) // deposit + change
    val change = totalInput - amountShannons - fee

    val inputs = selectedCells.map { CellInput(previousOutput = it.outPoint) }

    val outputs = mutableListOf(
        CellOutput(
            capacity = "0x${amountShannons.toString(16)}",
            lock = senderScript,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )
    )
    val outputsData = mutableListOf(
        "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
    )

    if (change >= MIN_CELL_CAPACITY) {
        outputs.add(CellOutput(
            capacity = "0x${change.toString(16)}",
            lock = senderScript
        ))
        outputsData.add("0x")
    }

    val secp256k1Dep = when (network) {
        NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
        NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
    }

    val unsignedTx = Transaction(
        cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
        headerDeps = emptyList(),
        cellInputs = inputs,
        cellOutputs = outputs,
        outputsData = outputsData,
        witnesses = inputs.map { "0x" }
    )

    return signTransaction(unsignedTx, privateKey, inputs.size)
}
```

**Step 2: Add `buildDaoWithdraw()`**

```kotlin
fun buildDaoWithdraw(
    depositCell: Cell,
    depositBlockNumber: Long,
    depositBlockHash: String,
    senderScript: Script,
    privateKey: ByteArray,
    network: NetworkType
): Transaction {
    val capacity = depositCell.capacity

    // Output mirrors the deposit cell but with block number as data
    val blockNumberBytes = ByteArray(8)
    var num = depositBlockNumber
    for (i in 0 until 8) {
        blockNumberBytes[i] = (num and 0xFF).toByte()
        num = num shr 8
    }
    val blockNumberHex = "0x" + blockNumberBytes.joinToString("") { "%02x".format(it) }

    val inputs = listOf(CellInput(previousOutput = depositCell.outPoint))
    val outputs = listOf(
        CellOutput(
            capacity = capacity,
            lock = senderScript,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )
    )

    val secp256k1Dep = when (network) {
        NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
        NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
    }

    val unsignedTx = Transaction(
        cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
        headerDeps = listOf(depositBlockHash),
        cellInputs = inputs,
        cellOutputs = outputs,
        outputsData = listOf(blockNumberHex),
        witnesses = listOf("0x")
    )

    return signTransaction(unsignedTx, privateKey, 1)
}
```

**Step 3: Add `buildDaoUnlock()` and modify `signTransaction()`**

First, update `signTransaction()` to accept optional `outputType`:

Change the signature from:
```kotlin
private fun signTransaction(
    tx: Transaction,
    privateKey: ByteArray,
    inputCount: Int
): Transaction {
```

To:
```kotlin
private fun signTransaction(
    tx: Transaction,
    privateKey: ByteArray,
    inputCount: Int,
    witnessOutputType: ByteArray? = null
): Transaction {
```

Then at lines 254 and 276, change `serializeWitnessArgs(ByteArray(65), null, null)` and `serializeWitnessArgs(signature, null, null)` to use `witnessOutputType`:

```kotlin
// Line 254: empty witness args for signing message
val emptyWitnessArgs = serializeWitnessArgs(ByteArray(65), null, witnessOutputType)

// Line 276: signed witness args
val signedWitnessArgs = serializeWitnessArgs(signature, null, witnessOutputType)
```

Then add `buildDaoUnlock()`:

```kotlin
fun buildDaoUnlock(
    withdrawingCell: Cell,
    maxWithdraw: Long,
    sinceValue: String,
    depositBlockHash: String,
    withdrawBlockHash: String,
    senderScript: Script,
    privateKey: ByteArray,
    network: NetworkType
): Transaction {
    val fee = DEFAULT_FEE

    val inputs = listOf(
        CellInput(
            since = sinceValue,
            previousOutput = withdrawingCell.outPoint
        )
    )

    val outputs = listOf(
        CellOutput(
            capacity = "0x${(maxWithdraw - fee).toString(16)}",
            lock = senderScript
            // No type script — funds return to normal cell
        )
    )

    val secp256k1Dep = when (network) {
        NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
        NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
    }

    // output_type in witness = 8-byte LE index of deposit header in header_deps (index 0)
    val depositHeaderIndex = ByteArray(8) // 8 zero bytes = index 0

    val unsignedTx = Transaction(
        cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
        headerDeps = listOf(depositBlockHash, withdrawBlockHash),
        cellInputs = inputs,
        cellOutputs = outputs,
        outputsData = listOf("0x"),
        witnesses = listOf("0x")
    )

    return signTransaction(unsignedTx, privateKey, 1, witnessOutputType = depositHeaderIndex)
}
```

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/transaction/TransactionBuilder.kt
git commit -m "feat(dao): add DAO deposit, withdraw, unlock tx builders (#29)"
```

---

## Task 7: Repository — DAO Transaction Operations

**Issue:** #28 (continued)

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt`

**Context:** Add `depositToDao()`, `withdrawFromDao()`, `unlockDao()` to `GatewayRepository`. These orchestrate: validate → get cells → build tx → sign → send. Follow the `sendTransaction()` pattern at lines 659-719.

**Step 1: Add `depositToDao()`**

```kotlin
suspend fun depositToDao(amountShannons: Long): Result<String> = runCatching {
    val info = _walletInfo.value ?: throw Exception("No wallet")
    val net = _network.value

    require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
        "Minimum deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
    }

    val cells = getCells().getOrThrow()
    val privateKey = keyManager.getPrivateKey()
        ?: throw Exception("No private key")

    val tx = transactionBuilder.buildDaoDeposit(
        amountShannons = amountShannons,
        availableCells = cells.cells,
        senderScript = info.script,
        privateKey = privateKey,
        network = net
    )

    val txHash = sendTransaction(tx).getOrThrow()
    Log.d(TAG, "DAO deposit sent: $txHash")
    txHash
}
```

**Step 2: Add `withdrawFromDao()`**

```kotlin
suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String> = runCatching {
    val info = _walletInfo.value ?: throw Exception("No wallet")
    val net = _network.value

    // Find the deposit cell
    val deposits = getDaoDeposits().getOrThrow()
    val deposit = deposits.find { it.outPoint == depositOutPoint }
        ?: throw Exception("Deposit not found")

    val privateKey = keyManager.getPrivateKey()
        ?: throw Exception("No private key")

    // Build a Cell from the deposit for the transaction builder
    val depositCell = Cell(
        outPoint = deposit.outPoint,
        capacity = "0x${deposit.capacity.toString(16)}",
        blockNumber = "0x${deposit.depositBlockNumber.toString(16)}",
        lock = info.script,
        type = DaoConstants.DAO_TYPE_SCRIPT,
        data = "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
    )

    val tx = transactionBuilder.buildDaoWithdraw(
        depositCell = depositCell,
        depositBlockNumber = deposit.depositBlockNumber,
        depositBlockHash = deposit.depositBlockHash,
        senderScript = info.script,
        privateKey = privateKey,
        network = net
    )

    val txHash = sendTransaction(tx).getOrThrow()
    Log.d(TAG, "DAO withdraw (phase 1) sent: $txHash")
    txHash
}
```

**Step 3: Add `unlockDao()`**

```kotlin
suspend fun unlockDao(
    withdrawingOutPoint: OutPoint,
    depositBlockHash: String,
    withdrawBlockHash: String
): Result<String> = runCatching {
    val info = _walletInfo.value ?: throw Exception("No wallet")
    val net = _network.value

    val deposits = getDaoDeposits().getOrThrow()
    val deposit = deposits.find { it.outPoint == withdrawingOutPoint }
        ?: throw Exception("Withdrawing cell not found")

    require(deposit.status == DaoCellStatus.UNLOCKABLE) {
        "Cell is not unlockable yet (status: ${deposit.status})"
    }

    val privateKey = keyManager.getPrivateKey()
        ?: throw Exception("No private key")

    // Get headers for max withdraw calculation
    val depositHeaderJson = LightClientNative.nativeGetHeader(
        "0x${deposit.depositBlockNumber.toString(16)}"
    ) ?: throw Exception("Failed to get deposit header")
    val depositHeader = json.decodeFromString<JniHeaderView>(depositHeaderJson)

    val withdrawHeaderJson = LightClientNative.nativeGetHeader(
        "0x${(deposit.withdrawBlockNumber ?: throw Exception("No withdraw block")).toString(16)}"
    ) ?: throw Exception("Failed to get withdraw header")
    val withdrawHeader = json.decodeFromString<JniHeaderView>(withdrawHeaderJson)

    val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
        depositHeader.dao,
        withdrawHeader.dao,
        deposit.capacity,
        61_00000000L
    )

    val sinceValue = LightClientNative.nativeCalculateUnlockEpoch(
        depositHeader.epoch,
        withdrawHeader.epoch
    ) ?: throw Exception("Failed to calculate unlock epoch")

    val withdrawingCell = Cell(
        outPoint = deposit.outPoint,
        capacity = "0x${deposit.capacity.toString(16)}",
        blockNumber = "0x${(deposit.withdrawBlockNumber).toString(16)}",
        lock = info.script,
        type = DaoConstants.DAO_TYPE_SCRIPT
    )

    val tx = transactionBuilder.buildDaoUnlock(
        withdrawingCell = withdrawingCell,
        maxWithdraw = maxWithdraw,
        sinceValue = sinceValue,
        depositBlockHash = depositBlockHash,
        withdrawBlockHash = withdrawBlockHash,
        senderScript = info.script,
        privateKey = privateKey,
        network = net
    )

    val txHash = sendTransaction(tx).getOrThrow()
    Log.d(TAG, "DAO unlock (phase 2) sent: $txHash")
    txHash
}
```

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/data/gateway/GatewayRepository.kt
git commit -m "feat(dao): add deposit, withdraw, unlock operations to repository (#28)"
```

---

## Task 8: DaoViewModel — State Management & Adaptive Polling

**Issue:** #30

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoViewModel.kt`

**Context:** Follow `HomeViewModel` pattern (~366 lines): `@HiltViewModel`, `MutableStateFlow<UiState>`, `viewModelScope.launch` for polling. Adaptive polling: 10s when a pending action exists, 30s otherwise.

**Step 1: Create `DaoViewModel.kt`**

```kotlin
package com.rjnr.pocketnode.ui.screens.dao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DaoViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaoUiState())
    val uiState: StateFlow<DaoUiState> = _uiState.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refreshDaoData()
                val interval = if (_uiState.value.pendingAction != null) 10_000L else 30_000L
                delay(interval)
            }
        }
    }

    private suspend fun refreshDaoData() {
        repository.getDaoDeposits()
            .onSuccess { deposits ->
                val active = deposits.filter { it.status != DaoCellStatus.COMPLETED }
                val completed = deposits.filter { it.status == DaoCellStatus.COMPLETED }

                val overview = DaoOverview(
                    totalLocked = active.sumOf { it.capacity },
                    totalCompensation = deposits.sumOf { it.compensation },
                    currentApc = 2.47, // approximate
                    activeCount = active.size,
                    completedCount = completed.size
                )

                _uiState.update {
                    it.copy(
                        overview = overview,
                        activeDeposits = active,
                        completedDeposits = completed,
                        isLoading = false
                    )
                }

                // Auto-clear pending actions when state transitions
                resolvePendingAction(deposits)
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(error = e.message, isLoading = false)
                }
            }
    }

    private fun resolvePendingAction(deposits: List<DaoDeposit>) {
        val pending = _uiState.value.pendingAction ?: return

        val shouldClear = when (pending) {
            is DaoAction.Depositing -> deposits.any { it.status == DaoCellStatus.DEPOSITED }
            is DaoAction.Withdrawing -> deposits.any {
                it.outPoint == pending.outPoint &&
                    (it.status == DaoCellStatus.LOCKED || it.status == DaoCellStatus.UNLOCKABLE)
            }
            is DaoAction.Unlocking -> deposits.none { it.outPoint == pending.outPoint }
        }

        if (shouldClear) {
            _uiState.update { it.copy(pendingAction = null) }
        }
    }

    fun deposit(amountShannons: Long) {
        _uiState.update { it.copy(pendingAction = DaoAction.Depositing(amountShannons)) }
        viewModelScope.launch {
            repository.depositToDao(amountShannons)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun withdraw(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Withdrawing(deposit.outPoint)) }
        viewModelScope.launch {
            repository.withdrawFromDao(deposit.outPoint)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun unlock(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Unlocking(deposit.outPoint)) }
        viewModelScope.launch {
            repository.unlockDao(
                withdrawingOutPoint = deposit.outPoint,
                depositBlockHash = deposit.depositBlockHash,
                withdrawBlockHash = deposit.withdrawBlockHash
                    ?: throw Exception("No withdraw block hash")
            )
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun selectTab(tab: DaoTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
```

**Step 2: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoViewModel.kt
git commit -m "feat(dao): add DaoViewModel with adaptive polling (#30)"
```

---

## Task 9: DAO Dashboard UI — Overview Card & Empty State

**Issue:** #31

**Files:**
- Rewrite: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/MainScreen.kt:98-100`

**Context:** Replace the entire placeholder `DaoScreen.kt` (205 lines) with the functional dashboard. Wire `hiltViewModel()` in `MainScreen.kt`. This task covers the Scaffold layout, overview card, empty state, and tab row. Deposit cards and bottom sheets are in subsequent tasks.

**Step 1: Rewrite `DaoScreen.kt`**

Full rewrite — replace entire file. The screen shows:
- Overview card with locked total, compensation, APC, deposit button
- Tab row (Active / Completed)
- LazyColumn of deposit cards (stubbed as text for now, built in Task 10)
- Empty state when no deposits

```kotlin
package com.rjnr.pocketnode.ui.screens.dao

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.DaoOverview
import com.rjnr.pocketnode.data.gateway.models.DaoTab

private val DaoGreen = Color(0xFF1ED882)

@Composable
fun DaoScreen(viewModel: DaoViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            DaoOverviewCard(
                overview = uiState.overview,
                onDepositClick = { /* opens bottom sheet — wired in Task 11 */ }
            )

            Spacer(modifier = Modifier.height(16.dp))

            DaoTabRow(
                selectedTab = uiState.selectedTab,
                activeCount = uiState.overview.activeCount,
                completedCount = uiState.overview.completedCount,
                onTabSelected = viewModel::selectTab
            )

            Spacer(modifier = Modifier.height(8.dp))

            val deposits = when (uiState.selectedTab) {
                DaoTab.ACTIVE -> uiState.activeDeposits
                DaoTab.COMPLETED -> uiState.completedDeposits
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (deposits.isEmpty() && uiState.selectedTab == DaoTab.ACTIVE) {
                DaoEmptyState(onDepositClick = { /* wired in Task 11 */ })
            } else if (deposits.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No completed deposits yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(deposits, key = { "${it.outPoint.txHash}:${it.outPoint.index}" }) { deposit ->
                        // Placeholder card — replaced in Task 10
                        DaoDepositCardPlaceholder(deposit)
                    }
                }
            }
        }
    }

    // Snackbar for errors
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Will be replaced with proper Snackbar host in polish phase
            viewModel.clearError()
        }
    }
}

@Composable
private fun DaoOverviewCard(
    overview: DaoOverview,
    onDepositClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Nervos DAO",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = formatCkb(overview.totalLocked) + " CKB",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "+${formatCkb(overview.totalCompensation)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = DaoGreen
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "APC ~${String.format("%.2f", overview.currentApc)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDepositClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Deposit")
            }
        }
    }
}

@Composable
private fun DaoTabRow(
    selectedTab: DaoTab,
    activeCount: Int,
    completedCount: Int,
    onTabSelected: (DaoTab) -> Unit
) {
    TabRow(
        selectedTabIndex = if (selectedTab == DaoTab.ACTIVE) 0 else 1
    ) {
        Tab(
            selected = selectedTab == DaoTab.ACTIVE,
            onClick = { onTabSelected(DaoTab.ACTIVE) },
            text = { Text("Active ($activeCount)") }
        )
        Tab(
            selected = selectedTab == DaoTab.COMPLETED,
            onClick = { onTabSelected(DaoTab.COMPLETED) },
            text = { Text("Completed ($completedCount)") }
        )
    }
}

@Composable
private fun DaoEmptyState(onDepositClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Earn rewards with Nervos DAO",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Deposit CKB to earn ~2.5% annual compensation. 180-epoch lock cycles.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDepositClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Make First Deposit")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Not seeing expected deposits? Try resyncing from an earlier block in Settings.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun DaoDepositCardPlaceholder(deposit: DaoDeposit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${formatCkb(deposit.capacity)} CKB",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = deposit.status.name,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
```

**Step 2: Update `MainScreen.kt:98-100` to pass ViewModel**

Change:
```kotlin
composable(BottomTab.DAO.route) {
    DaoScreen()
}
```
To:
```kotlin
composable(BottomTab.DAO.route) {
    DaoScreen(viewModel = hiltViewModel())
}
```

Add the import at the top of MainScreen.kt:
```kotlin
import androidx.hilt.navigation.compose.hiltViewModel
```

**Step 3: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/MainScreen.kt
git commit -m "feat(dao): replace placeholder with DAO dashboard UI (#31)"
```

---

## Task 10: DAO Deposit Card & Compensation Progress Bar

**Issue:** #33

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/DaoDepositCard.kt`
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/CompensationProgressBar.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt` (replace placeholder card)

**Context:** Build the per-deposit card with status badge, compensation display, progress bar, countdown, and action button. Then the 3-zone Canvas progress bar (green/amber/red). Wire into DaoScreen's LazyColumn.

**Step 1: Create `CompensationProgressBar.kt`**

```kotlin
package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.CyclePhase

private val TrackColor = Color(0xFF252525)
private val NormalGreen = Color(0xFF1ED882)
private val SuggestedAmber = Color(0xFFF59E0B)
private val EndingRed = Color(0xFFEF4444)

@Composable
fun CompensationProgressBar(
    progress: Float,
    phase: CyclePhase,
    modifier: Modifier = Modifier
) {
    val progressColor = when (phase) {
        CyclePhase.NORMAL -> NormalGreen
        CyclePhase.SUGGESTED -> SuggestedAmber
        CyclePhase.ENDING -> EndingRed
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val cornerRadius = CornerRadius(4.dp.toPx())

        // Track
        drawRoundRect(
            color = TrackColor,
            cornerRadius = cornerRadius,
            size = size
        )

        // Progress
        if (progress > 0f) {
            drawRoundRect(
                color = progressColor,
                cornerRadius = cornerRadius,
                size = Size(size.width * progress.coerceIn(0f, 1f), size.height)
            )
        }
    }
}
```

**Step 2: Create `DaoDepositCard.kt`**

```kotlin
package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit

private val DaoGreen = Color(0xFF1ED882)
private val StatusAmber = Color(0xFFF59E0B)
private val StatusGray = Color(0xFFA0A0A0)
private val MutedGreen = Color(0xFF4ADE80)

@Composable
fun DaoDepositCard(
    deposit: DaoDeposit,
    onWithdraw: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Amount + compensation row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${formatCkb(deposit.capacity)} CKB",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (deposit.compensation > 0) {
                        Text(
                            text = "+${formatCkb(deposit.compensation)} CKB",
                            style = MaterialTheme.typography.bodySmall,
                            color = DaoGreen
                        )
                    }
                }

                StatusBadge(deposit.status, deposit.lockRemainingHours)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            CompensationProgressBar(
                progress = deposit.compensationCycleProgress,
                phase = deposit.cyclePhase
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Countdown + action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val countdownText = when (deposit.status) {
                    DaoCellStatus.LOCKED -> {
                        val hours = deposit.lockRemainingHours ?: 0
                        val days = hours / 24
                        val remainingHours = hours % 24
                        "Unlockable in ${days}d ${remainingHours}h"
                    }
                    DaoCellStatus.UNLOCKABLE -> "Ready to unlock!"
                    DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING ->
                        "Confirming..."
                    else -> ""
                }

                Text(
                    text = countdownText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (deposit.status) {
                    DaoCellStatus.DEPOSITED -> {
                        OutlinedButton(
                            onClick = onWithdraw,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Withdraw")
                        }
                    }
                    DaoCellStatus.UNLOCKABLE -> {
                        Button(
                            onClick = onUnlock,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Unlock")
                        }
                    }
                    DaoCellStatus.DEPOSITING, DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    else -> { /* no action button */ }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DaoCellStatus, lockRemainingHours: Int?) {
    val (color, label) = when (status) {
        DaoCellStatus.DEPOSITING -> StatusAmber to "Depositing..."
        DaoCellStatus.DEPOSITED -> DaoGreen to "Active"
        DaoCellStatus.WITHDRAWING -> StatusAmber to "Withdrawing..."
        DaoCellStatus.LOCKED -> {
            val hours = lockRemainingHours ?: 0
            val days = hours / 24
            val h = hours % 24
            StatusGray to "Locked — ${days}d ${h}h"
        }
        DaoCellStatus.UNLOCKABLE -> DaoGreen to "Ready to Unlock"
        DaoCellStatus.UNLOCKING -> StatusAmber to "Unlocking..."
        DaoCellStatus.COMPLETED -> MutedGreen to "Completed"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
```

**Step 3: Wire into DaoScreen**

In `DaoScreen.kt`, replace the `DaoDepositCardPlaceholder` usage in the LazyColumn with:

```kotlin
items(deposits, key = { "${it.outPoint.txHash}:${it.outPoint.index}" }) { deposit ->
    DaoDepositCard(
        deposit = deposit,
        onWithdraw = { viewModel.withdraw(deposit) },
        onUnlock = { viewModel.unlock(deposit) }
    )
}
```

Add import:
```kotlin
import com.rjnr.pocketnode.ui.screens.dao.components.DaoDepositCard
```

Remove the `DaoDepositCardPlaceholder` composable function.

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/CompensationProgressBar.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/DaoDepositCard.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt
git commit -m "feat(dao): add deposit card with status badges and progress bar (#33)"
```

---

## Task 11: Deposit Bottom Sheet

**Issue:** #32

**Files:**
- Create: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/DepositBottomSheet.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt` (wire bottom sheet)

**Context:** `ModalBottomSheet` with amount input, min 102 CKB validation, reserve 62 CKB checkbox, reward estimates, and DAO rules info section. Wire deposit button in overview card and empty state to open this sheet.

**Step 1: Create `DepositBottomSheet.kt`**

```kotlin
package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.DaoConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositBottomSheet(
    availableBalance: Long,
    currentApc: Double,
    onDeposit: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var reserveEnabled by remember { mutableStateOf(true) }
    var showRules by remember { mutableStateOf(false) }

    val amountCkb = amountText.toDoubleOrNull() ?: 0.0
    val amountShannons = (amountCkb * 100_000_000).toLong()
    val maxDepositable = if (reserveEnabled) {
        (availableBalance - DaoConstants.RESERVE_SHANNONS).coerceAtLeast(0L)
    } else {
        availableBalance
    }
    val isValid = amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS
        && amountShannons <= maxDepositable

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Deposit to Nervos DAO",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Available: ${formatCkb(maxDepositable)} CKB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (CKB)") },
                placeholder = { Text("Min 102 CKB") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = amountText.isNotEmpty() && !isValid,
                supportingText = {
                    if (amountText.isNotEmpty() && amountShannons < DaoConstants.MIN_DEPOSIT_SHANNONS) {
                        Text("Minimum deposit is 102 CKB")
                    } else if (amountText.isNotEmpty() && amountShannons > maxDepositable) {
                        Text("Exceeds available balance")
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = reserveEnabled,
                    onCheckedChange = { reserveEnabled = it }
                )
                Text(
                    text = "Reserve 62 CKB for future fees",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Reward estimates
            if (amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val reward30d = amountCkb * currentApc / 100 / 12
                        val reward360d = amountCkb * currentApc / 100
                        Text(
                            text = "Estimated rewards",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "30 days: ~${String.format("%.4f", reward30d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "360 days: ~${String.format("%.4f", reward360d)} CKB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable rules
            TextButton(onClick = { showRules = !showRules }) {
                Text(if (showRules) "Hide Nervos DAO Rules" else "Nervos DAO Rules")
            }
            AnimatedVisibility(visible = showRules) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val rules = listOf(
                            "Minimum deposit: 102 CKB",
                            "Lock period: 180 epochs (~30 days)",
                            "You can withdraw anytime, but funds stay locked until the cycle ends",
                            "Unlock becomes available after the full lock cycle completes"
                        )
                        rules.forEach { rule ->
                            Text(
                                text = "• $rule",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onDeposit(amountShannons) },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Deposit")
                }
            }
        }
    }
}

private fun formatCkb(shannons: Long): String {
    val ckb = shannons / 100_000_000.0
    return String.format("%.2f", ckb)
}
```

**Step 2: Wire bottom sheet in DaoScreen**

In `DaoScreen.kt`, add state and bottom sheet:

After `val uiState by viewModel.uiState.collectAsState()`, add:
```kotlin
var showDepositSheet by remember { mutableStateOf(false) }
```

Replace the `onDepositClick` lambdas (overview card + empty state) with:
```kotlin
onDepositClick = { showDepositSheet = true }
```

After the Scaffold closing brace, add:
```kotlin
if (showDepositSheet) {
    DepositBottomSheet(
        availableBalance = 0L, // TODO: pass from repository balance
        currentApc = uiState.overview.currentApc,
        onDeposit = { amount ->
            showDepositSheet = false
            viewModel.deposit(amount)
        },
        onDismiss = { showDepositSheet = false }
    )
}
```

Add import:
```kotlin
import com.rjnr.pocketnode.ui.screens.dao.components.DepositBottomSheet
```

**Step 3: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/components/DepositBottomSheet.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt
git commit -m "feat(dao): add deposit bottom sheet with validation (#32)"
```

---

## Task 12: Withdraw & Unlock Confirmation Dialogs

**Issue:** #34

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt`

**Context:** Add `AlertDialog` confirmations before withdraw (Phase 1) and unlock (Phase 2). These are triggered from `DaoDepositCard` action buttons. The dialogs show deposit amount, earned compensation, and warnings about lock time.

**Step 1: Add confirmation dialog state and composables**

In `DaoScreen.kt`, add state variables:
```kotlin
var withdrawTarget by remember { mutableStateOf<DaoDeposit?>(null) }
var unlockTarget by remember { mutableStateOf<DaoDeposit?>(null) }
```

Update the `DaoDepositCard` calls to set confirmation targets instead of calling viewModel directly:
```kotlin
DaoDepositCard(
    deposit = deposit,
    onWithdraw = { withdrawTarget = deposit },
    onUnlock = { unlockTarget = deposit }
)
```

Add dialog composables at the bottom of DaoScreen:

```kotlin
// Withdraw confirmation
withdrawTarget?.let { deposit ->
    AlertDialog(
        onDismissRequest = { withdrawTarget = null },
        title = { Text("Withdraw from DAO") },
        text = {
            Column {
                Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                Text("Earned: +${formatCkb(deposit.compensation)} CKB")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your funds will remain locked until the current 180-epoch cycle ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.withdraw(deposit)
                withdrawTarget = null
            }) {
                Text("Withdraw")
            }
        },
        dismissButton = {
            TextButton(onClick = { withdrawTarget = null }) {
                Text("Cancel")
            }
        }
    )
}

// Unlock confirmation
unlockTarget?.let { deposit ->
    val totalReceived = deposit.capacity + deposit.compensation
    AlertDialog(
        onDismissRequest = { unlockTarget = null },
        title = { Text("Unlock DAO Deposit") },
        text = {
            Column {
                Text("Deposit: ${formatCkb(deposit.capacity)} CKB")
                Text("Compensation: +${formatCkb(deposit.compensation)} CKB")
                Text("Total received: ${formatCkb(totalReceived)} CKB")
                Text("Fee: ~0.001 CKB")
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.unlock(deposit)
                unlockTarget = null
            }) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = { unlockTarget = null }) {
                Text("Cancel")
            }
        }
    )
}
```

**Step 2: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt
git commit -m "feat(dao): add withdraw and unlock confirmation dialogs (#34)"
```

---

## Task 13: Enable Home Stake Button & DAO Tab Navigation

**Issue:** #35

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/Components.kt` (~line 157)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt` (~line 368)
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/MainScreen.kt`

**Context:** The `ActionRow` in `Components.kt:157-160` currently has only Send and Receive. Add a Stake action that navigates to the DAO tab. This requires threading a callback from `MainScreen` → `HomeScreen` → `ActionRow`.

**Step 1: Add `onStake` parameter to `ActionRow`**

In `Components.kt`, update:
```kotlin
@Composable
fun ActionRow(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onStake: () -> Unit
)
```

Add a Stake button inside the ActionRow (alongside Send/Receive). Follow the same pattern used for Send/Receive buttons.

**Step 2: Thread callback through HomeScreen**

In `HomeScreen.kt`, add `onNavigateToDao: () -> Unit` parameter and pass to `ActionRow`:
```kotlin
ActionRow(
    onSend = onNavigateToSend,
    onReceive = onNavigateToReceive,
    onStake = onNavigateToDao
)
```

**Step 3: Wire in MainScreen**

In `MainScreen.kt`, the `HomeScreen` call needs the new callback. Add a mechanism to programmatically switch the selected bottom tab to DAO:

```kotlin
composable(BottomTab.Home.route) {
    HomeScreen(
        // ... existing params
        onNavigateToDao = {
            navController.navigate(BottomTab.DAO.route) {
                popUpTo(BottomTab.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    )
}
```

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/Components.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/MainScreen.kt
git commit -m "feat(dao): enable Stake action button on home screen (#35)"
```

---

## Task 14: Error Handling, Snackbar, & Polish

**Issue:** #36

**Files:**
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt`
- Modify: `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoViewModel.kt`

**Context:** Add proper SnackbarHost to DaoScreen, pass available balance to deposit sheet, sort deposits by deposit time, handle edge cases.

**Step 1: Add SnackbarHost to DaoScreen Scaffold**

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) }
) { ... }

// Replace the LaunchedEffect error handler with:
uiState.error?.let { error ->
    LaunchedEffect(error) {
        snackbarHostState.showSnackbar(error)
        viewModel.clearError()
    }
}
```

**Step 2: Pass available balance from repository**

In `DaoViewModel`, expose the balance:
```kotlin
val availableBalance: StateFlow<Long> = repository.balance
    .map { it?.available?.toLongOrNull() ?: 0L }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
```

In `DaoScreen`, collect it:
```kotlin
val availableBalance by viewModel.availableBalance.collectAsState()
```

Pass to `DepositBottomSheet`:
```kotlin
DepositBottomSheet(
    availableBalance = availableBalance,
    ...
)
```

**Step 3: Sort deposits**

In `DaoViewModel.refreshDaoData()`, sort before setting state:
```kotlin
val active = deposits
    .filter { it.status != DaoCellStatus.COMPLETED }
    .sortedByDescending { it.depositBlockNumber }
```

**Step 4: Build to verify**

Run: `cd android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoScreen.kt \
        android/app/src/main/java/com/rjnr/pocketnode/ui/screens/dao/DaoViewModel.kt
git commit -m "feat(dao): add error handling, snackbar, sort deposits (#36)"
```

---

## Task 15: Version Bump to v1.3.0

**Issue:** #38

**Files:**
- Modify: `android/app/build.gradle.kts`

**Context:** Bump version for the v1.3.0 release (DAO core functionality complete).

**Step 1: Update version in build.gradle.kts**

Change `versionCode` and `versionName`:
```kotlin
versionCode = <current + 1>
versionName = "1.3.0"
```

**Step 2: Build full APK to verify**

Run: `cd android && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "chore: bump version to 1.3.0 (#38)"
```

---

## Summary: Task Dependencies

```
Task 1 (Models/Constants) ── no deps
Task 2 (Rust JNI)         ── no deps (parallel with Task 1)
Task 3 (JNI Kotlin decls) ── after Task 2
Task 4 (Script Registration) ── after Task 1
Task 5 (Repository Query)   ── after Tasks 1, 3, 4
Task 6 (TransactionBuilder)  ── after Task 1
Task 7 (Repository Txns)     ── after Tasks 5, 6
Task 8 (ViewModel)           ── after Task 7
Task 9 (DaoScreen)           ── after Task 8
Task 10 (Cards + ProgressBar) ── after Task 9
Task 11 (Deposit Sheet)       ── after Task 9
Task 12 (Confirmations)       ── after Tasks 10, 11
Task 13 (Home Stake)           ── after Task 9
Task 14 (Polish)               ── after Task 12
Task 15 (Version Bump)         ── after Task 14
```

Tasks 1 and 2 can run in parallel. Tasks 10, 11, and 13 can run in parallel after Task 9.
