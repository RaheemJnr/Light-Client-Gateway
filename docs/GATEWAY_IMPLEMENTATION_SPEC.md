# CKB Wallet Gateway - Complete Implementation Specification

> **Purpose**: This document is a complete, self-contained specification for building a CKB wallet from scratch using a remote gateway architecture. It requires no prior codebase context.

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [CKB Fundamentals](#ckb-fundamentals)
4. [Server Implementation](#part-1-server-implementation)
5. [Gateway API Specification](#part-2-gateway-api-specification)
6. [Android Client Implementation](#part-3-android-client-implementation)
7. [Complete Code Examples](#part-4-complete-code-examples)
8. [Testing & Deployment](#part-5-testing--deployment)
9. [Reference Data](#appendix-reference-data)

---

## Overview

### What We're Building
A CKB (Nervos Network) mobile wallet with:
- **Server**: Runs official CKB light client + exposes REST API
- **Android App**: Holds private keys locally, signs transactions on-device, queries chain state via gateway

### Why This Architecture
- Native CKB light client has Android networking issues (Tentacle P2P library)
- WASM approach has WebView limitations
- Gateway approach: fastest path to working product while maintaining clean abstraction

### Security Model
- **Keys never leave the device** - signing happens locally
- Gateway can only lie about state (like any untrusted RPC node)
- Gateway cannot steal funds (no access to private keys)

---

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│   Android App   │  HTTPS  │  Wallet Gateway  │  HTTP   │ CKB Light Client│
│                 │ ──────> │    (Your API)    │ ──────> │   (Official)    │
│ - Key Storage   │         │                  │         │                 │
│ - Tx Signing    │         │ - REST Endpoints │         │ - P2P Sync      │
│ - UI            │         │ - Address Parse  │         │ - RocksDB       │
└─────────────────┘         └──────────────────┘         └─────────────────┘
     Port: N/A                  Port: 8080                  Port: 9000
                                (public)                    (localhost only)
```

---

## CKB Fundamentals

### The Cell Model (UTXO-like)
CKB uses a **Cell model** similar to Bitcoin's UTXO but more powerful:

```
Cell = {
  capacity: u64,        // Size in shannons (1 CKB = 10^8 shannons)
  lock: Script,         // Who can spend this cell (like Bitcoin's scriptPubKey)
  type: Script | null,  // Optional: custom validation logic
  data: bytes           // Arbitrary data storage
}
```

### Scripts
A Script determines who can unlock/spend a cell:

```json
{
  "code_hash": "0x9bd7e06f...",  // Hash of the script code
  "hash_type": "type",           // "type" or "data" or "data1"
  "args": "0x..."                // Arguments passed to script (e.g., pubkey hash)
}
```

**Default Lock Script** (secp256k1-blake160):
- `code_hash`: Points to secp256k1 signature verification code
- `args`: First 20 bytes of Blake2b hash of compressed public key

### Addresses
CKB addresses encode a lock script in Bech32 format:
- Testnet prefix: `ckt`
- Mainnet prefix: `ckb`

Example: `ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq...`

### Transactions
```json
{
  "version": "0x0",
  "cell_deps": [...],      // Reference to code cells needed for validation
  "header_deps": [],       // Block headers (rarely used)
  "inputs": [...],         // Cells being consumed
  "outputs": [...],        // New cells being created
  "outputs_data": [...],   // Data for each output
  "witnesses": [...]       // Signatures and other proofs
}
```

**Key Rule**: `sum(input.capacity) >= sum(output.capacity) + fee`

### Minimum Cell Capacity
Every cell must hold enough CKB to cover its own storage:
- Empty cell (no data, basic lock): **61 CKB minimum**
- Formula: `capacity >= (lock_script_size + type_script_size + data_size + 8) * 1_0000_0000`

---

## Part 1: Server Implementation

### 1.1 Prerequisites
- Rust toolchain (for building light client)
- Your preferred backend language (Rust/Go/Node/Python)
- Linux server with 2GB+ RAM

### 1.2 CKB Light Client Setup

#### Install
```bash
# Clone official light client
git clone https://github.com/nervosnetwork/ckb-light-client.git
cd ckb-light-client

# Build release binary
cargo build --release -p light-client-bin

# Binary location
ls -la target/release/ckb-light-client
```

#### Configure (Testnet)
Create `config/testnet.toml`:

```toml
chain = "testnet"

[log]
filter = "info"
color = true

[rpc]
listen_address = "127.0.0.1:9000"

[network]
listen_addresses = ["/ip4/0.0.0.0/tcp/8118"]
max_outbound_peers = 8
max_inbound_peers = 8

# Testnet bootnodes (official)
bootnodes = [
    "/ip4/47.111.169.36/tcp/8111/p2p/QmNQ4jky6uVqLDrPU7snqxARuNGWNLgSrTnssbRuy3ij2W",
    "/ip4/47.74.66.72/tcp/8111/p2p/QmajBDXQfYjyJnt5MbqbMM7fFcNrWjJU7qpuL2k4qMGpxJ"
]

peer_store_path = "data/network/peer_store"

[store]
path = "data/db"
```

#### Configure (Mainnet)
Create `config/mainnet.toml`:

```toml
chain = "mainnet"

[log]
filter = "info"
color = true

[rpc]
listen_address = "127.0.0.1:9000"

[network]
listen_addresses = ["/ip4/0.0.0.0/tcp/8118"]
max_outbound_peers = 8
max_inbound_peers = 8

# Mainnet bootnodes (official)
bootnodes = [
    "/ip4/47.110.15.57/tcp/8114/p2p/QmXS4Kbc9HEeykHUTJCm2tNmqghbvWyYpUp6BtE5b6VpBs",
    "/ip4/47.111.169.36/tcp/8114/p2p/QmNQ4jky6uVqLDrPU7snqxARuNGWNLgSrTnssbRuy3ij2W"
]

peer_store_path = "data/network/peer_store"

[store]
path = "data/db"
```

#### Run
```bash
# Create data directory
mkdir -p data/{db,network}

# Run with testnet config
RUST_LOG=info ./target/release/ckb-light-client run \
    --config-file config/testnet.toml

# Expected output:
# INFO ckb_light_client::service  > Light client starting...
# INFO ckb_light_client::protocols::sync  > Syncing headers...
```

#### Verify RPC Works
```bash
# Get tip header
curl -s http://127.0.0.1:9000/ \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"get_tip_header","params":[]}' | jq

# Expected response:
# {
#   "jsonrpc": "2.0",
#   "result": {
#     "compact_target": "0x1a08a97e",
#     "hash": "0x...",
#     "number": "0x123456",
#     ...
#   },
#   "id": 1
# }
```

### 1.3 Light Client JSON-RPC Methods Reference

| Method | Description | Params |
|--------|-------------|--------|
| `get_tip_header` | Current chain tip | `[]` |
| `get_header` | Header by hash | `[block_hash]` |
| `get_peers` | Connected peers | `[]` |
| `set_scripts` | Register scripts to track | `[scripts[], command]` |
| `get_scripts` | List tracked scripts | `[]` |
| `get_cells` | Get cells for script | `[search_key, order, limit, cursor]` |
| `get_cells_capacity` | Total capacity for script | `[search_key]` |
| `get_transactions` | Transactions for script | `[search_key, order, limit, cursor]` |
| `send_transaction` | Broadcast tx | `[transaction]` |
| `get_transaction` | Get tx by hash | `[tx_hash]` |
| `fetch_transaction` | Fetch & return tx | `[tx_hash]` |

### 1.4 Gateway Service Implementation

Choose your language. Here's a complete example in **Rust with Axum**:

#### Cargo.toml
```toml
[package]
name = "ckb-wallet-gateway"
version = "0.1.0"
edition = "2021"

[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
reqwest = { version = "0.11", features = ["json"] }
tower-http = { version = "0.5", features = ["cors", "trace"] }
tracing = "0.1"
tracing-subscriber = "0.3"
anyhow = "1"
thiserror = "1"
hex = "0.4"
bech32 = "0.9"
blake2b-rs = "0.2"
```

#### src/main.rs
```rust
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::Json,
    routing::{get, post},
    Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

mod light_client;
mod address;
mod error;

use light_client::LightClientRpc;
use error::ApiError;

#[derive(Clone)]
struct AppState {
    light_client: Arc<LightClientRpc>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::init();

    let light_client = Arc::new(LightClientRpc::new("http://127.0.0.1:9000"));
    let state = AppState { light_client };

    let app = Router::new()
        .route("/v1/status", get(get_status))
        .route("/v1/accounts/register", post(register_account))
        .route("/v1/accounts/:address/status", get(get_account_status))
        .route("/v1/accounts/:address/balance", get(get_balance))
        .route("/v1/accounts/:address/cells", get(get_cells))
        .route("/v1/accounts/:address/transactions", get(get_transactions))
        .route("/v1/transactions/send", post(send_transaction))
        .with_state(state)
        .layer(tower_http::cors::CorsLayer::permissive());

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await.unwrap();
    tracing::info!("Gateway listening on port 8080");
    axum::serve(listener, app).await.unwrap();
}

// Endpoint implementations follow...
```

---

## Part 2: Gateway API Specification

### 2.1 Common Types

#### Script
```json
{
  "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
  "hash_type": "type",
  "args": "0x0123456789abcdef0123456789abcdef01234567"
}
```

#### OutPoint (Cell Reference)
```json
{
  "tx_hash": "0x...",
  "index": "0x0"
}
```

#### Cell
```json
{
  "out_point": { "tx_hash": "0x...", "index": "0x0" },
  "capacity": "0x174876e800",
  "lock": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
  "type": null,
  "data": "0x",
  "block_number": "0x12345"
}
```

#### Transaction
```json
{
  "version": "0x0",
  "cell_deps": [
    {
      "out_point": { "tx_hash": "0x...", "index": "0x0" },
      "dep_type": "dep_group"
    }
  ],
  "header_deps": [],
  "inputs": [
    {
      "since": "0x0",
      "previous_output": { "tx_hash": "0x...", "index": "0x0" }
    }
  ],
  "outputs": [
    {
      "capacity": "0x...",
      "lock": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
      "type": null
    }
  ],
  "outputs_data": ["0x"],
  "witnesses": ["0x..."]
}
```

#### Error Response
```json
{
  "error": {
    "code": "INVALID_ADDRESS",
    "message": "Human-readable error message",
    "details": {}
  }
}
```

**Error Codes:**
| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_ADDRESS` | 400 | Malformed CKB address |
| `INVALID_SCRIPT` | 400 | Malformed script structure |
| `SCRIPT_NOT_REGISTERED` | 404 | Script not tracked by light client |
| `INVALID_TRANSACTION` | 400 | Malformed transaction |
| `TRANSACTION_REJECTED` | 422 | Light client rejected transaction |
| `INSUFFICIENT_BALANCE` | 400 | Not enough capacity |
| `LIGHT_CLIENT_ERROR` | 502 | Upstream light client error |
| `RATE_LIMITED` | 429 | Too many requests |

### 2.2 Endpoints

---

#### `GET /v1/status`
Global gateway/light-client health.

**Response (200):**
```json
{
  "network": "testnet",
  "tip_number": "0x123456",
  "tip_hash": "0xabc...",
  "peer_count": 5,
  "is_synced": true,
  "is_healthy": true
}
```

**Server Logic:**
```rust
async fn get_status(State(state): State<AppState>) -> Result<Json<StatusResponse>, ApiError> {
    let tip = state.light_client.get_tip_header().await?;
    let peers = state.light_client.get_peers().await?;
    
    Ok(Json(StatusResponse {
        network: "testnet".to_string(),
        tip_number: tip.number,
        tip_hash: tip.hash,
        peer_count: peers.len(),
        is_synced: true, // Could compare with known tip
        is_healthy: peers.len() > 0,
    }))
}
```

---

#### `POST /v1/accounts/register`
Register a lock script for indexing.

**Request:**
```json
{
  "address": "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq...",
  "script": {
    "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
    "hash_type": "type",
    "args": "0x..."
  },
  "from_block": null
}
```

- `address`: CKB address (required if script omitted)
- `script`: Lock script (required if address omitted)  
- `from_block`: `null` (start from tip) or `"0x0"` (from genesis) or specific block number

**Response (201):**
```json
{
  "ok": true,
  "syncing_from": "0x123456"
}
```

**Server Logic:**
```rust
async fn register_account(
    State(state): State<AppState>,
    Json(body): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, ApiError> {
    // 1. Get script from address or use provided
    let script = match (body.address, body.script) {
        (Some(addr), _) => address::decode(&addr)?,
        (_, Some(s)) => s,
        _ => return Err(ApiError::InvalidRequest("address or script required")),
    };
    
    // 2. Determine starting block
    let block_number = match body.from_block {
        Some(n) => n,
        None => state.light_client.get_tip_header().await?.number,
    };
    
    // 3. Register with light client
    state.light_client.set_scripts(vec![ScriptStatus {
        script,
        script_type: "lock".to_string(),
        block_number,
    }], "partial").await?;
    
    Ok(Json(RegisterResponse {
        ok: true,
        syncing_from: block_number,
    }))
}
```

**Light Client RPC Call:**
```json
{
  "jsonrpc": "2.0",
  "method": "set_scripts",
  "params": [
    [
      {
        "script": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
        "script_type": "lock",
        "block_number": "0x123456"
      }
    ],
    "partial"
  ],
  "id": 1
}
```

---

#### `GET /v1/accounts/{address}/status`
Get sync status for an account.

**Response (200):**
```json
{
  "address": "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq...",
  "is_registered": true,
  "tip_number": "0x123456",
  "synced_to_block": "0x123400",
  "sync_progress": 0.9985,
  "is_synced": false
}
```

**Server Logic:**
1. Decode address to script
2. Call `get_scripts` to find this script's `block_number`
3. Call `get_tip_header` for chain tip
4. Calculate progress

---

#### `GET /v1/accounts/{address}/balance`
Get total capacity for an address.

**Response (200):**
```json
{
  "address": "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq...",
  "capacity": "0x174876e800",
  "capacity_ckb": "100.00000000",
  "as_of_block": "0x123456"
}
```

**Server Logic:**
```rust
async fn get_balance(
    State(state): State<AppState>,
    Path(address): Path<String>,
) -> Result<Json<BalanceResponse>, ApiError> {
    let script = address::decode(&address)?;
    
    let search_key = SearchKey {
        script,
        script_type: "lock".to_string(),
        script_search_mode: Some("prefix".to_string()),
        filter: None,
        with_data: None,
        group_by_transaction: None,
    };
    
    let result = state.light_client.get_cells_capacity(search_key).await?;
    
    let capacity_shannons: u64 = u64::from_str_radix(&result.capacity[2..], 16)?;
    let capacity_ckb = capacity_shannons as f64 / 100_000_000.0;
    
    Ok(Json(BalanceResponse {
        address,
        capacity: result.capacity,
        capacity_ckb: format!("{:.8}", capacity_ckb),
        as_of_block: result.block_number,
    }))
}
```

**Light Client RPC Call:**
```json
{
  "jsonrpc": "2.0",
  "method": "get_cells_capacity",
  "params": [
    {
      "script": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
      "script_type": "lock",
      "script_search_mode": "prefix"
    }
  ],
  "id": 1
}
```

---

#### `GET /v1/accounts/{address}/cells`
Get live cells (UTXOs) for transaction building.

**Query Params:**
- `limit`: Max cells to return (default: 20, max: 100)
- `cursor`: Pagination cursor from previous response

**Response (200):**
```json
{
  "items": [
    {
      "out_point": { "tx_hash": "0x...", "index": "0x0" },
      "capacity": "0x174876e800",
      "block_number": "0x12345",
      "lock": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
      "type": null,
      "data": "0x",
      "data_hash": "0x..."
    }
  ],
  "next_cursor": "0x..."
}
```

**Light Client RPC Call:**
```json
{
  "jsonrpc": "2.0",
  "method": "get_cells",
  "params": [
    {
      "script": { "code_hash": "0x...", "hash_type": "type", "args": "0x..." },
      "script_type": "lock",
      "script_search_mode": "prefix"
    },
    "asc",
    "0x14",
    null
  ],
  "id": 1
}
```

---

#### `GET /v1/accounts/{address}/transactions`
Get transaction history with computed balance changes.

**Query Params:**
- `limit`: Max transactions (default: 20, max: 100)
- `cursor`: Pagination cursor

**Response (200):**
```json
{
  "items": [
    {
      "tx_hash": "0x...",
      "block_number": "0x12345",
      "block_hash": "0x...",
      "timestamp": 1700000000000,
      "balance_change": "10000000000",
      "direction": "in",
      "fee": "0",
      "confirmations": 24
    }
  ],
  "next_cursor": null
}
```

**Server Logic (Balance Change Calculation):**
```rust
fn calculate_balance_change(tx: &Transaction, our_script: &Script) -> (i128, String) {
    // Sum outputs going to our script
    let out_sum: u64 = tx.outputs.iter()
        .filter(|o| &o.lock == our_script)
        .map(|o| parse_capacity(&o.capacity))
        .sum();
    
    // Sum inputs coming from our script (need to fetch previous outputs)
    let in_sum: u64 = /* fetch and sum previous outputs that match our_script */;
    
    let change = out_sum as i128 - in_sum as i128;
    let direction = match change.cmp(&0) {
        Ordering::Greater => "in",
        Ordering::Less => "out",
        Ordering::Equal => "self",
    };
    
    (change, direction.to_string())
}
```

---

#### `POST /v1/transactions/send`
Broadcast a signed transaction.

**Request:**
```json
{
  "transaction": {
    "version": "0x0",
    "cell_deps": [...],
    "header_deps": [],
    "inputs": [...],
    "outputs": [...],
    "outputs_data": [...],
    "witnesses": [...]
  }
}
```

**Response (201):**
```json
{
  "tx_hash": "0xabc123..."
}
```

**Response (422 - Rejected):**
```json
{
  "error": {
    "code": "TRANSACTION_REJECTED",
    "message": "PoolRejectedDuplicatedTransaction",
    "details": {
      "light_client_error": "..."
    }
  }
}
```

**Light Client RPC Call:**
```json
{
  "jsonrpc": "2.0",
  "method": "send_transaction",
  "params": [{ /* transaction */ }],
  "id": 1
}
```

---

### 2.3 Address Encoding/Decoding

CKB uses a custom address format based on Bech32/Bech32m:

#### Decode Address to Script (Server)
```rust
use bech32::{self, FromBase32, Variant};
use blake2b_rs::Blake2bBuilder;

pub fn decode_address(address: &str) -> Result<Script, AddressError> {
    let (hrp, data, variant) = bech32::decode(address)?;
    
    // Validate network
    match hrp.as_str() {
        "ckb" => { /* mainnet */ }
        "ckt" => { /* testnet */ }
        _ => return Err(AddressError::InvalidPrefix),
    }
    
    let payload = Vec::<u8>::from_base32(&data)?;
    
    // Format type is first byte
    let format_type = payload[0];
    
    match format_type {
        0x00 => {
            // Short format: code_hash_index (1 byte) + args (20 bytes)
            // 0x00 = SECP256K1_BLAKE160
            let args = &payload[1..21];
            Ok(Script {
                code_hash: SECP256K1_CODE_HASH.to_string(),
                hash_type: "type".to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        0x01 => {
            // Full format (deprecated)
            // code_hash (32) + hash_type (1) + args (variable)
            let code_hash = &payload[1..33];
            let hash_type = match payload[33] {
                0x00 => "data",
                0x01 => "type",
                0x02 => "data1",
                _ => return Err(AddressError::InvalidHashType),
            };
            let args = &payload[34..];
            Ok(Script {
                code_hash: format!("0x{}", hex::encode(code_hash)),
                hash_type: hash_type.to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        0x02 | 0x04 => {
            // Full format (new, Bech32m for 0x04)
            let code_hash = &payload[1..33];
            let hash_type = match payload[33] {
                0x00 => "data",
                0x01 => "type", 
                0x02 => "data1",
                _ => return Err(AddressError::InvalidHashType),
            };
            let args = &payload[34..];
            Ok(Script {
                code_hash: format!("0x{}", hex::encode(code_hash)),
                hash_type: hash_type.to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        _ => Err(AddressError::UnsupportedFormat),
    }
}

const SECP256K1_CODE_HASH: &str = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8";
```

#### Encode Script to Address
```rust
use bech32::{self, ToBase32, Variant};

pub fn encode_address(script: &Script, network: Network) -> Result<String, AddressError> {
    let hrp = match network {
        Network::Mainnet => "ckb",
        Network::Testnet => "ckt",
    };
    
    // For standard secp256k1 lock, use short format
    if script.code_hash == SECP256K1_CODE_HASH && script.hash_type == "type" {
        let args = hex::decode(&script.args[2..])?;
        if args.len() == 20 {
            let mut payload = vec![0x00]; // Short format + SECP256K1
            payload.extend_from_slice(&args);
            return Ok(bech32::encode(hrp, payload.to_base32(), Variant::Bech32)?);
        }
    }
    
    // Full format for other scripts
    let code_hash = hex::decode(&script.code_hash[2..])?;
    let hash_type_byte = match script.hash_type.as_str() {
        "data" => 0x00,
        "type" => 0x01,
        "data1" => 0x02,
        _ => return Err(AddressError::InvalidHashType),
    };
    let args = hex::decode(&script.args[2..])?;
    
    let mut payload = vec![0x04]; // New full format
    payload.extend_from_slice(&code_hash);
    payload.push(hash_type_byte);
    payload.extend_from_slice(&args);
    
    Ok(bech32::encode(hrp, payload.to_base32(), Variant::Bech32m)?)
}
```

---

## Part 3: Android Client Implementation

### 3.1 Project Setup

Create new Android project with these settings:
- **Name**: CKB Wallet
- **Package**: com.example.ckbwallet
- **Min SDK**: 26 (Android 8.0)
- **Language**: Kotlin
- **Build**: Gradle Kotlin DSL

### 3.2 Dependencies

**app/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.ckbwallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ckbwallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    
    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // Crypto
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.androidx.security.crypto)
    
    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    
    // Room (for caching)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
```

**libs.versions.toml:**
```toml
[versions]
kotlin = "2.0.0"
ktor = "2.3.12"
secp256k1 = "0.15.0"
hilt = "2.51.1"
room = "2.6.1"
securityCrypto = "1.1.0-alpha06"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
secp256k1-kmp-jni-android = { module = "fr.acinq.secp256k1:secp256k1-kmp-jni-android", version.ref = "secp256k1" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt-android = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.0-1.0.22" }
```

### 3.3 Project Structure

```
app/src/main/java/com/example/ckbwallet/
├── CkbWalletApp.kt                    # Application class
├── MainActivity.kt
├── data/
│   ├── gateway/
│   │   ├── GatewayApi.kt              # Ktor API client
│   │   ├── GatewayRepository.kt       # Repository implementation
│   │   └── models/                    # API request/response models
│   │       ├── ApiModels.kt
│   │       └── CkbModels.kt
│   ├── wallet/
│   │   ├── KeyManager.kt              # Key generation & storage
│   │   ├── AddressUtils.kt            # Address encoding/decoding
│   │   └── TransactionBuilder.kt      # Tx building & signing
│   └── crypto/
│       └── Blake2b.kt                 # Blake2b hashing
├── di/
│   ├── AppModule.kt                   # Hilt modules
│   └── NetworkModule.kt
├── ui/
│   ├── navigation/
│   │   └── NavGraph.kt
│   ├── screens/
│   │   ├── home/
│   │   │   ├── HomeScreen.kt
│   │   │   └── HomeViewModel.kt
│   │   ├── send/
│   │   │   ├── SendScreen.kt
│   │   │   └── SendViewModel.kt
│   │   └── receive/
│   │       └── ReceiveScreen.kt
│   └── components/
│       └── CommonComponents.kt
└── util/
    └── Extensions.kt
```

---

## Part 4: Complete Code Examples

### 4.1 CKB Data Models

**data/gateway/models/CkbModels.kt:**
```kotlin
package com.example.ckbwallet.data.gateway.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Script(
    @SerialName("code_hash") val codeHash: String,
    @SerialName("hash_type") val hashType: String,
    val args: String
) {
    companion object {
        // Testnet secp256k1-blake160 code hash
        const val SECP256K1_CODE_HASH_TESTNET = 
            "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
        // Mainnet is the same
        const val SECP256K1_CODE_HASH_MAINNET = SECP256K1_CODE_HASH_TESTNET
    }
}

@Serializable
data class OutPoint(
    @SerialName("tx_hash") val txHash: String,
    val index: String
)

@Serializable
data class CellDep(
    @SerialName("out_point") val outPoint: OutPoint,
    @SerialName("dep_type") val depType: String
) {
    companion object {
        // Testnet secp256k1 cell dep
        val SECP256K1_TESTNET = CellDep(
            outPoint = OutPoint(
                txHash = "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37",
                index = "0x0"
            ),
            depType = "dep_group"
        )
        
        // Mainnet secp256k1 cell dep
        val SECP256K1_MAINNET = CellDep(
            outPoint = OutPoint(
                txHash = "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c",
                index = "0x0"
            ),
            depType = "dep_group"
        )
    }
}

@Serializable
data class Input(
    val since: String = "0x0",
    @SerialName("previous_output") val previousOutput: OutPoint
)

@Serializable
data class Output(
    val capacity: String,
    val lock: Script,
    val type: Script? = null
)

@Serializable
data class Transaction(
    val version: String = "0x0",
    @SerialName("cell_deps") val cellDeps: List<CellDep>,
    @SerialName("header_deps") val headerDeps: List<String> = emptyList(),
    val inputs: List<Input>,
    val outputs: List<Output>,
    @SerialName("outputs_data") val outputsData: List<String>,
    val witnesses: List<String>
)

@Serializable
data class Cell(
    @SerialName("out_point") val outPoint: OutPoint,
    val capacity: String,
    @SerialName("block_number") val blockNumber: String,
    val lock: Script,
    val type: Script? = null,
    val data: String = "0x"
) {
    fun capacityAsLong(): Long = capacity.removePrefix("0x").toLong(16)
}

@Serializable
data class TransactionRecord(
    @SerialName("tx_hash") val txHash: String,
    @SerialName("block_number") val blockNumber: String,
    @SerialName("block_hash") val blockHash: String,
    val timestamp: Long,
    @SerialName("balance_change") val balanceChange: String,
    val direction: String, // "in", "out", "self"
    val fee: String,
    val confirmations: Int
)

enum class NetworkType(val hrp: String) {
    TESTNET("ckt"),
    MAINNET("ckb")
}
```

### 4.2 API Models

**data/gateway/models/ApiModels.kt:**
```kotlin
package com.example.ckbwallet.data.gateway.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Requests

@Serializable
data class RegisterAccountRequest(
    val address: String? = null,
    val script: Script? = null,
    @SerialName("from_block") val fromBlock: String? = null
)

@Serializable
data class SendTransactionRequest(
    val transaction: Transaction
)

// Responses

@Serializable
data class StatusResponse(
    val network: String,
    @SerialName("tip_number") val tipNumber: String,
    @SerialName("tip_hash") val tipHash: String,
    @SerialName("peer_count") val peerCount: Int,
    @SerialName("is_synced") val isSynced: Boolean,
    @SerialName("is_healthy") val isHealthy: Boolean
)

@Serializable
data class RegisterResponse(
    val ok: Boolean,
    @SerialName("syncing_from") val syncingFrom: String
)

@Serializable
data class AccountStatusResponse(
    val address: String,
    @SerialName("is_registered") val isRegistered: Boolean,
    @SerialName("tip_number") val tipNumber: String,
    @SerialName("synced_to_block") val syncedToBlock: String,
    @SerialName("sync_progress") val syncProgress: Double,
    @SerialName("is_synced") val isSynced: Boolean
)

@Serializable
data class BalanceResponse(
    val address: String,
    val capacity: String,
    @SerialName("capacity_ckb") val capacityCkb: String,
    @SerialName("as_of_block") val asOfBlock: String
) {
    fun capacityAsLong(): Long = capacity.removePrefix("0x").toLong(16)
    fun capacityAsCkb(): Double = capacityCkb.toDoubleOrNull() ?: 0.0
}

@Serializable
data class CellsResponse(
    val items: List<Cell>,
    @SerialName("next_cursor") val nextCursor: String?
)

@Serializable
data class TransactionsResponse(
    val items: List<TransactionRecord>,
    @SerialName("next_cursor") val nextCursor: String?
)

@Serializable
data class SendTransactionResponse(
    @SerialName("tx_hash") val txHash: String
)

@Serializable
data class ApiError(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)
```

### 4.3 Gateway API Client

**data/gateway/GatewayApi.kt:**
```kotlin
package com.example.ckbwallet.data.gateway

import com.example.ckbwallet.data.gateway.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayApi @Inject constructor() {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
    
    // Configure this for your gateway URL
    var baseUrl: String = "https://your-gateway.example.com"
    
    suspend fun getStatus(): Result<StatusResponse> = runCatching {
        client.get("$baseUrl/v1/status").body()
    }
    
    suspend fun registerAccount(request: RegisterAccountRequest): Result<RegisterResponse> = runCatching {
        client.post("$baseUrl/v1/accounts/register") {
            setBody(request)
        }.body()
    }
    
    suspend fun getAccountStatus(address: String): Result<AccountStatusResponse> = runCatching {
        client.get("$baseUrl/v1/accounts/$address/status").body()
    }
    
    suspend fun getBalance(address: String): Result<BalanceResponse> = runCatching {
        client.get("$baseUrl/v1/accounts/$address/balance").body()
    }
    
    suspend fun getCells(
        address: String,
        limit: Int = 20,
        cursor: String? = null
    ): Result<CellsResponse> = runCatching {
        client.get("$baseUrl/v1/accounts/$address/cells") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()
    }
    
    suspend fun getTransactions(
        address: String,
        limit: Int = 20,
        cursor: String? = null
    ): Result<TransactionsResponse> = runCatching {
        client.get("$baseUrl/v1/accounts/$address/transactions") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()
    }
    
    suspend fun sendTransaction(request: SendTransactionRequest): Result<SendTransactionResponse> = runCatching {
        client.post("$baseUrl/v1/transactions/send") {
            setBody(request)
        }.body()
    }
}
```

### 4.4 Blake2b Implementation

**data/crypto/Blake2b.kt:**
```kotlin
package com.example.ckbwallet.data.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blake2b-256 implementation with CKB personalization.
 * 
 * CKB uses Blake2b with specific personalizations:
 * - "ckb-default-hash" for general hashing (tx hash, sighash)
 * - "ckb-personalization" for script hashes
 */
@Singleton
class Blake2b @Inject constructor() {
    
    companion object {
        private const val HASH_LENGTH = 32
        private val CKB_HASH_PERSONALIZATION = "ckb-default-hash".toByteArray()
    }
    
    /**
     * Blake2b-256 hash with CKB default personalization.
     */
    fun hash(input: ByteArray): ByteArray {
        return blake2b(input, CKB_HASH_PERSONALIZATION)
    }
    
    /**
     * Hash with custom personalization.
     */
    fun hashWithPersonalization(input: ByteArray, personalization: ByteArray): ByteArray {
        return blake2b(input, personalization)
    }
    
    /**
     * Create a hasher for incremental hashing (for signing messages).
     */
    fun newHasher(): Blake2bHasher {
        return Blake2bHasher(CKB_HASH_PERSONALIZATION)
    }
    
    // Pure Kotlin Blake2b implementation
    // In production, consider using a native library for performance
    private fun blake2b(input: ByteArray, personalization: ByteArray): ByteArray {
        val hasher = Blake2bHasher(personalization)
        hasher.update(input)
        return hasher.finalize()
    }
}

/**
 * Incremental Blake2b hasher.
 */
class Blake2bHasher(personalization: ByteArray) {
    
    private val state = LongArray(8)
    private val buffer = ByteArray(128)
    private var bufferOffset = 0
    private var totalBytes = 0L
    
    init {
        // Initialize state with IV XOR parameter block
        val iv = longArrayOf(
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL,
            0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL,
            0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
        )
        
        // Parameter block (simplified for 32-byte output, no key)
        val paramBlock = ByteArray(64)
        paramBlock[0] = 32  // digest length
        paramBlock[1] = 0   // key length
        paramBlock[2] = 1   // fanout
        paramBlock[3] = 1   // depth
        
        // Copy personalization (up to 16 bytes) to offset 48
        val persLen = minOf(personalization.size, 16)
        System.arraycopy(personalization, 0, paramBlock, 48, persLen)
        
        // XOR IV with parameter block
        val paramWords = bytesToLongs(paramBlock)
        for (i in 0..7) {
            state[i] = iv[i] xor paramWords[i]
        }
    }
    
    fun update(input: ByteArray): Blake2bHasher {
        var offset = 0
        var remaining = input.size
        
        // Fill buffer
        while (remaining > 0) {
            val toCopy = minOf(remaining, 128 - bufferOffset)
            System.arraycopy(input, offset, buffer, bufferOffset, toCopy)
            bufferOffset += toCopy
            offset += toCopy
            remaining -= toCopy
            
            if (bufferOffset == 128) {
                totalBytes += 128
                compress(buffer, false)
                bufferOffset = 0
            }
        }
        
        return this
    }
    
    fun finalize(): ByteArray {
        totalBytes += bufferOffset
        
        // Pad with zeros
        for (i in bufferOffset until 128) {
            buffer[i] = 0
        }
        
        compress(buffer, true)
        
        // Extract hash (first 32 bytes of state)
        val result = ByteArray(32)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..3) {
            buf.putLong(state[i])
        }
        
        return result
    }
    
    private fun compress(block: ByteArray, isFinal: Boolean) {
        val v = LongArray(16)
        val m = bytesToLongs(block)
        
        // Initialize working vector
        System.arraycopy(state, 0, v, 0, 8)
        v[8] = 0x6a09e667f3bcc908L
        v[9] = 0xbb67ae8584caa73bL
        v[10] = 0x3c6ef372fe94f82bL
        v[11] = 0xa54ff53a5f1d36f1L
        v[12] = 0x510e527fade682d1L xor totalBytes
        v[13] = 0x9b05688c2b3e6c1fL
        v[14] = if (isFinal) 0x1f83d9abfb41bd6bL.inv() else 0x1f83d9abfb41bd6bL
        v[15] = 0x5be0cd19137e2179L
        
        // 12 rounds of mixing
        val sigma = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
        )
        
        for (round in 0 until 12) {
            val s = sigma[round % 10]
            mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        
        // Update state
        for (i in 0..7) {
            state[i] = state[i] xor v[i] xor v[i + 8]
        }
    }
    
    private fun mix(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }
    
    private fun Long.rotateRight(n: Int): Long = (this ushr n) or (this shl (64 - n))
    
    private fun bytesToLongs(bytes: ByteArray): LongArray {
        val result = LongArray(bytes.size / 8)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in result.indices) {
            result[i] = buf.getLong()
        }
        return result
    }
}
```

### 4.5 Key Manager

**data/wallet/KeyManager.kt:**
```kotlin
package com.example.ckbwallet.data.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.ckbwallet.data.crypto.Blake2b
import com.example.ckbwallet.data.gateway.models.NetworkType
import com.example.ckbwallet.data.gateway.models.Script
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blake2b: Blake2b
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            "ckb_wallet_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    fun hasWallet(): Boolean = prefs.contains(KEY_PRIVATE_KEY)
    
    /**
     * Generate a new random private key.
     */
    fun generateWallet(): WalletInfo {
        val privateKey = ByteArray(32)
        SecureRandom().nextBytes(privateKey)
        
        // Validate it's a valid secp256k1 private key
        require(Secp256k1.secKeyVerify(privateKey)) { "Invalid private key generated" }
        
        savePrivateKey(privateKey)
        return getWalletInfo()
    }
    
    /**
     * Import existing private key (32 bytes hex).
     */
    fun importWallet(privateKeyHex: String): WalletInfo {
        val privateKey = privateKeyHex.removePrefix("0x").hexToBytes()
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        require(Secp256k1.secKeyVerify(privateKey)) { "Invalid private key" }
        
        savePrivateKey(privateKey)
        return getWalletInfo()
    }
    
    /**
     * Get current wallet info.
     */
    fun getWalletInfo(): WalletInfo {
        val privateKey = getPrivateKey()
        val publicKey = derivePublicKey(privateKey)
        val script = deriveLockScript(publicKey)
        val testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET)
        val mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET)
        
        return WalletInfo(
            publicKey = publicKey.toHex(),
            script = script,
            testnetAddress = testnetAddress,
            mainnetAddress = mainnetAddress
        )
    }
    
    /**
     * Get private key for signing.
     */
    fun getPrivateKey(): ByteArray {
        val hex = prefs.getString(KEY_PRIVATE_KEY, null)
            ?: throw IllegalStateException("No wallet found")
        return hex.hexToBytes()
    }
    
    /**
     * Derive compressed public key from private key.
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        return Secp256k1.pubkeyCreate(privateKey)
    }
    
    /**
     * Derive lock script from public key (secp256k1-blake160).
     */
    fun deriveLockScript(publicKey: ByteArray): Script {
        // Blake2b hash of public key, take first 20 bytes
        val pubKeyHash = blake2b.hash(publicKey).take(20).toByteArray()
        
        return Script(
            codeHash = Script.SECP256K1_CODE_HASH_TESTNET,
            hashType = "type",
            args = "0x" + pubKeyHash.toHex()
        )
    }
    
    /**
     * Sign a message with the private key.
     */
    fun sign(message: ByteArray): ByteArray {
        val privateKey = getPrivateKey()
        val signature = Secp256k1.sign(message, privateKey)
        
        // CKB expects 65-byte signature: r (32) + s (32) + recovery_id (1)
        val compactSig = Secp256k1.compact(signature)
        val recoveryId = Secp256k1.recover(signature, message, null)
        
        return compactSig + byteArrayOf(recoveryId.toByte())
    }
    
    /**
     * Delete wallet (clear keys).
     */
    fun deleteWallet() {
        prefs.edit().remove(KEY_PRIVATE_KEY).apply()
    }
    
    private fun savePrivateKey(privateKey: ByteArray) {
        prefs.edit().putString(KEY_PRIVATE_KEY, privateKey.toHex()).apply()
    }
    
    companion object {
        private const val KEY_PRIVATE_KEY = "private_key"
    }
}

data class WalletInfo(
    val publicKey: String,
    val script: Script,
    val testnetAddress: String,
    val mainnetAddress: String
)

// Extension functions
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val hex = removePrefix("0x")
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun List<Byte>.toByteArray(): ByteArray = this.toByteArray()
```

### 4.6 Address Utilities

**data/wallet/AddressUtils.kt:**
```kotlin
package com.example.ckbwallet.data.wallet

import com.example.ckbwallet.data.gateway.models.NetworkType
import com.example.ckbwallet.data.gateway.models.Script

/**
 * CKB address encoding/decoding utilities.
 * 
 * Address format: bech32/bech32m encoded payload
 * Payload structure varies by format type.
 */
object AddressUtils {
    
    private const val SECP256K1_CODE_HASH = 
        "9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
    
    /**
     * Encode a script to CKB address.
     */
    fun encode(script: Script, network: NetworkType): String {
        val hrp = network.hrp
        
        // For standard secp256k1-blake160, use short format
        val codeHash = script.codeHash.removePrefix("0x")
        val args = script.args.removePrefix("0x").hexToBytes()
        
        if (codeHash == SECP256K1_CODE_HASH && 
            script.hashType == "type" && 
            args.size == 20) {
            // Short format: 0x00 (format) + args
            val payload = byteArrayOf(0x00) + args
            return Bech32.encode(hrp, payload, Bech32.Variant.BECH32)
        }
        
        // Full format (0x04 for bech32m)
        val codeHashBytes = codeHash.hexToBytes()
        val hashTypeByte = when (script.hashType) {
            "data" -> 0x00.toByte()
            "type" -> 0x01.toByte()
            "data1" -> 0x02.toByte()
            else -> throw IllegalArgumentException("Invalid hash type: ${script.hashType}")
        }
        
        val payload = byteArrayOf(0x04) + codeHashBytes + hashTypeByte + args
        return Bech32.encode(hrp, payload, Bech32.Variant.BECH32M)
    }
    
    /**
     * Decode a CKB address to script.
     */
    fun decode(address: String): Script {
        val (hrp, payload, _) = Bech32.decode(address)
        
        // Validate network prefix
        require(hrp == "ckb" || hrp == "ckt") { "Invalid address prefix: $hrp" }
        
        val formatType = payload[0].toInt() and 0xFF
        
        return when (formatType) {
            0x00 -> {
                // Short format: secp256k1-blake160
                require(payload.size == 21) { "Invalid short format address" }
                val args = payload.sliceArray(1..20)
                Script(
                    codeHash = "0x$SECP256K1_CODE_HASH",
                    hashType = "type",
                    args = "0x" + args.toHex()
                )
            }
            0x01, 0x02, 0x04 -> {
                // Full format
                require(payload.size >= 34) { "Invalid full format address" }
                val codeHash = payload.sliceArray(1..32)
                val hashType = when (payload[33].toInt() and 0xFF) {
                    0x00 -> "data"
                    0x01 -> "type"
                    0x02 -> "data1"
                    else -> throw IllegalArgumentException("Invalid hash type byte")
                }
                val args = payload.sliceArray(34 until payload.size)
                Script(
                    codeHash = "0x" + codeHash.toHex(),
                    hashType = hashType,
                    args = "0x" + args.toHex()
                )
            }
            else -> throw IllegalArgumentException("Unsupported address format: $formatType")
        }
    }
    
    /**
     * Validate an address without fully decoding.
     */
    fun isValid(address: String): Boolean {
        return try {
            decode(address)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Bech32/Bech32m encoding implementation.
 */
object Bech32 {
    
    enum class Variant { BECH32, BECH32M }
    
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.apply {
        CHARSET.forEachIndexed { i, c -> this[c.code] = i }
    }
    
    fun encode(hrp: String, data: ByteArray, variant: Variant): String {
        val values = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, values, variant)
        
        val combined = values + checksum
        val result = StringBuilder(hrp.length + 1 + combined.size)
        result.append(hrp)
        result.append('1')
        combined.forEach { result.append(CHARSET[it]) }
        
        return result.toString()
    }
    
    fun decode(str: String): Triple<String, ByteArray, Variant> {
        val lower = str.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= lower.length) { "Invalid bech32 string" }
        
        val hrp = lower.substring(0, pos)
        val dataStr = lower.substring(pos + 1)
        
        val data = IntArray(dataStr.length) {
            val c = dataStr[it].code
            require(c < 128 && CHARSET_REV[c] != -1) { "Invalid character" }
            CHARSET_REV[c]
        }
        
        val variant = verifyChecksum(hrp, data)
        val payload = convertBits(data.dropLast(6).map { it.toByte() }.toByteArray(), 5, 8, false)
        
        return Triple(hrp, payload, variant)
    }
    
    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }
    
    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c ->
            result[i] = c.code shr 5
            result[i + hrp.length + 1] = c.code and 31
        }
        return result
    }
    
    private fun verifyChecksum(hrp: String, data: IntArray): Variant {
        val values = hrpExpand(hrp) + data
        return when (polymod(values)) {
            1 -> Variant.BECH32
            0x2bc830a3 -> Variant.BECH32M
            else -> throw IllegalArgumentException("Invalid checksum")
        }
    }
    
    private fun createChecksum(hrp: String, data: IntArray, variant: Variant): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val target = when (variant) {
            Variant.BECH32 -> 1
            Variant.BECH32M -> 0x2bc830a3
        }
        val polymod = polymod(values) xor target
        return IntArray(6) { (polymod shr (5 * (5 - it))) and 31 }
    }
    
    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        
        for (b in data) {
            val value = b.toInt() and 0xFF
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        
        if (pad && bits > 0) {
            result.add((acc shl (toBits - bits)) and maxv)
        }
        
        return result.toIntArray()
    }
}
```

### 4.7 Transaction Builder

**data/wallet/TransactionBuilder.kt:**
```kotlin
package com.example.ckbwallet.data.wallet

