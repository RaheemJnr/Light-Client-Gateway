use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ApiError {
    #[error("Invalid address: {0}")]
    InvalidAddress(String),

    #[error("Invalid script: {0}")]
    InvalidScript(String),

    #[error("Script not registered: {0}")]
    ScriptNotRegistered(String),

    #[error("Invalid transaction: {0}")]
    InvalidTransaction(String),

    #[error("Transaction rejected: {0}")]
    TransactionRejected(String),

    #[error("Light client error: {0}")]
    LightClientError(String),

    #[error("Internal error: {0}")]
    Internal(String),
}

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: ErrorDetail,
}

#[derive(Debug, Serialize)]
pub struct ErrorDetail {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code, message) = match &self {
            ApiError::InvalidAddress(msg) => (StatusCode::BAD_REQUEST, "INVALID_ADDRESS", msg.clone()),
            ApiError::InvalidScript(msg) => (StatusCode::BAD_REQUEST, "INVALID_SCRIPT", msg.clone()),
            ApiError::ScriptNotRegistered(msg) => (StatusCode::NOT_FOUND, "SCRIPT_NOT_REGISTERED", msg.clone()),
            ApiError::InvalidTransaction(msg) => (StatusCode::BAD_REQUEST, "INVALID_TRANSACTION", msg.clone()),
            ApiError::TransactionRejected(msg) => (StatusCode::UNPROCESSABLE_ENTITY, "TRANSACTION_REJECTED", msg.clone()),
            ApiError::LightClientError(msg) => (StatusCode::BAD_GATEWAY, "LIGHT_CLIENT_ERROR", msg.clone()),
            ApiError::Internal(msg) => (StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", msg.clone()),
        };

        let body = ErrorResponse {
            error: ErrorDetail {
                code: code.to_string(),
                message,
                details: None,
            },
        };

        (status, Json(body)).into_response()
    }
}

impl From<reqwest::Error> for ApiError {
    fn from(err: reqwest::Error) -> Self {
        ApiError::LightClientError(err.to_string())
    }
}

impl From<anyhow::Error> for ApiError {
    fn from(err: anyhow::Error) -> Self {
        ApiError::Internal(err.to_string())
    }
}
