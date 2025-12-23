use serde::{Deserialize, Serialize};

// ============ CKB Core Types ============

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Script {
    pub code_hash: String,
    pub hash_type: String,
    pub args: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutPoint {
    pub tx_hash: String,
    pub index: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CellDep {
    pub out_point: OutPoint,
    pub dep_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Input {
    pub since: String,
    pub previous_output: OutPoint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Output {
    pub capacity: String,
    pub lock: Script,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub type_script: Option<Script>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transaction {
    pub version: String,
    pub cell_deps: Vec<CellDep>,
    pub header_deps: Vec<String>,
    pub inputs: Vec<Input>,
    pub outputs: Vec<Output>,
    pub outputs_data: Vec<String>,
    pub witnesses: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Cell {
    pub out_point: OutPoint,
    pub capacity: String,
    pub block_number: String,
    pub lock: Script,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub type_script: Option<Script>,
    #[serde(default)]
    pub data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Header {
    pub hash: String,
    pub number: String,
    pub timestamp: String,
    pub parent_hash: String,
    pub compact_target: String,
    pub version: String,
    pub epoch: String,
    pub dao: String,
    pub nonce: String,
    pub transactions_root: String,
    pub proposals_hash: String,
    pub extra_hash: String,
}

// ============ API Request Types ============

#[derive(Debug, Deserialize)]
pub struct RegisterAccountRequest {
    pub address: Option<String>,
    pub script: Option<Script>,
    pub from_block: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SendTransactionRequest {
    pub transaction: Transaction,
}

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    pub limit: Option<u32>,
    pub cursor: Option<String>,
}

// ============ API Response Types ============

#[derive(Debug, Serialize)]
pub struct StatusResponse {
    pub network: String,
    pub tip_number: String,
    pub tip_hash: String,
    pub peer_count: usize,
    pub is_synced: bool,
    pub is_healthy: bool,
}

#[derive(Debug, Serialize)]
pub struct RegisterResponse {
    pub ok: bool,
    pub syncing_from: String,
}

#[derive(Debug, Serialize)]
pub struct AccountStatusResponse {
    pub address: String,
    pub is_registered: bool,
    pub tip_number: String,
    pub synced_to_block: String,
    pub sync_progress: f64,
    pub is_synced: bool,
}

#[derive(Debug, Serialize)]
pub struct BalanceResponse {
    pub address: String,
    pub capacity: String,
    pub capacity_ckb: String,
    pub as_of_block: String,
}

#[derive(Debug, Serialize)]
pub struct CellsResponse {
    pub items: Vec<Cell>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct TransactionRecord {
    pub tx_hash: String,
    pub block_number: String,
    pub block_hash: String,
    pub timestamp: u64,
    pub balance_change: String,
    pub direction: String,
    pub fee: String,
    pub confirmations: u32,
}

#[derive(Debug, Serialize)]
pub struct TransactionsResponse {
    pub items: Vec<TransactionRecord>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct SendTransactionResponse {
    pub tx_hash: String,
}

#[derive(Debug, Serialize)]
pub struct TransactionStatusResponse {
    pub tx_hash: String,
    pub status: String, // "pending", "proposed", "committed", "unknown"
    pub confirmations: Option<u32>,
    pub block_number: Option<String>,
    pub block_hash: Option<String>,
    pub timestamp: Option<u64>,
}

// ============ Light Client RPC Types ============

#[derive(Debug, Serialize, Deserialize)]
pub struct JsonRpcRequest<T> {
    pub jsonrpc: String,
    pub id: u64,
    pub method: String,
    pub params: T,
}

#[derive(Debug, Deserialize)]
pub struct JsonRpcResponse<T> {
    pub jsonrpc: String,
    pub id: u64,
    pub result: Option<T>,
    pub error: Option<JsonRpcError>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct JsonRpcError {
    pub code: i64,
    pub message: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ScriptStatus {
    pub script: Script,
    pub script_type: String,
    pub block_number: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SearchKey {
    pub script: Script,
    pub script_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script_search_mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filter: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub with_data: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group_by_transaction: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct CellsCapacityResult {
    pub capacity: String,
    pub block_number: String,
    pub block_hash: String,
}

#[derive(Debug, Deserialize)]
pub struct GetCellsResult {
    pub last_cursor: String,
    pub objects: Vec<CellObject>,
}

#[derive(Debug, Deserialize)]
pub struct CellObject {
    pub out_point: OutPoint,
    pub output: Output,
    pub output_data: String,
    pub block_number: String,
    pub tx_index: String,
}

#[derive(Debug, Deserialize)]
pub struct GetTransactionsResult {
    pub last_cursor: String,
    pub objects: Vec<TransactionObject>,
}

/// Transaction object returned by light client get_transactions
/// The transaction is always embedded with its hash in transaction.hash
#[derive(Debug, Deserialize)]
pub struct TransactionObject {
    pub block_number: String,
    pub tx_index: String,
    #[serde(default)]
    pub io_type: String,
    #[serde(default)]
    pub io_index: String,
    /// The full transaction is embedded here
    pub transaction: TransactionView,
}

impl TransactionObject {
    /// Get the transaction hash from the embedded transaction
    pub fn tx_hash(&self) -> &str {
        &self.transaction.hash
    }
}

#[derive(Debug, Deserialize)]
pub struct TransactionView {
    /// Transaction hash
    pub hash: String,
    pub version: String,
    pub cell_deps: Vec<CellDep>,
    pub header_deps: Vec<String>,
    pub inputs: Vec<Input>,
    pub outputs: Vec<Output>,
    pub outputs_data: Vec<String>,
    pub witnesses: Vec<String>,
}

/// Response from get_transaction RPC
/// The light client returns transaction in a nested format
#[derive(Debug, Deserialize)]
pub struct TransactionWithHeader {
    pub transaction: TransactionWrapper,
    pub tx_status: TxStatus,
}

/// The transaction can be returned directly or wrapped in an "inner" field
#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub enum TransactionWrapper {
    /// Direct transaction format
    Direct(Transaction),
    /// Wrapped format with inner field (some CKB versions)
    Wrapped { inner: Transaction },
}

impl TransactionWrapper {
    pub fn inner(&self) -> &Transaction {
        match self {
            TransactionWrapper::Direct(tx) => tx,
            TransactionWrapper::Wrapped { inner } => inner,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TxStatus {
    pub status: String,
    pub block_hash: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct PeerInfo {
    pub node_id: String,
    pub addresses: Vec<String>,
    pub connected_duration: u64,
}