import com.example.ckbwallet.data.crypto.Blake2b
import com.example.ckbwallet.data.gateway.models.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionBuilder @Inject constructor(
    private val blake2b: Blake2b,
    private val keyManager: KeyManager
) {
    companion object {
        // Minimum capacity for a cell (61 CKB for empty cell with secp256k1 lock)
        const val MIN_CELL_CAPACITY = 6_100_000_000L // 61 CKB in shannons
        
        // Default fee (0.0001 CKB = 10000 shannons)
        const val DEFAULT_FEE = 10_000L
    }
    
    /**
     * Build a simple CKB transfer transaction.
     */
    fun buildTransfer(
        fromScript: Script,
        toAddress: String,
        amountShannons: Long,
        feeShannons: Long = DEFAULT_FEE,
        availableCells: List<Cell>,
        network: NetworkType = NetworkType.TESTNET
    ): Result<Transaction> = runCatching {
        // 1. Validate amount
        require(amountShannons >= MIN_CELL_CAPACITY) {
            "Amount must be at least ${MIN_CELL_CAPACITY / 100_000_000.0} CKB"
        }
        
        // 2. Decode destination address
        val toScript = AddressUtils.decode(toAddress)
        
        // 3. Select inputs (simple first-fit)
        val requiredCapacity = amountShannons + feeShannons
        val selectedCells = selectCells(availableCells, requiredCapacity)
        
        val totalInput = selectedCells.sumOf { it.capacityAsLong() }
        require(totalInput >= requiredCapacity) {
            "Insufficient balance. Need $requiredCapacity, have $totalInput"
        }
        
        // 4. Build outputs
        val outputs = mutableListOf<Output>()
        val outputsData = mutableListOf<String>()
        
        // Output to recipient
        outputs.add(Output(
            capacity = "0x${amountShannons.toString(16)}",
            lock = toScript
        ))
        outputsData.add("0x")
        
        // Change output (if needed)
        val change = totalInput - amountShannons - feeShannons
        if (change >= MIN_CELL_CAPACITY) {
            outputs.add(Output(
                capacity = "0x${change.toString(16)}",
                lock = fromScript
            ))
            outputsData.add("0x")
        } else if (change > 0) {
            // Change too small for a cell, add to fee
            // (This is a simplification; real wallet might warn user)
        }
        
        // 5. Build inputs
        val inputs = selectedCells.map { cell ->
            Input(
                since = "0x0",
                previousOutput = cell.outPoint
            )
        }
        
        // 6. Cell deps (secp256k1)
        val cellDeps = listOf(
            when (network) {
                NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
                NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
            }
        )
        
        // 7. Build unsigned transaction with witness placeholders
        val witnessPlaceholders = inputs.mapIndexed { index, _ ->
            if (index == 0) {
                // First witness needs lock placeholder (65 bytes of zeros)
                serializeWitnessArgs(WitnessArgs(lock = ByteArray(65)))
            } else {
                "0x"
            }
        }
        
        val unsignedTx = Transaction(
            version = "0x0",
            cellDeps = cellDeps,
            headerDeps = emptyList(),
            inputs = inputs,
            outputs = outputs,
            outputsData = outputsData,
            witnesses = witnessPlaceholders
        )
        
        // 8. Sign transaction
        signTransaction(unsignedTx)
    }
    
    /**
     * Sign a transaction.
     */
    private fun signTransaction(tx: Transaction): Transaction {
        // 1. Compute transaction hash
        val txHash = computeTxHash(tx)
        
        // 2. Build signing message
        val message = buildSigningMessage(txHash, tx.witnesses)
        
        // 3. Sign with private key
        val signature = keyManager.sign(message)
        
        // 4. Build signed witness
        val signedWitness = serializeWitnessArgs(WitnessArgs(lock = signature))
        
        // 5. Replace first witness
        val signedWitnesses = tx.witnesses.toMutableList()
        signedWitnesses[0] = signedWitness
        
        return tx.copy(witnesses = signedWitnesses)
    }
    
    /**
     * Compute transaction hash (without witnesses).
     */
    private fun computeTxHash(tx: Transaction): ByteArray {
        // Serialize raw transaction (without witnesses)
        val rawTx = serializeRawTransaction(tx)
        return blake2b.hash(rawTx)
    }
    
    /**
     * Build the message to sign.
     * 
     * sighash = blake2b(
     *   tx_hash ||
     *   witness_length || witness_bytes ||
     *   witness_length || witness_bytes ||
     *   ...
     * )
     */
    private fun buildSigningMessage(txHash: ByteArray, witnesses: List<String>): ByteArray {
        val hasher = blake2b.newHasher()
        
        // Add tx hash
        hasher.update(txHash)
        
        // Add each witness with length prefix
        witnesses.forEach { witnessHex ->
            val witnessBytes = witnessHex.removePrefix("0x").hexToBytes()
            val lengthBytes = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(witnessBytes.size.toLong())
                .array()
            hasher.update(lengthBytes)
            hasher.update(witnessBytes)
        }
        
        return hasher.finalize()
    }
    
    /**
     * Select cells for transaction (simple first-fit).
     */
    private fun selectCells(cells: List<Cell>, required: Long): List<Cell> {
        val selected = mutableListOf<Cell>()
        var total = 0L
        
        for (cell in cells.sortedByDescending { it.capacityAsLong() }) {
            if (total >= required) break
            selected.add(cell)
            total += cell.capacityAsLong()
        }
        
        return selected
    }
    
    /**
     * Serialize raw transaction (without witnesses) using Molecule encoding.
     */
    private fun serializeRawTransaction(tx: Transaction): ByteArray {
        // This is a simplified implementation
        // Full Molecule serialization is more complex
        
        val parts = mutableListOf<ByteArray>()
        
        // Version (4 bytes, little endian)
        parts.add(intToBytes(tx.version.removePrefix("0x").toInt(16)))
        
        // Cell deps
        parts.add(serializeCellDeps(tx.cellDeps))
        
        // Header deps
        parts.add(serializeBytesList(tx.headerDeps.map { it.removePrefix("0x").hexToBytes() }))
        
        // Inputs
        parts.add(serializeInputs(tx.inputs))
        
        // Outputs
        parts.add(serializeOutputs(tx.outputs))
        
        // Outputs data
        parts.add(serializeBytesList(tx.outputsData.map { it.removePrefix("0x").hexToBytes() }))
        
        return serializeTable(parts)
    }
    
    private fun serializeTable(fields: List<ByteArray>): ByteArray {
        val offsets = mutableListOf<Int>()
        var currentOffset = 4 + fields.size * 4 // header size + offset table
        
        fields.forEach { field ->
            offsets.add(currentOffset)
            currentOffset += field.size
        }
        
        val result = ByteBuffer.allocate(currentOffset).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(currentOffset) // total size
        offsets.forEach { result.putInt(it) }
        fields.forEach { result.put(it) }
        
        return result.array()
    }
    
    private fun serializeCellDeps(cellDeps: List<CellDep>): ByteArray {
        val items = cellDeps.map { dep ->
            val txHash = dep.outPoint.txHash.removePrefix("0x").hexToBytes()
            val index = intToBytes(dep.outPoint.index.removePrefix("0x").toInt(16))
            val depType = byteArrayOf(if (dep.depType == "code") 0 else 1)
            txHash + index + depType
        }
        return serializeFixedVector(items)
    }
    
    private fun serializeInputs(inputs: List<Input>): ByteArray {
        val items = inputs.map { input ->
            val since = longToBytes(input.since.removePrefix("0x").toLong(16))
            val txHash = input.previousOutput.txHash.removePrefix("0x").hexToBytes()
            val index = intToBytes(input.previousOutput.index.removePrefix("0x").toInt(16))
            since + txHash + index
        }
        return serializeFixedVector(items)
    }
    
    private fun serializeOutputs(outputs: List<Output>): ByteArray {
        val items = outputs.map { output ->
            val capacity = longToBytes(output.capacity.removePrefix("0x").toLong(16))
            val lock = serializeScript(output.lock)
            val type = output.type?.let { serializeScript(it) } ?: byteArrayOf()
            serializeTable(listOf(capacity, lock, type))
        }
        return serializeDynVector(items)
    }
    
    private fun serializeScript(script: Script): ByteArray {
        val codeHash = script.codeHash.removePrefix("0x").hexToBytes()
        val hashType = byteArrayOf(
            when (script.hashType) {
                "data" -> 0
                "type" -> 1
                "data1" -> 2
                else -> throw IllegalArgumentException("Invalid hash type")
            }
        )
        val args = script.args.removePrefix("0x").hexToBytes()
        return serializeTable(listOf(codeHash, hashType, serializeBytes(args)))
    }
    
    private fun serializeBytes(data: ByteArray): ByteArray {
        val result = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(data.size)
        result.put(data)
        return result.array()
    }
    
    private fun serializeBytesList(items: List<ByteArray>): ByteArray {
        return serializeDynVector(items.map { serializeBytes(it) })
    }
    
    private fun serializeFixedVector(items: List<ByteArray>): ByteArray {
        val totalSize = 4 + items.sumOf { it.size }
        val result = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(items.size)
        items.forEach { result.put(it) }
        return result.array()
    }
    
    private fun serializeDynVector(items: List<ByteArray>): ByteArray {
        if (items.isEmpty()) {
            return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array()
        }
        
        val headerSize = 4 + items.size * 4
        var offset = headerSize
        val offsets = items.map { item ->
            val currentOffset = offset
            offset += item.size
            currentOffset
        }
        
        val result = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(offset) // total size
        offsets.forEach { result.putInt(it) }
        items.forEach { result.put(it) }
        
        return result.array()
    }
    
    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
    
    private fun longToBytes(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }
    
    private fun serializeWitnessArgs(args: WitnessArgs): String {
        val lock = args.lock?.let { serializeBytes(it) } ?: byteArrayOf()
        val inputType = args.inputType?.let { serializeBytes(it) } ?: byteArrayOf()
        val outputType = args.outputType?.let { serializeBytes(it) } ?: byteArrayOf()
        
        val serialized = serializeTable(listOf(lock, inputType, outputType))
        return "0x" + serialized.toHex()
    }
}

