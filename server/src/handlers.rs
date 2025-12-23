use crate::address::decode_address;
use crate::error::ApiError;
use crate::models::*;
use crate::AppState;
use axum::{
    extract::{Path, Query, State},
    Json,
};
use serde_json::Value;

/// Normalize a script for comparison (lowercase hex values)
fn normalize_script(script: &Script) -> Script {
    Script {
        code_hash: script.code_hash.to_lowercase(),
        hash_type: script.hash_type.to_lowercase(),
        args: script.args.to_lowercase(),
    }
}

/// Compare two scripts with normalization
fn scripts_equal(a: &Script, b: &Script) -> bool {
    let na = normalize_script(a);
    let nb = normalize_script(b);
    na.code_hash == nb.code_hash && na.hash_type == nb.hash_type && na.args == nb.args
}

// GET /v1/status
pub async fn get_status(State(state): State<AppState>) -> Result<Json<StatusResponse>, ApiError> {
    let tip = state.light_client.get_tip_header().await?;
    let peers = state.light_client.get_peers().await.unwrap_or_default();

    Ok(Json(StatusResponse {
        network: state.network.clone(),
        tip_number: tip.number,
        tip_hash: tip.hash,
        peer_count: peers.len(),
        is_synced: true,
        is_healthy: !peers.is_empty(),
    }))
}

// POST /v1/accounts/register
pub async fn register_account(
    State(state): State<AppState>,
    Json(body): Json<RegisterAccountRequest>,
) -> Result<Json<RegisterResponse>, ApiError> {
    // Get script from address or use provided
    let script = match (body.address.as_ref(), body.script) {
        (Some(addr), _) => decode_address(addr)?,
        (_, Some(s)) => s,
        _ => {
            return Err(ApiError::InvalidAddress(
                "address or script required".to_string(),
            ))
        }
    };

    // Get current tip for reference
    let tip = state.light_client.get_tip_header().await?;
    let tip_num = u64::from_str_radix(tip.number.trim_start_matches("0x"), 16).unwrap_or(0);

    // Determine starting block based on from_block parameter
    // Supported formats:
    //   - "tip" or "latest": Start from current tip (new wallets, no history)
    //   - "genesis" or "0x0": Start from block 0 (full history, slow)
    //   - "-N" (e.g., "-100000"): Relative, N blocks before tip
    //   - "0xHEX" or decimal: Specific block number
    //   - null/not provided: Default to ~30 days ago (reasonable recent history)
    let block_number = match body.from_block.as_deref() {
        Some("tip") | Some("latest") => {
            // New wallet - sync from current tip only
            tip.number.clone()
        }
        Some("genesis") => {
            // Full history from genesis
            "0x0".to_string()
        }
        Some(s) if s.starts_with('-') => {
            // Relative blocks: "-100000" means 100k blocks before tip
            let blocks_back = s[1..].parse::<u64>().unwrap_or(100_000);
            let start_block = tip_num.saturating_sub(blocks_back);
            format!("0x{:x}", start_block)
        }
        Some(s) if s.starts_with("0x") => {
            // Hex block number provided directly
            s.to_string()
        }
        Some(s) => {
            // Try to parse as decimal block number
            match s.parse::<u64>() {
                Ok(n) => format!("0x{:x}", n),
                Err(_) => {
                    // Invalid format, use default
                    let default_blocks_back: u64 = 200_000; // ~30 days on CKB
                    let start_block = tip_num.saturating_sub(default_blocks_back);
                    format!("0x{:x}", start_block)
                }
            }
        }
        None => {
            // Default: Start from ~30 days ago (roughly 200k blocks on CKB testnet)
            // This provides reasonable recent history without full sync
            let default_blocks_back: u64 = 200_000;
            let start_block = tip_num.saturating_sub(default_blocks_back);
            format!("0x{:x}", start_block)
        }
    };

    tracing::info!(
        "Registering account from block {} (tip: {})",
        block_number,
        tip.number
    );

    // Register with light client
    // First, delete any existing registration for this script to allow resync from new block
    let script_for_delete = ScriptStatus {
        script: script.clone(),
        script_type: "lock".to_string(),
        block_number: "0x0".to_string(), // block_number doesn't matter for delete
    };

    // Try to delete existing registration (ignore errors if not registered)
    let _ = state
        .light_client
        .set_scripts(vec![script_for_delete], "delete")
        .await;

    // Now register with the desired starting block
    let script_status = ScriptStatus {
        script,
        script_type: "lock".to_string(),
        block_number: block_number.clone(),
    };

    state
        .light_client
        .set_scripts(vec![script_status], "partial")
        .await?;

    Ok(Json(RegisterResponse {
        ok: true,
        syncing_from: block_number,
    }))
}

