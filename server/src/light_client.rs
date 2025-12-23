use crate::error::ApiError;
use crate::models::*;
use reqwest::Client;
use serde_json::Value;
use std::sync::atomic::{AtomicU64, Ordering};

pub struct LightClientRpc {
    client: Client,
    url: String,
    request_id: AtomicU64,
}

impl LightClientRpc {
    pub fn new(url: &str) -> Self {
        Self {
            client: Client::new(),
            url: url.to_string(),
            request_id: AtomicU64::new(1),
        }
    }

    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::SeqCst)
    }

    async fn call<T, R>(&self, method: &str, params: T) -> Result<R, ApiError>
    where
        T: serde::Serialize,
        R: serde::de::DeserializeOwned,
    {
        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: method.to_string(),
            params,
        };

        let response = self.client.post(&self.url).json(&request).send().await?;

        let rpc_response: JsonRpcResponse<R> = response.json().await?;

        if let Some(error) = rpc_response.error {
            return Err(ApiError::LightClientError(format!(
                "{}: {}",
                error.code, error.message
            )));
        }

        rpc_response
            .result
            .ok_or_else(|| ApiError::LightClientError("Empty response".to_string()))
    }

    pub async fn get_tip_header(&self) -> Result<Header, ApiError> {
        self.call("get_tip_header", Vec::<()>::new()).await
    }

    pub async fn get_header(&self, block_hash: &str) -> Result<Header, ApiError> {
        self.call("get_header", vec![block_hash]).await
    }

    pub async fn get_peers(&self) -> Result<Vec<PeerInfo>, ApiError> {
        self.call("get_peers", Vec::<()>::new()).await
    }

    pub async fn set_scripts(
        &self,
        scripts: Vec<ScriptStatus>,
        command: &str,
    ) -> Result<(), ApiError> {
        // set_scripts returns null on success, so we need special handling
        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "set_scripts".to_string(),
            params: (scripts, command),
        };

        let response = self.client.post(&self.url).json(&request).send().await?;

        let rpc_response: JsonRpcResponse<serde_json::Value> = response.json().await?;

        if let Some(error) = rpc_response.error {
            return Err(ApiError::LightClientError(format!(
                "{}: {}",
                error.code, error.message
            )));
        }

        // set_scripts returns null on success, which is fine
        Ok(())
    }

    pub async fn get_scripts(&self) -> Result<Vec<ScriptStatus>, ApiError> {
        self.call("get_scripts", Vec::<()>::new()).await
    }

    pub async fn get_cells_capacity(
        &self,
        search_key: SearchKey,
    ) -> Result<CellsCapacityResult, ApiError> {
        self.call("get_cells_capacity", vec![search_key]).await
    }

    pub async fn get_cells(
        &self,
        search_key: SearchKey,
        order: &str,
        limit: &str,
        cursor: Option<String>,
    ) -> Result<GetCellsResult, ApiError> {
        self.call("get_cells", (search_key, order, limit, cursor))
            .await
    }

    pub async fn get_transactions(
        &self,
        search_key: SearchKey,
        order: &str,
        limit: &str,
        cursor: Option<String>,
    ) -> Result<GetTransactionsResult, ApiError> {
        self.call("get_transactions", (search_key, order, limit, cursor))
            .await
    }

    pub async fn get_transaction(&self, tx_hash: &str) -> Result<TransactionWithHeader, ApiError> {
        self.call("get_transaction", vec![tx_hash]).await
    }

    pub async fn send_transaction(&self, tx: Transaction) -> Result<String, ApiError> {
        self.call("send_transaction", vec![tx]).await
    }

    /// Get raw JSON response from get_transactions for debugging
    pub async fn get_transactions_raw(
        &self,
        search_key: SearchKey,
        order: &str,
        limit: &str,
        cursor: Option<String>,
    ) -> Result<Value, ApiError> {
        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "get_transactions".to_string(),
            params: (search_key, order, limit, cursor),
        };

        let response = self.client.post(&self.url).json(&request).send().await?;
        let json: Value = response.json().await?;

        Ok(json)
    }
}