data class WitnessArgs(
    val lock: ByteArray? = null,
    val inputType: ByteArray? = null,
    val outputType: ByteArray? = null
)
```

### 4.8 Repository

**data/gateway/GatewayRepository.kt:**
```kotlin
package com.example.ckbwallet.data.gateway

import com.example.ckbwallet.data.gateway.models.*
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.TransactionBuilder
import com.example.ckbwallet.data.wallet.WalletInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayRepository @Inject constructor(
    private val api: GatewayApi,
    private val keyManager: KeyManager,
    private val txBuilder: TransactionBuilder
) {
    private val _walletInfo = MutableStateFlow<WalletInfo?>(null)
    val walletInfo: StateFlow<WalletInfo?> = _walletInfo.asStateFlow()
    
    private val _balance = MutableStateFlow<BalanceResponse?>(null)
    val balance: StateFlow<BalanceResponse?> = _balance.asStateFlow()
    
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()
    
    val network: NetworkType = NetworkType.TESTNET // Configure as needed
    
    /**
     * Initialize wallet (create or load existing).
     */
    suspend fun initializeWallet(): Result<WalletInfo> = runCatching {
        val info = if (keyManager.hasWallet()) {
            keyManager.getWalletInfo()
        } else {
            keyManager.generateWallet()
        }
        _walletInfo.value = info
        info
    }
    
    /**
     * Import wallet from private key.
     */
    suspend fun importWallet(privateKeyHex: String): Result<WalletInfo> = runCatching {
        val info = keyManager.importWallet(privateKeyHex)
        _walletInfo.value = info
        _isRegistered.value = false
        info
    }
    
    /**
     * Register account with gateway.
     */
    suspend fun registerAccount(): Result<Unit> {
        val info = _walletInfo.value ?: return Result.failure(Exception("Wallet not initialized"))
        val address = when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }
        
        return api.registerAccount(
            RegisterAccountRequest(
                address = address,
                script = info.script,
                fromBlock = null // Start from current tip
            )
        ).map {
            _isRegistered.value = true
        }
    }
    
    /**
     * Get current balance.
     */
    suspend fun refreshBalance(): Result<BalanceResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        
        return api.getBalance(address).onSuccess {
            _balance.value = it
        }
    }
    
    /**
     * Get account status.
     */
    suspend fun getAccountStatus(): Result<AccountStatusResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getAccountStatus(address)
    }
    
    /**
     * Get cells (UTXOs).
     */
    suspend fun getCells(limit: Int = 100, cursor: String? = null): Result<CellsResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getCells(address, limit, cursor)
    }
    
    /**
     * Get transaction history.
     */
    suspend fun getTransactions(limit: Int = 20, cursor: String? = null): Result<TransactionsResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getTransactions(address, limit, cursor)
    }
    
    /**
     * Send CKB to an address.
     */
    suspend fun sendCkb(
        toAddress: String,
        amountCkb: Double,
        feeCkb: Double = 0.0001
    ): Result<String> {
        val info = _walletInfo.value ?: return Result.failure(Exception("Wallet not initialized"))
        
        val amountShannons = (amountCkb * 100_000_000).toLong()
        val feeShannons = (feeCkb * 100_000_000).toLong()
        
        // 1. Fetch available cells
        val cellsResult = getCells(limit = 100)
        if (cellsResult.isFailure) {
            return Result.failure(cellsResult.exceptionOrNull()!!)
        }
        val cells = cellsResult.getOrThrow().items
        
        // 2. Build and sign transaction
        val txResult = txBuilder.buildTransfer(
            fromScript = info.script,
            toAddress = toAddress,
            amountShannons = amountShannons,
            feeShannons = feeShannons,
            availableCells = cells,
            network = network
        )
        if (txResult.isFailure) {
            return Result.failure(txResult.exceptionOrNull()!!)
        }
        
        // 3. Broadcast
        return api.sendTransaction(
            SendTransactionRequest(transaction = txResult.getOrThrow())
        ).map { it.txHash }
    }
    
    /**
     * Get gateway status.
     */
    suspend fun getGatewayStatus(): Result<StatusResponse> = api.getStatus()
    
    private fun getCurrentAddress(): String? {
        val info = _walletInfo.value ?: return null
        return when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }
    }
}
```

### 4.9 Dependency Injection

**di/AppModule.kt:**
```kotlin
package com.example.ckbwallet.di

