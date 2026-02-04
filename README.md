# Chat App v1

A real-time multi-user chat application built with Spring Boot, featuring WebSocket messaging, Kafka event streaming, and Redis caching.

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 21 | Core language |
| Spring Boot | 4.0.1 | Application framework |
| Apache Kafka | 3.7 (KRaft) | Message broker |
| Redis | 7.0 | Cache & Pub/Sub |
| MySQL | 8.0 | Database |
| WebSocket | STOMP + SockJS | Real-time messaging |
| JWT | JJWT 0.11.5 | Authentication |
| Docker | Multi-stage | Containerization |
| Kubernetes | 1.24+ | Orchestration |

## Features

- Real-time messaging via WebSocket (STOMP protocol)
- Direct messages and group chats
- Message delivery status (SENT, DELIVERED, READ)
- Typing indicators and online presence
- JWT-based authentication
- Scalable architecture with Kafka and Redis

## Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose (for local development)
- MySQL 8.0
- Redis 7.0
- Apache Kafka 3.7+

## Getting Started

### Clone the repository

```bash
git clone https://github.com/gaurmukesh/chat-app-v1.git
cd chat-app-v1
```

### Build the application

```bash
./mvnw clean package
```

### Run locally

```bash
./mvnw spring-boot:run
```

### Run with Docker

```bash
docker build -t chat-app-v1 .
docker run -p 8080:8080 chat-app-v1
```

## Project Structure

```
src/main/java/com/mg/chat_app/
├── controller/          # REST & WebSocket controllers
├── service/             # Business logic
├── repository/          # Data access layer
├── entity/              # JPA entities
├── dto/                 # Data transfer objects
├── config/              # Configuration classes
└── security/            # Security configuration
```

## API Endpoints

### Authentication
- `POST /api/auth/register` - Register a new user
- `POST /api/auth/login` - Login and get JWT token
- `POST /api/auth/refresh` - Refresh JWT token

### Messages
- `POST /api/chat/send` - Send a direct message
- `GET /api/chat/history/{userId}` - Get chat history

### Groups
- `POST /api/groups` - Create a group
- `GET /api/groups/{groupId}` - Get group details
- `POST /api/groups/{groupId}/message` - Send group message

### WebSocket
- Connect: `/ws` (SockJS endpoint)
- Subscribe: `/user/queue/messages` - Receive direct messages
- Subscribe: `/topic/group/{groupId}` - Receive group messages
- Send: `/app/chat.send` - Send a message

## Documentation

- [Technical Overview](TECHNICAL_OVERVIEW.md) - Detailed architecture and implementation
- [AWS Deployment Guide](AWS_DEPLOYMENT_GUIDE.md) - Production deployment on AWS
- [Design Document](DESIGN_DOCUMENT.md) - System design decisions

## Kubernetes Deployment

Kubernetes manifests are available in the `k8s/` directory.

```bash
kubectl apply -f k8s/
```

## License

This project is licensed under the MIT License.