// GET /v1/accounts/:address/status
pub async fn get_account_status(
    State(state): State<AppState>,
    Path(address): Path<String>,
) -> Result<Json<AccountStatusResponse>, ApiError> {
    let script = decode_address(&address)?;
    let tip = state.light_client.get_tip_header().await?;
    let scripts = state.light_client.get_scripts().await?;

    // Find this script in registered scripts
    let script_status = scripts.iter().find(|s| s.script == script);

    let (is_registered, synced_to_block) = match script_status {
        Some(s) => (true, s.block_number.clone()),
        None => (false, "0x0".to_string()),
    };

    let tip_num = u64::from_str_radix(tip.number.trim_start_matches("0x"), 16).unwrap_or(0);
    let synced_num = u64::from_str_radix(synced_to_block.trim_start_matches("0x"), 16).unwrap_or(0);
    let sync_progress = if tip_num > 0 {
        synced_num as f64 / tip_num as f64
    } else {
        0.0
    };

    Ok(Json(AccountStatusResponse {
        address,
        is_registered,
        tip_number: tip.number,
        synced_to_block,
        sync_progress,
        is_synced: sync_progress >= 0.9999,
    }))
}

// GET /v1/accounts/:address/balance
pub async fn get_balance(
    State(state): State<AppState>,
    Path(address): Path<String>,
) -> Result<Json<BalanceResponse>, ApiError> {
    let script = decode_address(&address)?;

    let search_key = SearchKey {
        script,
        script_type: "lock".to_string(),
        script_search_mode: Some("prefix".to_string()),
        filter: None,
        with_data: None,
        group_by_transaction: None,
    };

    let result = state.light_client.get_cells_capacity(search_key).await?;

    let capacity_shannons =
        u64::from_str_radix(result.capacity.trim_start_matches("0x"), 16).unwrap_or(0);
    let capacity_ckb = capacity_shannons as f64 / 100_000_000.0;

    Ok(Json(BalanceResponse {
        address,
        capacity: result.capacity,
        capacity_ckb: format!("{:.8}", capacity_ckb),
        as_of_block: result.block_number,
    }))
}

// GET /v1/accounts/:address/cells
pub async fn get_cells(
    State(state): State<AppState>,
    Path(address): Path<String>,
    Query(query): Query<PaginationQuery>,
) -> Result<Json<CellsResponse>, ApiError> {
    let script = decode_address(&address)?;

    let limit = query.limit.unwrap_or(20).min(100);
    let limit_hex = format!("0x{:x}", limit);

    let search_key = SearchKey {
        script: script.clone(),
        script_type: "lock".to_string(),
        script_search_mode: Some("prefix".to_string()),
        filter: None,
        with_data: Some(true),
        group_by_transaction: None,
    };

    let result = state
        .light_client
        .get_cells(search_key, "asc", &limit_hex, query.cursor)
        .await?;

    let cells: Vec<Cell> = result
        .objects
        .into_iter()
        .map(|obj| Cell {
            out_point: obj.out_point,
            capacity: obj.output.capacity,
            block_number: obj.block_number,
            lock: obj.output.lock,
            type_script: obj.output.type_script,
            data: obj.output_data,
        })
        .collect();

    let next_cursor = if cells.len() == limit as usize {
        Some(result.last_cursor)
    } else {
        None
    };

    Ok(Json(CellsResponse {
        items: cells,
        next_cursor,
    }))
}