import android.content.Context
import com.example.ckbwallet.data.crypto.Blake2b
import com.example.ckbwallet.data.gateway.GatewayApi
import com.example.ckbwallet.data.gateway.GatewayRepository
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.TransactionBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideBlake2b(): Blake2b = Blake2b()
    
    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context,
        blake2b: Blake2b
    ): KeyManager = KeyManager(context, blake2b)
    
    @Provides
    @Singleton
    fun provideGatewayApi(): GatewayApi = GatewayApi()
    
    @Provides
    @Singleton
    fun provideTransactionBuilder(
        blake2b: Blake2b,
        keyManager: KeyManager
    ): TransactionBuilder = TransactionBuilder(blake2b, keyManager)
    
    @Provides
    @Singleton
    fun provideGatewayRepository(
        api: GatewayApi,
        keyManager: KeyManager,
        txBuilder: TransactionBuilder
    ): GatewayRepository = GatewayRepository(api, keyManager, txBuilder)
}
```

### 4.10 Home Screen

**ui/screens/home/HomeViewModel.kt:**
```kotlin
package com.example.ckbwallet.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ckbwallet.data.gateway.GatewayRepository
import com.example.ckbwallet.data.gateway.models.TransactionRecord
import com.example.ckbwallet.data.wallet.WalletInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            initializeWallet()
        }
        
        // Observe wallet info changes
        viewModelScope.launch {
            repository.walletInfo.collect { info ->
                _uiState.update { it.copy(walletInfo = info) }
            }
        }
        
        // Observe balance changes
        viewModelScope.launch {
            repository.balance.collect { balance ->
                _uiState.update { 
                    it.copy(balanceCkb = balance?.capacityAsCkb() ?: 0.0)
                }
            }
        }
    }
    
    private suspend fun initializeWallet() {
        _uiState.update { it.copy(isLoading = true) }
        
        repository.initializeWallet()
            .onSuccess { info ->
                _uiState.update { it.copy(walletInfo = info, isLoading = false) }
                registerAndRefresh()
            }
            .onFailure { error ->
                _uiState.update { 
                    it.copy(error = error.message, isLoading = false)
                }
            }
    }
    
    private suspend fun registerAndRefresh() {
        // Register with gateway
        repository.registerAccount()
            .onSuccess {
                refresh()
            }
            .onFailure { error ->
                _uiState.update { it.copy(error = "Registration failed: ${error.message}") }
            }
    }
    
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            
            // Refresh balance
            repository.refreshBalance()
            
            // Refresh transactions
            repository.getTransactions()
                .onSuccess { response ->
                    _uiState.update { 
                        it.copy(
                            transactions = response.items,
                            isRefreshing = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            error = error.message,
                            isRefreshing = false
                        )
                    }
                }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val walletInfo: WalletInfo? = null,
    val balanceCkb: Double = 0.0,
    val transactions: List<TransactionRecord> = emptyList(),
    val error: String? = null
)
```

**ui/screens/home/HomeScreen.kt:**
```kotlin
package com.example.ckbwallet.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.ckbwallet.data.gateway.models.TransactionRecord
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CKB Wallet") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Address Card
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Address", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.walletInfo?.testnetAddress ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        uiState.walletInfo?.testnetAddress?.let {
                                            clipboardManager.setText(AnnotatedString(it))
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Copy")
                                }
                            }
                        }
                    }
                }
                
                // Balance Card
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Balance", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "%.8f CKB".format(uiState.balanceCkb),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }
                
                // Action Buttons
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = onNavigateToSend,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send")
                        }
                        OutlinedButton(
                            onClick = onNavigateToReceive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Receive")
                        }
                    }
                }
                
                // Transactions Header
                item {
                    Text(
                        text = "Recent Transactions",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                // Transaction List
                if (uiState.transactions.isEmpty()) {
                    item {
                        Text(
                            text = "No transactions yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(uiState.transactions) { tx ->
                        TransactionItem(tx)
                    }
                }
            }
        }
        
        // Error Snackbar
        uiState.error?.let { error ->
            LaunchedEffect(error) {
                // Show snackbar
                viewModel.clearError()
            }
        }
    }
}

