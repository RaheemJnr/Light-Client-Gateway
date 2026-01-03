# CKB Wallet Gateway - Deployment Guide

This guide covers deploying the CKB Wallet Gateway to a VPS (Virtual Private Server).

## Prerequisites

- **VPS Requirements:**
  - Ubuntu 22.04 LTS or Debian 12
  - Minimum 2 vCPU, 4GB RAM, 40GB SSD
  - Root/sudo access

- **Recommended VPS Providers:**
  | Provider | Specs | Price/Month |
  |----------|-------|-------------|
  | [Hetzner](https://www.hetzner.com/cloud) | 2 vCPU, 4GB RAM, 40GB | ~€7 |
  | [DigitalOcean](https://www.digitalocean.com) | 2 vCPU, 4GB RAM, 80GB | ~$24 |
  | [Vultr](https://www.vultr.com) | 2 vCPU, 4GB RAM, 80GB | ~$24 |

## Quick Start (Automated Setup)

### 1. Create a VPS

1. Sign up for a VPS provider (Hetzner recommended for cost)
2. Create a new server with Ubuntu 22.04 LTS
3. Note your server's IP address

### 2. Connect to Your Server

```bash
ssh root@YOUR_SERVER_IP
```

### 3. Run the Setup Script

```bash
# Download and run the setup script
curl -sSL https://raw.githubusercontent.com/RaheemJnr/Light-Client-Gateway/main/deployment/setup-vps.sh -o setup-vps.sh
chmod +x setup-vps.sh
sudo ./setup-vps.sh
```

**With a domain (for SSL):**
```bash
sudo ./setup-vps.sh --domain api.yourdomain.com
```

### 4. Wait for Sync

The CKB light client needs time to sync with the network. Monitor progress:

```bash
docker compose -f /opt/ckb-wallet-gateway/deployment/docker-compose.yml logs -f light-client
```

## Manual Setup

If you prefer to set up manually:

### 1. Install Docker

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sh

# Start Docker
sudo systemctl start docker
sudo systemctl enable docker
```

### 2. Clone Repository

```bash
sudo git clone https://github.com/RaheemJnr/Light-Client-Gateway.git /opt/ckb-wallet-gateway
cd /opt/ckb-wallet-gateway/deployment
```

### 3. Configure Environment

```bash
# Create .env file
cat > .env << EOF
RUST_LOG=info
SERVER_PORT=8080
LIGHT_CLIENT_URL=http://light-client:9000
CKB_NETWORK=testnet
EOF
```

### 4. Build and Start

```bash
# Build images
docker compose build

# Start services
docker compose up -d
```

## Verifying the Deployment

### Check Service Status

```bash
docker compose ps
```

Expected output:
```
NAME               STATUS              PORTS
ckb-gateway        Up                  0.0.0.0:8080->8080/tcp
ckb-light-client   Up                  0.0.0.0:9000->9000/tcp
```

### Test the API

```bash
# Health check
curl http://localhost:8080/v1/status

# Or from outside the server
curl http://YOUR_SERVER_IP:8080/v1/status
```

Expected response:
```json
{"status": "ok", "version": "0.1.0"}
```

## Managing Services

### View Logs

```bash
# All services
docker compose logs -f

# Gateway only
docker compose logs -f gateway

# Light client only
docker compose logs -f light-client
```

### Stop Services

```bash
docker compose down
```

### Start Services

```bash
docker compose up -d
```

### Restart Services

```bash
docker compose restart
```

### Update to Latest Version

```bash
cd /opt/ckb-wallet-gateway
git pull origin main
cd deployment
docker compose build
docker compose up -d
```

## Configuring the Android App

Update your Android app to point to your server:

### For Debug Builds

Edit `android/app/build.gradle.kts`:

```kotlin
buildConfigField("String", "GATEWAY_URL", "\"http://YOUR_SERVER_IP:8080\"")
```

### For Release Builds

```kotlin
buildTypes {
    release {
        buildConfigField("String", "GATEWAY_URL", "\"https://api.yourdomain.com\"")
    }
}
```

## Setting Up SSL (HTTPS)

### Option 1: Using the Setup Script

```bash
sudo ./setup-vps.sh --domain api.yourdomain.com
```

### Option 2: Manual Nginx + Certbot

```bash
# Install Nginx and Certbot
sudo apt install nginx certbot python3-certbot-nginx -y

# Create Nginx config
sudo nano /etc/nginx/sites-available/ckb-gateway
```

Add this configuration:
```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable and get SSL:
```bash
sudo ln -s /etc/nginx/sites-available/ckb-gateway /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d api.yourdomain.com
```

## Firewall Configuration

The setup script configures UFW automatically. To configure manually:

```bash
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP
sudo ufw allow 443/tcp   # HTTPS
sudo ufw allow 8080/tcp  # Gateway API (optional, remove if using Nginx)
sudo ufw enable
```

## Monitoring

### Check Resource Usage

```bash
# Docker stats
docker stats

# System resources
htop
```

### Check Disk Usage

```bash
df -h
docker system df
```

### Clean Up Docker Resources

```bash
# Remove unused images
docker image prune -a

# Remove all unused resources
docker system prune -a
```

## Troubleshooting

### Light Client Not Syncing

```bash
# Check logs
docker compose logs light-client

# Restart light client
docker compose restart light-client
```

### Gateway Can't Connect to Light Client

```bash
# Check if light client is running
docker compose ps

# Check network connectivity
docker compose exec gateway curl http://light-client:9000/
```

### Port Already in Use

```bash
# Find what's using the port
sudo lsof -i :8080

# Kill the process or change the port in docker-compose.yml
```

### Out of Disk Space

```bash
# Check disk usage
df -h

# Clean Docker resources
docker system prune -a

# Remove old logs
docker compose logs --tail=0 -f
```

## Production Checklist

- [ ] Set up SSL/HTTPS with valid certificate
- [ ] Configure firewall (restrict port 8080 if using Nginx)
- [ ] Set up monitoring (e.g., Uptime Robot, Prometheus)
- [ ] Configure log rotation
- [ ] Set up automated backups of light client data
- [ ] Use a non-root user for running services
- [ ] Set up fail2ban for SSH protection
- [ ] Keep system and Docker updated

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                         VPS                              │
│  ┌─────────────────────────────────────────────────────┐│
│  │                    Docker                            ││
│  │  ┌─────────────────┐    ┌─────────────────────────┐ ││
│  │  │  Gateway Server │◄──►│   CKB Light Client      │ ││
│  │  │   (Port 8080)   │    │     (Port 9000)         │ ││
│  │  └────────┬────────┘    └───────────┬─────────────┘ ││
│  │           │                         │               ││
│  └───────────┼─────────────────────────┼───────────────┘│
│              │                         │                │
│  ┌───────────▼───────────┐             │                │
│  │   Nginx (Optional)    │             │                │
│  │   (Port 80/443)       │             │                │
│  └───────────┬───────────┘             │                │
└──────────────┼─────────────────────────┼────────────────┘
               │                         │
               ▼                         ▼
         Mobile App              CKB Testnet Network
```

## Support

For issues and questions:
- GitHub Issues: https://github.com/RaheemJnr/Light-Client-Gateway/issues
- CKB Documentation: https://docs.nervos.org/