// GET /v1/accounts/:address/transactions
pub async fn get_transactions(
    State(state): State<AppState>,
    Path(address): Path<String>,
    Query(query): Query<PaginationQuery>,
) -> Result<Json<TransactionsResponse>, ApiError> {
    let script = decode_address(&address)?;

    let limit = query.limit.unwrap_or(50).min(100);
    let limit_hex = format!("0x{:x}", limit);

    tracing::debug!(
        "Fetching transactions for address: {}, limit: {}",
        address,
        limit
    );

    let search_key = SearchKey {
        script: script.clone(),
        script_type: "lock".to_string(),
        script_search_mode: Some("prefix".to_string()),
        filter: None,
        with_data: None,
        group_by_transaction: None,
    };

    let result = state
        .light_client
        .get_transactions(search_key, "desc", &limit_hex, query.cursor)
        .await?;

    tracing::debug!(
        "Light client returned {} transaction objects",
        result.objects.len()
    );

    // Log each object for debugging
    for (i, obj) in result.objects.iter().enumerate() {
        tracing::debug!(
            "  [{}] tx_hash={}, block={}, io_type={}, io_index={}",
            i,
            obj.tx_hash(),
            &obj.block_number,
            &obj.io_type,
            &obj.io_index
        );
    }

    let tip = state.light_client.get_tip_header().await?;
    let tip_num = u64::from_str_radix(tip.number.trim_start_matches("0x"), 16).unwrap_or(0);

    // Group transaction objects by tx_hash to calculate balance changes
    let mut tx_map: std::collections::HashMap<String, TxAccumulator> =
        std::collections::HashMap::new();

    for obj in &result.objects {
        let tx_hash = obj.tx_hash();
        let entry = tx_map
            .entry(tx_hash.to_string())
            .or_insert_with(|| TxAccumulator {
                input_capacity: 0i64,
                output_capacity: 0i64,
            });

        // Track direction based on io_type
        if obj.io_type == "input" {
            entry.input_capacity += 1;
        } else if obj.io_type == "output" {
            entry.output_capacity += 1;
        }
    }

    tracing::debug!("Unique transactions in map: {}", tx_map.len());

    // Now process each unique transaction (transaction is already embedded in the response)
    let mut items: Vec<TransactionRecord> = Vec::new();
    let mut seen_txs: std::collections::HashSet<String> = std::collections::HashSet::new();

    for obj in &result.objects {
        let tx_hash = obj.tx_hash().to_string();
        if !seen_txs.insert(tx_hash.clone()) {
            continue;
        }

        tracing::debug!("Processing transaction: {}", &tx_hash);

        let block_num =
            u64::from_str_radix(obj.block_number.trim_start_matches("0x"), 16).unwrap_or(0);
        let confirmations = if tip_num >= block_num {
            (tip_num - block_num) as u32
        } else {
            0
        };

        // Transaction is already embedded in the response - use it directly
        let tx = &obj.transaction;

        tracing::debug!(
            "  Transaction has {} inputs and {} outputs",
            tx.inputs.len(),
            tx.outputs.len()
        );

        // Calculate output capacity owned by this address
        let mut our_output_capacity: i64 = 0;
        // Calculate output capacity going to others (for calculating sent amount)
        let mut others_output_capacity: i64 = 0;

        // Calculate outputs
        for (idx, output) in tx.outputs.iter().enumerate() {
            let capacity = u64::from_str_radix(output.capacity.trim_start_matches("0x"), 16)
                .unwrap_or(0) as i64;

            let is_ours = scripts_equal(&output.lock, &script);
            tracing::debug!(
                "    Output[{}]: capacity={} shannons ({:.4} CKB), is_ours={}",
                idx,
                capacity,
                capacity as f64 / 100_000_000.0,
                is_ours
            );

            if is_ours {
                our_output_capacity += capacity;
            } else {
                others_output_capacity += capacity;
            }
        }

        tracing::debug!(
            "  Our output capacity: {} shannons ({:.4} CKB)",
            our_output_capacity,
            our_output_capacity as f64 / 100_000_000.0
        );
        tracing::debug!(
            "  Others output capacity: {} shannons ({:.4} CKB)",
            others_output_capacity,
            others_output_capacity as f64 / 100_000_000.0
        );

        // For inputs, we need to check the accumulator
        let acc = tx_map.get(&tx_hash);
        let has_our_input = acc.map(|a| a.input_capacity > 0).unwrap_or(false);
        let has_our_output = acc.map(|a| a.output_capacity > 0).unwrap_or(false);

        tracing::debug!(
            "  has_our_input={}, has_our_output={}",
            has_our_input,
            has_our_output
        );

        // Determine direction and calculate balance change
        // Key insight: if we have inputs AND there are outputs to others, we SENT something
        let (balance_change, direction) = if has_our_input && others_output_capacity > 0 {
            // We spent from our inputs and sent to someone else - this is an outgoing transaction
            // The amount sent is what went to others (excluding our change)
            (
                format!("0x{:x}", others_output_capacity as u64),
                "out".to_string(),
            )
        } else if has_our_input && has_our_output && others_output_capacity == 0 {
            // We have inputs and outputs but nothing went to others - self transfer (consolidation)
            (
                format!("0x{:x}", our_output_capacity as u64),
                "self".to_string(),
            )
        } else if has_our_output && !has_our_input {
            // Only outputs to us, no inputs from us - pure incoming
            (
                format!("0x{:x}", our_output_capacity as u64),
                "in".to_string(),
            )
        } else if has_our_input && !has_our_output {
            // Only inputs from us, no outputs to us - spent everything (rare)
            ("0x0".to_string(), "out".to_string())
        } else {
            ("0x0".to_string(), "unknown".to_string())
        };

        tracing::debug!("  direction={}, change={}", direction, balance_change);

        // Get block info for timestamp by fetching the header
        let (block_hash, timestamp) = match state.light_client.get_transaction(&tx_hash).await {
            Ok(tx_with_status) => match &tx_with_status.tx_status.block_hash {
                Some(hash) => match state.light_client.get_header(hash).await {
                    Ok(header) => {
                        let ts = u64::from_str_radix(header.timestamp.trim_start_matches("0x"), 16)
                            .unwrap_or(0);
                        (hash.clone(), ts)
                    }
                    Err(_) => (hash.clone(), 0),
                },
                None => (String::new(), 0),
            },
            Err(_) => (String::new(), 0),
        };

        let fee = "0x0".to_string();

        let record = TransactionRecord {
            tx_hash: tx_hash.clone(),
            block_number: obj.block_number.clone(),
            block_hash,
            timestamp,
            balance_change,
            direction,
            fee,
            confirmations,
        };

        tracing::debug!(
            "  Created record: tx={}, dir={}, amount={}, conf={}",
            &record.tx_hash,
            &record.direction,
            &record.balance_change,
            record.confirmations
        );

        items.push(record);
    }

    tracing::info!(
        "Returning {} transactions for address {}",
        items.len(),
        address
    );

    let next_cursor = if items.len() == limit as usize {
        Some(result.last_cursor)
    } else {
        None
    };

    Ok(Json(TransactionsResponse { items, next_cursor }))
}