@Composable
fun TransactionItem(tx: TransactionRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = when (tx.direction) {
                        "in" -> "Received"
                        "out" -> "Sent"
                        else -> "Self Transfer"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                        .format(Date(tx.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${if (tx.direction == "in") "+" else "-"}${
                    "%.4f".format(tx.balanceChange.toLong() / 100_000_000.0)
                } CKB",
                style = MaterialTheme.typography.bodyLarge,
                color = if (tx.direction == "in") 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.error
            )
        }
    }
}
```

---

## Part 5: Testing & Deployment

### 5.1 Local Development

1. **Run light client locally:**
```bash
cd ckb-light-client
./target/release/ckb-light-client run --config-file config/testnet.toml
```

2. **Run gateway locally:**
```bash
cd ckb-wallet-gateway
cargo run
# or for Node.js: npm run dev
```

3. **Configure Android app:**
```kotlin
// In GatewayApi.kt
var baseUrl: String = "http://10.0.2.2:8080" // Android emulator localhost
```

### 5.2 Testing Checklist

- [ ] Light client syncs and responds to RPC
- [ ] Gateway health endpoint returns OK
- [ ] Account registration succeeds
- [ ] Balance query returns correct amount
- [ ] Cell fetching works
- [ ] Transaction building produces valid tx
- [ ] Transaction signing produces valid signature
- [ ] Transaction broadcast succeeds
- [ ] Transaction appears in history after confirmation

### 5.3 Production Deployment

**Server (Docker Compose):**
```yaml
version: '3.8'

