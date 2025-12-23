mod address;
mod error;
mod handlers;
mod light_client;
mod models;

use axum::{
    routing::{get, post},
    Router,
};
use std::sync::Arc;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use light_client::LightClientRpc;

#[derive(Clone)]
pub struct AppState {
    pub light_client: Arc<LightClientRpc>,
    pub network: String,
}

#[tokio::main]
async fn main() {
    // Initialize logging
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "ckb_wallet_gateway=debug,tower_http=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Load config from env
    dotenvy::dotenv().ok();
    let light_client_url =
        std::env::var("LIGHT_CLIENT_URL").unwrap_or_else(|_| "http://127.0.0.1:9000".to_string());
    let network = std::env::var("NETWORK").unwrap_or_else(|_| "testnet".to_string());
    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".to_string());

    tracing::info!("Connecting to light client at {}", light_client_url);
    tracing::info!("Network: {}", network);

    let light_client = Arc::new(LightClientRpc::new(&light_client_url));
    let state = AppState {
        light_client,
        network,
    };

    let app = Router::new()
        .route("/v1/status", get(handlers::get_status))
        .route("/v1/accounts/register", post(handlers::register_account))
        .route(
            "/v1/accounts/:address/status",
            get(handlers::get_account_status),
        )
        .route("/v1/accounts/:address/balance", get(handlers::get_balance))
        .route("/v1/accounts/:address/cells", get(handlers::get_cells))
        .route(
            "/v1/accounts/:address/transactions",
            get(handlers::get_transactions),
        )
        .route("/v1/transactions/send", post(handlers::send_transaction))
        .route(
            "/v1/transactions/:tx_hash/status",
            get(handlers::get_transaction_status),
        )
        .route(
            "/v1/debug/transactions/:address",
            get(handlers::debug_transactions),
        )
        .with_state(state)
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http());

    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", port))
        .await
        .expect("Failed to bind to port");

    tracing::info!("Gateway listening on port {}", port);

    axum::serve(listener, app).await.expect("Server failed");
}