// Helper struct for accumulating transaction info
struct TxAccumulator {
    input_capacity: i64,
    output_capacity: i64,
}

// GET /v1/debug/transactions/:address - Debug endpoint to see raw transaction data
pub async fn debug_transactions(
    State(state): State<AppState>,
    Path(address): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let script = decode_address(&address)?;

    let search_key = SearchKey {
        script: script.clone(),
        script_type: "lock".to_string(),
        script_search_mode: Some("prefix".to_string()),
        filter: None,
        with_data: None,
        group_by_transaction: None,
    };

    // Get parsed transactions with higher limit to catch all transactions
    let result = state
        .light_client
        .get_transactions(search_key, "desc", "0x64", None) // 100 transactions
        .await?;

    tracing::info!(
        "Debug: Light client returned {} raw transaction objects for address {}",
        result.objects.len(),
        address
    );

    // Process each unique transaction
    let mut tx_details: Vec<Value> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();

    for obj in &result.objects {
        let tx_hash = obj.tx_hash().to_string();
        if !seen.insert(tx_hash.clone()) {
            continue;
        }

        let tx = &obj.transaction;

        // Check each output
        let mut outputs_info: Vec<Value> = Vec::new();
        let mut our_total: i64 = 0;

        for (idx, output) in tx.outputs.iter().enumerate() {
            let is_ours = scripts_equal(&output.lock, &script);
            let capacity =
                u64::from_str_radix(output.capacity.trim_start_matches("0x"), 16).unwrap_or(0);

            if is_ours {
                our_total += capacity as i64;
            }

            outputs_info.push(serde_json::json!({
                "index": idx,
                "capacity_shannons": capacity,
                "capacity_ckb": capacity as f64 / 100_000_000.0,
                "is_ours": is_ours,
                "lock_args": output.lock.args,
            }));
        }

        tx_details.push(serde_json::json!({
            "tx_hash": tx_hash,
            "block_number": obj.block_number,
            "io_type": obj.io_type,
            "inputs_count": tx.inputs.len(),
            "outputs_count": tx.outputs.len(),
            "our_output_total_ckb": our_total as f64 / 100_000_000.0,
            "outputs": outputs_info,
        }));
    }

    Ok(Json(serde_json::json!({
        "address": address,
        "our_script": {
            "code_hash": script.code_hash,
            "hash_type": script.hash_type,
            "args": script.args,
        },
        "raw_objects_count": result.objects.len(),
        "unique_transactions": tx_details.len(),
        "transactions": tx_details,
    })))
}