services:
  light-client:
    build: ./ckb-light-client
    volumes:
      - ./data/light-client:/app/data
    networks:
      - internal
    restart: unless-stopped

  gateway:
    build: ./gateway
    ports:
      - "8080:8080"
    environment:
      - LIGHT_CLIENT_URL=http://light-client:9000
      - RUST_LOG=info
    depends_on:
      - light-client
    networks:
      - internal
      - external
    restart: unless-stopped

  nginx:
    image: nginx:alpine
    ports:
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./certs:/etc/nginx/certs
    depends_on:
      - gateway
    networks:
      - external
    restart: unless-stopped

networks:
  internal:
  external:
```

**Android Release:**
1. Update `baseUrl` to production gateway URL
2. Enable ProGuard/R8 minification
3. Sign APK with release keystore
4. Test on physical device before release

---

## Appendix: Reference Data

### Testnet Cell Deps

**secp256k1-blake160:**
```json
{
  "out_point": {
    "tx_hash": "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37",
    "index": "0x0"
  },
  "dep_type": "dep_group"
}
```

**secp256k1-blake160 code_hash:** 
```
0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8
```

### Mainnet Cell Deps

**secp256k1-blake160:**
```json
{
  "out_point": {
    "tx_hash": "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c",
    "index": "0x0"
  },
  "dep_type": "dep_group"
}
```

### Useful Links

- [CKB Light Client Repo](https://github.com/nervosnetwork/ckb-light-client)
- [CKB RPC Documentation](https://github.com/nervosnetwork/ckb/tree/develop/rpc)
- [CKB Address RFC](https://github.com/nervosnetwork/rfcs/blob/master/rfcs/0021-ckb-address-format/0021-ckb-address-format.md)
- [Molecule Serialization](https://github.com/nervosnetwork/molecule)
- [CKB Explorer (Testnet)](https://pudge.explorer.nervos.org/)
- [CKB Faucet (Testnet)](https://faucet.nervos.org/)

### Shannons Conversion

```
1 CKB = 100,000,000 shannons (10^8)
1 shannon = 0.00000001 CKB
```

### Minimum Cell Capacities

| Cell Type | Minimum Capacity |
|-----------|------------------|
| Empty (secp256k1 lock) | 61 CKB |
| With type script | 102 CKB |
| With data (per byte) | +1 CKB per byte |

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2024-12 | Initial specification |
