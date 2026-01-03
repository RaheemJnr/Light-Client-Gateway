#!/bin/bash

#===============================================================================
# CKB Wallet Gateway - VPS Setup Script
#
# This script sets up a fresh VPS (Ubuntu 22.04/Debian 12) with:
# - Docker & Docker Compose
# - CKB Light Client
# - Gateway Server
# - Nginx reverse proxy with SSL (optional)
# - Firewall configuration
#
# Usage:
#   chmod +x setup-vps.sh
#   sudo ./setup-vps.sh
#
# Requirements:
#   - Ubuntu 22.04 LTS or Debian 12
#   - Root/sudo access
#   - Minimum 2GB RAM, 40GB disk
#===============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
INSTALL_DIR="/opt/ckb-wallet-gateway"
REPO_URL="https://github.com/RaheemJnr/Light-Client-Gateway.git"
DOMAIN=""  # Set this if you have a domain for SSL

#===============================================================================
# Helper Functions
#===============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

check_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        if [[ "$ID" != "ubuntu" && "$ID" != "debian" ]]; then
            log_error "This script supports Ubuntu and Debian only"
            exit 1
        fi
    else
        log_error "Cannot detect OS"
        exit 1
    fi
    log_info "Detected OS: $PRETTY_NAME"
}

#===============================================================================
# Installation Functions
#===============================================================================

update_system() {
    log_info "Updating system packages..."
    apt-get update -y
    apt-get upgrade -y
    log_success "System updated"
}

install_dependencies() {
    log_info "Installing dependencies..."
    apt-get install -y \
        curl \
        wget \
        git \
        ufw \
        htop \
        nano \
        ca-certificates \
        gnupg \
        lsb-release
    log_success "Dependencies installed"
}

install_docker() {
    if command -v docker &> /dev/null; then
        log_info "Docker already installed: $(docker --version)"
        return
    fi

    log_info "Installing Docker..."

    # Add Docker's official GPG key
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/$(. /etc/os-release && echo "$ID")/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg

    # Set up the repository
    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/$(. /etc/os-release && echo "$ID") \
        $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
        tee /etc/apt/sources.list.d/docker.list > /dev/null

    # Install Docker
    apt-get update -y
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    # Start and enable Docker
    systemctl start docker
    systemctl enable docker

    log_success "Docker installed: $(docker --version)"
}

setup_firewall() {
    log_info "Configuring firewall..."

    # Reset UFW to defaults
    ufw --force reset

    # Default policies
    ufw default deny incoming
    ufw default allow outgoing

    # Allow SSH
    ufw allow 22/tcp comment 'SSH'

    # Allow HTTP and HTTPS
    ufw allow 80/tcp comment 'HTTP'
    ufw allow 443/tcp comment 'HTTPS'

    # Allow Gateway API (you may want to restrict this in production)
    ufw allow 8080/tcp comment 'Gateway API'

    # Enable firewall
    ufw --force enable

    log_success "Firewall configured"
    ufw status verbose
}

clone_repository() {
    log_info "Setting up application directory..."

    if [[ -d "$INSTALL_DIR" ]]; then
        log_warn "Directory $INSTALL_DIR already exists. Pulling latest changes..."
        cd "$INSTALL_DIR"
        git pull origin main
    else
        log_info "Cloning repository..."
        git clone "$REPO_URL" "$INSTALL_DIR"
    fi

    cd "$INSTALL_DIR"
    log_success "Repository ready at $INSTALL_DIR"
}

create_env_file() {
    log_info "Creating environment file..."

    cat > "$INSTALL_DIR/deployment/.env" << EOF
# CKB Wallet Gateway Configuration
# Generated on $(date)

# Gateway Server
RUST_LOG=info
SERVER_PORT=8080
LIGHT_CLIENT_URL=http://light-client:9000

# Network (testnet or mainnet)
CKB_NETWORK=testnet
EOF

    log_success "Environment file created at $INSTALL_DIR/deployment/.env"
}

build_and_start() {
    log_info "Building and starting services..."

    cd "$INSTALL_DIR/deployment"

    # Build images
    docker compose build

    # Start services
    docker compose up -d

    log_success "Services started"
}

create_systemd_service() {
    log_info "Creating systemd service for auto-start..."

    cat > /etc/systemd/system/ckb-gateway.service << EOF
[Unit]
Description=CKB Wallet Gateway
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$INSTALL_DIR/deployment
ExecStart=/usr/bin/docker compose up -d
ExecStop=/usr/bin/docker compose down
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable ckb-gateway.service

    log_success "Systemd service created and enabled"
}

install_nginx() {
    if [[ -z "$DOMAIN" ]]; then
        log_warn "No domain configured. Skipping Nginx setup."
        log_info "To add SSL later, set DOMAIN variable and run this script again."
        return
    fi

    log_info "Installing Nginx..."
    apt-get install -y nginx certbot python3-certbot-nginx

    # Create Nginx config
    cat > /etc/nginx/sites-available/ckb-gateway << EOF
server {
    listen 80;
    server_name $DOMAIN;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_cache_bypass \$http_upgrade;
    }
}
EOF

    # Enable site
    ln -sf /etc/nginx/sites-available/ckb-gateway /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default

    # Test and reload
    nginx -t
    systemctl reload nginx

    # Get SSL certificate
    log_info "Obtaining SSL certificate..."
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --email admin@"$DOMAIN"

    log_success "Nginx configured with SSL"
}

show_status() {
    echo ""
    echo "==========================================="
    echo -e "${GREEN}CKB Wallet Gateway Setup Complete!${NC}"
    echo "==========================================="
    echo ""
    echo "Services Status:"
    docker compose -f "$INSTALL_DIR/deployment/docker-compose.yml" ps
    echo ""
    echo "Useful Commands:"
    echo "  - View logs:        docker compose -f $INSTALL_DIR/deployment/docker-compose.yml logs -f"
    echo "  - Stop services:    docker compose -f $INSTALL_DIR/deployment/docker-compose.yml down"
    echo "  - Start services:   docker compose -f $INSTALL_DIR/deployment/docker-compose.yml up -d"
    echo "  - Restart services: docker compose -f $INSTALL_DIR/deployment/docker-compose.yml restart"
    echo ""
    echo "API Endpoints:"
    echo "  - Health Check:     http://$(curl -s ifconfig.me):8080/v1/status"
    echo "  - Gateway API:      http://$(curl -s ifconfig.me):8080/v1/"
    echo ""
    if [[ -n "$DOMAIN" ]]; then
        echo "  - HTTPS:            https://$DOMAIN/v1/"
    fi
    echo ""
    echo -e "${YELLOW}Note: Light client will take some time to sync with the network.${NC}"
    echo "Monitor progress with: docker compose -f $INSTALL_DIR/deployment/docker-compose.yml logs -f light-client"
    echo ""
}

#===============================================================================
# Main Script
#===============================================================================

main() {
    echo ""
    echo "==========================================="
    echo "  CKB Wallet Gateway - VPS Setup Script"
    echo "==========================================="
    echo ""

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --domain)
                DOMAIN="$2"
                shift 2
                ;;
            --help)
                echo "Usage: sudo ./setup-vps.sh [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --domain DOMAIN   Set domain for SSL certificate"
                echo "  --help            Show this help message"
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done

    check_root
    check_os

    update_system
    install_dependencies
    install_docker
    setup_firewall
    clone_repository
    create_env_file
    build_and_start
    create_systemd_service
    install_nginx

    show_status
}

# Run main function
main "$@"