// POST /v1/transactions/send
pub async fn send_transaction(
    State(state): State<AppState>,
    Json(body): Json<SendTransactionRequest>,
) -> Result<Json<SendTransactionResponse>, ApiError> {
    let tx_hash = state
        .light_client
        .send_transaction(body.transaction)
        .await?;

    Ok(Json(SendTransactionResponse { tx_hash }))
}

// GET /v1/transactions/:tx_hash/status
pub async fn get_transaction_status(
    State(state): State<AppState>,
    Path(tx_hash): Path<String>,
) -> Result<Json<TransactionStatusResponse>, ApiError> {
    let tip = state.light_client.get_tip_header().await?;
    let tip_num = u64::from_str_radix(tip.number.trim_start_matches("0x"), 16).unwrap_or(0);

    // Try to get transaction from light client
    match state.light_client.get_transaction(&tx_hash).await {
        Ok(tx_with_header) => {
            let status = &tx_with_header.tx_status.status;

            let (confirmations, block_number, timestamp) = if status == "committed" {
                // Get block info if committed
                if let Some(block_hash) = &tx_with_header.tx_status.block_hash {
                    match state.light_client.get_header(block_hash).await {
                        Ok(header) => {
                            let block_num =
                                u64::from_str_radix(header.number.trim_start_matches("0x"), 16)
                                    .unwrap_or(0);
                            let ts =
                                u64::from_str_radix(header.timestamp.trim_start_matches("0x"), 16)
                                    .unwrap_or(0);
                            let confs = if tip_num >= block_num {
                                (tip_num - block_num + 1) as u32
                            } else {
                                1
                            };
                            (Some(confs), Some(header.number), Some(ts))
                        }
                        Err(_) => (Some(1), None, None),
                    }
                } else {
                    (Some(1), None, None)
                }
            } else {
                (None, None, None)
            };

            Ok(Json(TransactionStatusResponse {
                tx_hash,
                status: status.clone(),
                confirmations,
                block_number,
                block_hash: tx_with_header.tx_status.block_hash,
                timestamp,
            }))
        }
        Err(_) => {
            // Transaction not found - might be pending in mempool or unknown
            Ok(Json(TransactionStatusResponse {
                tx_hash,
                status: "unknown".to_string(),
                confirmations: None,
                block_number: None,
                block_hash: None,
                timestamp: None,
            }))
        }
    }
}
