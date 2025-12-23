# CKB Wallet Gateway Server

REST API gateway that wraps the CKB light client for mobile wallet integration.

## Prerequisites

- Rust 1.70+
- Running CKB light client instance

## Setup

1. **Start CKB Light Client**

```bash
# Clone and build light client
git clone https://github.com/nervosnetwork/ckb-light-client.git
cd ckb-light-client
cargo build --release -p light-client-bin

# Run with testnet config
./target/release/ckb-light-client run --config-file config/testnet.toml
```

2. **Configure Gateway**

```bash
cp .env.example .env
# Edit .env as needed
```

3. **Run Gateway**

```bash
cargo run
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/v1/status` | Gateway health status |
| POST | `/v1/accounts/register` | Register address for tracking |
| GET | `/v1/accounts/:address/status` | Account sync status |
| GET | `/v1/accounts/:address/balance` | Account balance |
| GET | `/v1/accounts/:address/cells` | Account UTXOs |
| GET | `/v1/accounts/:address/transactions` | Transaction history |
| POST | `/v1/transactions/send` | Broadcast transaction |
| GET | `/v1/transactions/:tx_hash/status` | Get transaction status |

## Example Requests

### Register Account

Register an account to track its transactions. The `from_block` parameter controls how much history to sync:

```bash
# Default (~30 days of history) - recommended for most users
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq..."}'

# New wallet - sync from current tip only (fastest, no history)
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1q...", "from_block": "tip"}'

# Full history - sync from genesis (slowest, complete history)
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1q...", "from_block": "genesis"}'

# Custom block height - sync from specific block
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1q...", "from_block": "12000000"}'

# Relative blocks - sync last N blocks (e.g., last 100k blocks)
curl -X POST http://localhost:8080/v1/accounts/register \
  -H "Content-Type: application/json" \
  -d '{"address": "ckt1q...", "from_block": "-100000"}'
```

**Sync Options:**
| Option | Description | Use Case |
|--------|-------------|----------|
| `null` (default) | ~30 days / 200k blocks | Most users |
| `"tip"` or `"latest"` | Current block only | New wallets |
| `"genesis"` or `"0x0"` | From block 0 | Complete history |
| `"12345678"` | Specific block number | Known wallet creation |
| `"-100000"` | Last N blocks | Recent history only |

### Get Balance
```bash
curl http://localhost:8080/v1/accounts/ckt1qzda0cr.../balance
```

### Send Transaction
```bash
curl -X POST http://localhost:8080/v1/transactions/send \
  -H "Content-Type: application/json" \
  -d '{"transaction": {...}}'
```

### Get Transaction Status
```bash
curl http://localhost:8080/v1/transactions/0x1234.../status
```

Response:
```json
{
  "tx_hash": "0x1234...",
  "status": "committed",
  "confirmations": 5,
  "block_number": "0x123456",
  "block_hash": "0xabc...",
  "timestamp": 1699999999999
}
```

Status values:
- `pending` - Transaction in mempool
- `proposed` - Transaction in proposal stage
- `committed` - Transaction confirmed on chain
- `unknown` - Transaction not found

## Development

```bash
# Run with debug logging
RUST_LOG=debug cargo run

# Run tests
cargo test

# Build release
cargo build --release
```

## Docker

```bash
docker build -t ckb-wallet-gateway .
docker run -p 8080:8080 -e LIGHT_CLIENT_URL=http://host.docker.internal:9000 ckb-wallet-gateway
```
