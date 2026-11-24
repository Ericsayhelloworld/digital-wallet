# Digital Wallet Platform

Spring Boot microservices with a web frontend: register, open a wallet, add money from a bank,
send money to other users, withdraw to the bank, and get a notification for every movement.

## Start

    cp .env.example .env
    docker compose up --build

Open http://localhost:8080 (the port comes from WEB_PORT in .env).

Start again from an empty database:

    docker compose down -v
    docker compose up --build

## Services

| Service              | Port (inside the network) | Responsibility                                   |
|----------------------|---------------------------|--------------------------------------------------|
| web                  | 8080 (published)          | Static frontend and edge proxy (nginx)           |
| auth-service         | 8081                      | Registration, login, RS256 JWT, JWKS             |
| wallet-service       | 8082                      | Wallets, ledger, transfers, deposits, withdrawals, idempotency, outbox, reconciliation |
| notification-service | 8083                      | Consumes wallet.events, notifications per user   |
| bank-simulator       | 8084                      | Fake external bank with signed webhooks          |

API documentation: http://localhost:8080/docs/auth/swagger-ui.html, /docs/wallet/..., /docs/notifications/..., /docs/bank/...
