# Chat Application — End-to-End Design Document

## Table of Contents

1. [System Overview](#1-system-overview)
2. [High-Level Design (HLD)](#2-high-level-design-hld)
3. [Low-Level Design (LLD)](#3-low-level-design-lld)
4. [Component Descriptions](#4-component-descriptions)
5. [Data Flow Diagrams](#5-data-flow-diagrams)
6. [Database Design](#6-database-design)
7. [API Specification](#7-api-specification)
8. [Security Architecture](#8-security-architecture)
9. [Deployment Architecture](#9-deployment-architecture)
10. [Scalability & Reliability](#10-scalability--reliability)

---

## 1. System Overview

### 1.1 Purpose

A production-ready, real-time chat application supporting:

- **Direct messaging** (1-to-1) with delivery and read receipts
- **Group messaging** with admin-managed membership
- **Real-time delivery** via WebSocket (STOMP protocol)
- **Multi-instance deployment** with cross-node message delivery
- **BCrypt authentication** with JWT access/refresh token strategy

### 1.2 Tech Stack

| Layer            | Technology                          |
|------------------|-------------------------------------|
| Language         | Java 21                             |
| Framework        | Spring Boot 4.0.1                   |
| REST API         | Spring Web MVC                      |
| Real-time        | Spring WebSocket + STOMP + SockJS   |
| Message Broker   | Apache Kafka                        |
| Pub/Sub Bridge   | Redis Pub/Sub                       |
| Cache/Sessions   | Redis                               |
| Database         | MySQL 8.0                           |
| ORM              | Spring Data JPA / Hibernate         |
| Authentication   | JWT (JJWT 0.11.5) + BCrypt         |
| Container        | Docker                              |
| Orchestration    | Kubernetes                          |

### 1.3 Key Design Decisions

| Decision                        | Rationale                                                        |
|---------------------------------|------------------------------------------------------------------|
| Kafka for async messaging       | Decouples message persistence from delivery; ordering guarantees |
| Redis Pub/Sub for WebSocket fan-out | Enables cross-node delivery in multi-instance deployments     |
| Redis for session/presence      | Shared state across app instances; fast TTL-based presence       |
| JWT with access/refresh tokens  | Stateless auth; short-lived access tokens limit exposure         |
| STOMP over SockJS               | Structured messaging protocol with fallback for older browsers   |
| MySQL with JPA                  | Relational integrity for users, messages, groups                 |

---

## 2. High-Level Design (HLD)

### 2.1 System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                         │
│  │ Browser  │  │ Browser  │  │ Browser  │  ...                     │
│  │ (User A) │  │ (User B) │  │ (User C) │                         │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                         │
│       │ HTTP/WS      │ HTTP/WS     │ HTTP/WS                       │
└───────┼──────────────┼─────────────┼───────────────────────────────┘
        │              │             │
        ▼              ▼             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     INGRESS / LOAD BALANCER                         │
│       (AWS ALB via ALB Ingress Controller on EKS)                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│  App Instance 1  │ │  App Instance 2  │ │  App Instance N  │
│  (Spring Boot)   │ │  (Spring Boot)   │ │  (Spring Boot)   │
│                  │ │                  │ │                  │
│ ┌──────────────┐ │ │ ┌──────────────┐ │ │ ┌──────────────┐ │
│ │ REST API     │ │ │ │ REST API     │ │ │ │ REST API     │ │
│ │ Controllers  │ │ │ │ Controllers  │ │ │ │ Controllers  │ │
│ ├──────────────┤ │ │ ├──────────────┤ │ │ ├──────────────┤ │
│ │ WebSocket    │ │ │ │ WebSocket    │ │ │ │ WebSocket    │ │
│ │ STOMP Broker │ │ │ │ STOMP Broker │ │ │ │ STOMP Broker │ │
│ ├──────────────┤ │ │ ├──────────────┤ │ │ ├──────────────┤ │
│ │ Kafka        │ │ │ │ Kafka        │ │ │ │ Kafka        │ │
│ │ Producer/    │ │ │ │ Producer/    │ │ │ │ Producer/    │ │
│ │ Consumer     │ │ │ │ Consumer     │ │ │ │ Consumer     │ │
│ ├──────────────┤ │ │ ├──────────────┤ │ │ ├──────────────┤ │
│ │ Redis Bridge │ │ │ │ Redis Bridge │ │ │ │ Redis Bridge │ │
│ └──────────────┘ │ │ └──────────────┘ │ │ └──────────────┘ │
└────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
         │                    │                     │
         ▼                    ▼                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      INFRASTRUCTURE LAYER                           │
│                                                                     │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────────┐          │
│  │  MySQL   │    │    Redis     │    │     Kafka        │          │
│  │          │    │              │    │                  │          │
│  │ - users  │    │ - Sessions   │    │ - chat-messages  │          │
│  │ - msgs   │    │   (Hash)     │    │ - chat-group-    │          │
│  │ - groups │    │ - Presence   │    │   messages       │          │
│  │ - members│    │   (KV+TTL)  │    │                  │          │
│  │ - presence│   │ - Pub/Sub    │    │ (Zookeeper)      │          │
│  └──────────┘    │   channels   │    └──────────────────┘          │
│                  └──────────────┘                                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Component Interaction Overview

```
Client ──HTTP POST──▶ AuthController ──▶ UserRepository + BCrypt ──▶ JwtService ──▶ TokenResponse
Client ──HTTP POST──▶ ChatRestController ──▶ ChatService ──▶ MySQL (save) ──▶ Kafka (publish)
Kafka ──consume──▶ ChatMessageConsumer ──▶ Redis Pub/Sub ──▶ RedisMessageBridge ──▶ WebSocket (deliver)
Client ──STOMP──▶ WebSocketAuthInterceptor ──▶ JWT validate ──▶ STOMP session established
Client ──STOMP /app/read──▶ ChatWebSocketController ──▶ MySQL (update) ──▶ WebSocket (receipt)
```

### 2.3 Multi-Instance Message Delivery Flow

```
┌───────────┐         ┌───────────┐         ┌───────────┐
│ Instance 1│         │   Kafka   │         │ Instance 2│
│           │         │           │         │           │
│ User A ●──┼──send──▶│ topic:    │◀──poll──┼──         │
│ connected │         │ chat-msgs │         │ User B ●  │
│           │         └─────┬─────┘         │ connected │
│           │               │               │           │
│           │         ┌─────▼─────┐         │           │
│           │         │Consumer   │         │           │
│           │         │(Instance 1│         │           │
│           │         │ or 2)     │         │           │
│           │         └─────┬─────┘         │           │
│           │               │               │           │
│           │         ┌─────▼─────┐         │           │
│           │         │   Redis   │         │           │
│           │         │  Pub/Sub  │         │           │
│           │         │ channel:  │         │           │
│           │         │chat:deliver│        │           │
│           │         │  :userB   │         │           │
│           │         └─────┬─────┘         │           │
│           │               │               │           │
│           │    subscribe  │  subscribe    │           │
│           │◀──────────────┼──────────────▶│           │
│           │               │               │           │
│           │               └──────────────▶│──WebSocket│
│           │                               │──▶User B │
└───────────┘                               └───────────┘
```

**Why this works:** Kafka consumer runs on one instance, but Redis Pub/Sub broadcasts to ALL instances. The instance where User B is connected receives the Redis message and pushes it via local WebSocket.

---

## 3. Low-Level Design (LLD)

### 3.1 Package Structure

```
com.mg.chat_app/
├── ChatAppApplication.java              # Spring Boot entry point
│
├── config/
│   ├── WebSocketConfig.java             # STOMP endpoint + broker config
│   ├── RedisConfig.java                 # RedisTemplate + Pub/Sub listener container
│   ├── RateLimitConfig.java             # Bucket4j + Lettuce ProxyManager + filter registration
│   └── WebSocketEventListener.java      # Connect/disconnect event handler
│
├── controller/
│   ├── AuthController.java              # Register, login, refresh endpoints
│   ├── ChatRestController.java          # Send message, offline, history endpoints
│   ├── ChatWebSocketController.java     # STOMP read receipts + typing indicators
│   ├── GroupController.java             # Group CRUD + group messaging endpoints
│   └── GlobalExceptionHandler.java      # Centralized error handling
│
├── dto/
│   ├── LoginRequest.java                # Username + password (validated)
│   ├── TokenResponse.java               # Access + refresh token pair
│   ├── RefreshTokenRequest.java         # Refresh token (validated, JSON body)
│   ├── SendMessageRequest.java          # Receiver ID + content (validated)
│   ├── ChatMessageDto.java              # Message transfer across Kafka/WebSocket
│   ├── ReadReceiptDto.java              # Read receipt notification
│   ├── CreateGroupRequest.java          # Group name + member IDs
│   ├── GroupMessageRequest.java         # Group ID + content
│   └── GroupDto.java                    # Group details with member list
│
├── entity/
│   ├── User.java                        # JPA entity: users table
│   ├── Message.java                     # JPA entity: messages table
│   ├── Presence.java                    # JPA entity: presence table
│   ├── ChatGroup.java                   # JPA entity: chat_groups table
│   └── GroupMember.java                 # JPA entity: group_members table
│
├── model/
│   ├── MessageStatus.java               # Enum: SENT, DELIVERED, READ
│   ├── MessageType.java                 # Enum: DIRECT, GROUP
│   └── GroupRole.java                   # Enum: ADMIN, MEMBER
│
├── repository/
│   ├── UserRepository.java              # findByUsername
│   ├── MessageRepository.java           # Paginated queries, conversation history
│   ├── PresenceRepository.java          # Presence CRUD
│   ├── ChatGroupRepository.java         # Group CRUD
│   └── GroupMemberRepository.java       # Membership queries
│
├── service/
│   ├── JwtService.java                  # Token generation, validation + Redis-backed rotation
│   ├── ChatService.java                 # Persist message + publish to Kafka
│   ├── InputSanitizer.java              # OWASP HTML sanitization for chat messages
│   ├── WebSocketSessionService.java     # Redis-backed session + presence tracking
│   ├── GroupService.java                # Group management business logic
│   └── RedisMessageBridge.java          # Redis Pub/Sub bridge for WebSocket delivery
│
├── kafka/
│   ├── ChatMessageProducer.java         # Publish to chat-messages topic
│   ├── ChatMessageConsumer.java         # Consume direct messages → Redis
│   └── GroupMessageConsumer.java        # Consume group messages → Redis fan-out
│
└── security/
    ├── SecurityConfig.java              # HTTP security + BCrypt + filter chain
    ├── JwtAuthenticationFilter.java     # HTTP request JWT validation
    ├── RateLimitFilter.java             # Per-IP rate limiting on auth endpoints
    └── WebSocketAuthInterceptor.java    # STOMP CONNECT JWT validation
```

### 3.2 Class Diagrams

#### 3.2.1 Entity Relationships

```
┌──────────────────┐       ┌───────────────────────────────┐
│      User        │       │          Message               │
├──────────────────┤       ├───────────────────────────────┤
│ userId (PK)      │       │ messageId (PK)                │
│ username (UNIQUE)│◀──┐   │ senderId (FK→User)            │
│ password (BCrypt)│   ├───│ receiverId (FK→User, nullable)│
│ firstName        │   │   │ groupId (FK→ChatGroup,nullable│
│ lastName         │   │   │ content (TEXT)                 │
│ status           │   │   │ status (ENUM: MessageStatus)  │
│ createdAt        │   │   │ messageType (ENUM: MessageType│
└──────────────────┘   │   │ createdAt                     │
                       │   └───────────────────────────────┘
┌──────────────────┐   │
│    Presence      │   │   ┌───────────────────────────────┐
├──────────────────┤   │   │        ChatGroup              │
│ userId (PK)      │───┘   ├───────────────────────────────┤
│ isOnline         │       │ groupId (PK)                  │
│ lastSeen         │       │ name                          │
└──────────────────┘       │ createdBy (FK→User)           │
                           │ createdAt                     │
                           └──────────────┬────────────────┘
                                          │
                           ┌──────────────▼────────────────┐
                           │       GroupMember              │
                           ├───────────────────────────────┤
                           │ id (PK)                       │
                           │ groupId (FK→ChatGroup)        │
                           │ userId (FK→User)              │
                           │ role (ENUM: ADMIN/MEMBER)     │
                           │ joinedAt                      │
                           └───────────────────────────────┘
```

#### 3.2.2 Service Layer Class Diagram

```
┌──────────────────────────────┐
│        ChatService           │
├──────────────────────────────┤
│ - messageRepository          │
│ - producer                   │
│ - inputSanitizer             │
├──────────────────────────────┤
│ + sendMessage(Message): Dto  │
│   1. Sanitize content (HTML) │
│   2. Set status = SENT       │
│   3. Save to DB              │
│   4. Build DTO               │
│   5. Publish to Kafka        │
│   6. Return DTO              │
└──────────────────────────────┘

┌──────────────────────────────┐
│        GroupService          │
├──────────────────────────────┤
│ - groupRepository            │
│ - memberRepository           │
│ - userRepository             │
├──────────────────────────────┤
│ + createGroup(...)           │
│ + addMember(...)             │
│ + removeMember(...)          │
│ + getGroupsForUser(userId)   │
│ + getGroupMemberIds(groupId) │
│ + isMember(groupId, userId)  │
│ - validateAdmin(...)         │
└──────────────────────────────┘

┌──────────────────────────────────┐
│     JwtService                   │
├──────────────────────────────────┤
│ - key (HMAC from secret)         │
│ - accessExpiryMs                 │
│ - refreshExpiryMs                │
│ - redisTemplate                  │
├──────────────────────────────────┤
│ + generateAccessToken(uid)       │
│ + generateRefreshToken(uid)      │
│ + storeRefreshToken(uid, token)  │
│   → SHA-256 hash → Redis w/ TTL │
│ + rotateRefreshToken(oldToken)   │
│   → validate → check hash →     │
│     delete old → issue new pair  │
│ + revokeRefreshToken(uid)        │
│   → delete from Redis            │
│ + extractUserId(token)           │
│ + validateAccessToken(token)     │
│ + validateRefreshToken(token)    │
│ - parseClaims(token)             │
│ - sha256(input)                  │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│   WebSocketSessionService        │
├──────────────────────────────────┤
│ - redisTemplate                  │
│ - presenceRepository             │
├──────────────────────────────────┤
│ + registerUser(userId, session)  │
│   → HSET ws:sessions uid sid     │
│   → SET presence:{uid} ONLINE    │
│   → Update Presence entity       │
│ + removeUser(userId)             │
│   → HDEL ws:sessions uid         │
│   → DEL presence:{uid}           │
│   → Update lastSeen in DB        │
│ + isUserOnline(userId)           │
│   → HEXISTS ws:sessions uid      │
│ + renewPresence(userId)          │
│   → EXPIRE presence:{uid} TTL    │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│     RedisMessageBridge           │
├──────────────────────────────────┤
│ - listenerContainer              │
│ - messagingTemplate              │
│ - redisTemplate                  │
├──────────────────────────────────┤
│ + publishToUser(userId, dto)     │
│   → PUBLISH chat:deliver:{uid}   │
│ + publishToGroup(groupId, dto)   │
│   → PUBLISH chat:group:{gid}     │
│ + subscribeUser(userId)          │
│   → Listen on chat:deliver:{uid} │
│   → On message → WebSocket push  │
│ + subscribeGroup(groupId)        │
│   → Listen on chat:group:{gid}   │
│   → On message → WebSocket push  │
└──────────────────────────────────┘
```

---

## 4. Component Descriptions

### 4.1 Authentication Layer

#### 4.1.1 AuthController

**Location:** `controller/AuthController.java`

**Purpose:** Handles user registration, login, and token refresh. This is the entry point for all authentication operations.

**Endpoints:**

| Method | Path               | Description                                      |
|--------|-------------------|--------------------------------------------------|
| POST   | /api/auth/register | Create new user with BCrypt-hashed password       |
| POST   | /api/auth/login    | Verify credentials, return access + refresh token |
| POST   | /api/auth/refresh  | Rotate refresh token, return new token pair       |

**Register Flow:**
1. Validate `LoginRequest` (username + password both `@NotBlank`)
2. Check if username already exists → 409 CONFLICT if taken
3. Hash password with BCrypt (`passwordEncoder.encode()`)
4. Save `User` entity to MySQL
5. Generate access token (15 min) and refresh token (7 days)
6. Store refresh token SHA-256 hash in Redis with 7-day TTL
7. Return `TokenResponse` with both tokens

**Login Flow:**
1. Validate `LoginRequest`
2. Look up user by username → 401 if not found
3. Verify password with BCrypt (`passwordEncoder.matches()`)
4. Generate tokens and store refresh token hash in Redis
5. Return `TokenResponse` with both tokens

**Refresh Flow (Token Rotation):**
1. Accept `RefreshTokenRequest` JSON body (`{"refreshToken": "..."}`)
2. Validate JWT signature and `tokenType: "refresh"` claim
3. Compute SHA-256 hash of the token
4. Check Redis key `refresh_token:{userId}` — hash must match stored value
5. If mismatch → token already rotated (possible theft) → revoke and return 401
6. Delete old hash from Redis
7. Generate new access + refresh token pair
8. Store new refresh token hash in Redis
9. Return `TokenResponse` with both new tokens

#### 4.1.2 JwtService

**Location:** `service/JwtService.java`

**Purpose:** Centralized JWT token generation and validation. All token operations flow through this service.

**Configuration (from application.yml):**
- `jwt.secret` — HMAC-SHA256 signing key (minimum 256 bits)
- `jwt.access-expiry-ms` — Access token lifetime (default: 900000 = 15 minutes)
- `jwt.refresh-expiry-ms` — Refresh token lifetime (default: 604800000 = 7 days)

**Token Structure:**
```json
{
  "sub": "1",                    // userId as string
  "tokenType": "access",        // "access" or "refresh"
  "iat": 1706745600,            // issued at (epoch seconds)
  "exp": 1706746500             // expiration (epoch seconds)
}
```

**Key Methods:**
- `generateAccessToken(userId)` — Creates short-lived token with `tokenType: "access"`
- `generateRefreshToken(userId)` — Creates long-lived token with `tokenType: "refresh"`
- `validateAccessToken(token)` — Parses token AND verifies `tokenType == "access"`
- `validateRefreshToken(token)` — Parses token AND verifies `tokenType == "refresh"`
- `extractUserId(token)` — Parses any valid token, returns subject (userId)

**Why token type matters:** Without the `tokenType` claim, a refresh token could be used as an access token to authenticate API requests. The type claim prevents this misuse.

#### 4.1.3 JwtAuthenticationFilter

**Location:** `security/JwtAuthenticationFilter.java`

**Purpose:** HTTP request filter that extracts and validates JWT from the `Authorization: Bearer <token>` header on every HTTP request.

**Flow:**
```
HTTP Request
    │
    ▼
Extract "Authorization" header
    │
    ├── Missing/No "Bearer " prefix → pass through (unauthenticated)
    │
    ▼
Extract token string
    │
    ▼
jwtService.validateAccessToken(token)
    │
    ├── JwtException → log WARN, pass through (unauthenticated)
    │
    ▼
Create UsernamePasswordAuthenticationToken
    │
    principal.getName() = userId (string)
    │
    ▼
Set in SecurityContextHolder
    │
    ▼
Continue filter chain (request is now authenticated)
```

**Key behavior:**
- Only validates **access tokens** (not refresh tokens)
- Catches `JwtException` specifically (not broad `Exception`)
- Logs failed validations at WARN level for monitoring
- Does NOT block requests — unauthenticated requests pass through to Spring Security's authorization layer

#### 4.1.4 WebSocketAuthInterceptor

**Location:** `security/WebSocketAuthInterceptor.java`

**Purpose:** Intercepts STOMP CONNECT frames to authenticate WebSocket connections. Unlike the HTTP filter, this **rejects** unauthenticated connections.

**Flow:**
```
STOMP CONNECT frame
    │
    ▼
Extract "Authorization" native header
    │
    ├── Missing → throw MessageDeliveryException (connection rejected)
    │
    ▼
jwtService.validateAccessToken(token)
    │
    ├── Exception → throw MessageDeliveryException (connection rejected)
    │
    ▼
Long.parseLong(userId)   // Verify numeric
    │
    ▼
Create UsernamePasswordAuthenticationToken
    │
    ▼
accessor.setUser(auth)   // Set Principal for entire WS session
    │
    ▼
All subsequent STOMP frames have access to Principal
```

**Difference from HTTP filter:** The HTTP filter allows unauthenticated requests through (Spring Security decides later). The WebSocket interceptor throws `MessageDeliveryException` which immediately rejects the STOMP CONNECT, preventing any WebSocket communication.

#### 4.1.5 SecurityConfig

**Location:** `security/SecurityConfig.java`

**Purpose:** Configures Spring Security filter chain, password encoder, and CORS.

**Beans provided:**
- `PasswordEncoder` — BCryptPasswordEncoder instance
- `SecurityFilterChain` — HTTP security rules
- `CorsConfigurationSource` — CORS policy

**Authorization rules:**

| Pattern             | Access     | Reason                                    |
|---------------------|-----------|-------------------------------------------|
| `/ws/**`            | Public    | WebSocket handshake (auth happens at STOMP level) |
| `/api/auth/**`      | Public    | Login, register, refresh don't need auth  |
| `/ws/info/**`       | Public    | SockJS info endpoint                      |
| `/actuator/**`      | Public    | Health checks for K8s probes              |
| `GET /`, `/chat.html`, `/static/**` | Public | Frontend static files       |
| Everything else     | Authenticated | All API endpoints require JWT          |

---

### 4.2 Messaging Layer

#### 4.2.1 ChatRestController

**Location:** `controller/ChatRestController.java`

**Purpose:** REST API for sending messages, fetching offline messages, and retrieving conversation history.

**Endpoints:**

| Method | Path              | Description                                    |
|--------|------------------|------------------------------------------------|
| POST   | /api/chat/send    | Send direct message (validated, authenticated) |
| GET    | /api/chat/offline | Fetch undelivered messages for current user    |
| GET    | /api/chat/history | Paginated conversation history between two users|

**Send Message Flow:**
1. Extract `senderId` from JWT `Principal`
2. Validate `SendMessageRequest` (`@NotNull receiverId`, `@NotBlank @Size(max=5000) content`)
3. Build `Message` entity
4. Delegate to `ChatService.sendMessage()`
5. Return `ChatMessageDto` with generated `messageId`

**Fetch Offline Flow (Transactional):**
1. Query messages where `receiverId = currentUser` AND `status = SENT`
2. Update all to `DELIVERED` status
3. Save all and return

**History Flow:**
1. Accept `otherUserId`, `page`, `size` parameters
2. Query using JPQL: messages where (sender=A, receiver=B) OR (sender=B, receiver=A)
3. Return paginated results ordered by `createdAt DESC`

#### 4.2.2 ChatService

**Location:** `service/ChatService.java`

**Purpose:** Core business logic for message persistence and Kafka publishing. Annotated with `@Transactional` to ensure atomicity.

**sendMessage() Flow:**
```
Message (from controller)
    │
    ▼
inputSanitizer.sanitize(content)   ← Strip HTML tags (XSS prevention)
    │
    ▼
Set status = SENT
    │
    ▼
messageRepository.save(message)    ← DB write (source of truth)
    │
    ▼
Build ChatMessageDto from saved entity
    │
    ▼
producer.publish(dto)              ← Kafka publish (async delivery)
    │
    ▼
Return dto to controller
```

**Why save before publish:** The database is the source of truth. If Kafka publish fails, the message still exists in DB with SENT status and can be retrieved via the offline endpoint. This prevents message loss.

#### 4.2.3 ChatMessageProducer

**Location:** `kafka/ChatMessageProducer.java`

**Purpose:** Publishes message events to Kafka topics.

**Topics:**

| Topic                  | Partition Key | Purpose                     |
|------------------------|---------------|-----------------------------|
| `chat-messages`        | receiverId    | Direct message delivery      |
| `chat-group-messages`  | groupId       | Group message delivery       |

**Why receiverId as partition key:** Messages for the same receiver always go to the same partition. This guarantees message ordering per receiver — User B always receives messages in the order they were sent.

**Why groupId as partition key:** Messages for the same group go to the same partition, ensuring group message ordering.

#### 4.2.4 ChatMessageConsumer

**Location:** `kafka/ChatMessageConsumer.java`

**Purpose:** Consumes from `chat-messages` topic, publishes to Redis for cross-node WebSocket delivery, and updates message status in DB.

**Consumer Group:** `chat-group`

**Flow:**
```
Kafka topic: chat-messages
    │
    ▼
consume(ChatMessageDto dto)
    │
    ▼
redisMessageBridge.publishToUser(receiverId, dto)
    │                                    │
    │     ┌──────────────────────────────┘
    │     ▼
    │  Redis PUBLISH chat:deliver:{receiverId}
    │     │
    │     ▼
    │  All app instances listening on that channel
    │  receive the message and push via local WebSocket
    │
    ▼
messageRepository.findById(messageId)
    │
    ▼
Set status = DELIVERED
    │
    ▼
messageRepository.save(msg)
```

**Why Redis instead of direct WebSocket:** In a multi-instance deployment, the Kafka consumer runs on ONE instance, but the target user might be connected to a DIFFERENT instance. Redis Pub/Sub broadcasts to all instances, and the one with the active WebSocket connection delivers the message.

#### 4.2.5 GroupMessageConsumer

**Location:** `kafka/GroupMessageConsumer.java`

**Purpose:** Consumes from `chat-group-messages` topic, fans out to all group members via Redis.

**Flow:**
```
Kafka topic: chat-group-messages
    │
    ▼
consume(ChatMessageDto dto)
    │
    ▼
groupService.getGroupMemberIds(groupId)
    │
    ▼
For each member (excluding sender):
    │
    ▼
redisMessageBridge.publishToUser(memberId, dto)
    │
    ▼
redisMessageBridge.publishToGroup(groupId, dto)
```

**Fan-out pattern:** One Kafka message becomes N Redis publishes (one per group member). This ensures each member receives the message regardless of which app instance they're connected to.

#### 4.2.6 RedisMessageBridge

**Location:** `service/RedisMessageBridge.java`

**Purpose:** Bridge between Kafka consumers and WebSocket delivery. Uses Redis Pub/Sub to enable cross-instance message delivery.

**Redis Channels:**

| Channel Pattern            | Purpose                                    |
|----------------------------|--------------------------------------------|
| `chat:deliver:{userId}`   | Direct messages to a specific user          |
| `chat:group:{groupId}`    | Group messages broadcast to group topic     |

**How it works:**

1. **Publishing:** When a Kafka consumer processes a message, it calls `publishToUser()` which does `PUBLISH chat:deliver:{userId} <serialized dto>`

2. **Subscribing:** When a user connects via WebSocket, the app instance subscribes to `chat:deliver:{userId}`. The `MessageListener` callback deserializes the message and calls `messagingTemplate.convertAndSend()` to push via the local STOMP broker.

3. **Cross-node delivery:** All app instances subscribe to channels for their locally connected users. When any instance publishes to a channel, the instance with the user's WebSocket connection receives and delivers it.

---

### 4.3 WebSocket Layer

#### 4.3.1 WebSocketConfig

**Location:** `config/WebSocketConfig.java`

**Purpose:** Configures STOMP messaging over WebSocket with SockJS fallback.

**Configuration:**

| Setting                      | Value        | Purpose                              |
|------------------------------|-------------|--------------------------------------|
| STOMP Endpoint               | `/ws`       | WebSocket handshake URL              |
| SockJS                       | enabled     | Fallback for browsers without WS     |
| Allowed Origins              | `*`         | Cross-origin WebSocket connections    |
| Application Prefix           | `/app`      | Client sends to `/app/*`             |
| Simple Broker                | `/topic`    | Server broadcasts on `/topic/*`      |
| Inbound Channel Interceptor  | AuthInterceptor | JWT validation on CONNECT        |

**STOMP Topics:**

| Topic Pattern                    | Direction      | Purpose                        |
|----------------------------------|---------------|--------------------------------|
| `/topic/messages/{userId}`       | Server→Client | Incoming direct messages        |
| `/topic/read/{userId}`           | Server→Client | Read receipt notifications      |
| `/topic/typing/{userId}`         | Server→Client | Typing indicators               |
| `/topic/groups/{groupId}`        | Server→Client | Group messages                  |
| `/app/read`                      | Client→Server | Mark message as read            |
| `/app/typing`                    | Client→Server | Send typing indicator           |

#### 4.3.2 ChatWebSocketController

**Location:** `controller/ChatWebSocketController.java`

**Purpose:** Handles STOMP messages for read receipts and typing indicators.

**markAsRead() Flow:**
```
Client sends to /app/read:
{ messageId: 1, senderId: 2, receiverId: 3 }
    │
    ▼
Extract userId from Principal (set during STOMP CONNECT)
    │
    ▼
Load message from DB
    │
    ▼
Verify msg.receiverId == userId  ← Authorization check!
    │
    ├── Not the receiver → log warning, return (do nothing)
    │
    ▼
Set status = READ, save to DB
    │
    ▼
Build ReadReceiptDto
    │
    ▼
messagingTemplate.convertAndSend("/topic/read/{senderId}", receipt)
    │
    ▼
Sender's browser receives receipt, shows ✓✓
```

**Authorization check:** Only the receiver of a message can mark it as READ. Without this check, any user could mark any message as read.

#### 4.3.3 WebSocketEventListener

**Location:** `config/WebSocketEventListener.java`

**Purpose:** Listens for WebSocket connect/disconnect events to manage session tracking and presence.

**On Connect (`SessionConnectEvent`):**
1. Extract `Principal` from STOMP accessor (set by `WebSocketAuthInterceptor`)
2. Extract `userId` and `sessionId`
3. Call `sessionService.registerUser(userId, sessionId)`
   - Stores in Redis Hash: `HSET ws:sessions {userId} {sessionId}`
   - Sets presence: `SET presence:{userId} ONLINE` with 5-minute TTL
   - Updates Presence entity in DB: `isOnline = true`

**On Disconnect (`SessionDisconnectEvent`):**
1. Extract `Principal` from STOMP accessor
2. Call `sessionService.removeUser(userId)`
   - Removes from Redis Hash: `HDEL ws:sessions {userId}`
   - Deletes presence key: `DEL presence:{userId}`
   - Updates Presence entity: `isOnline = false`, `lastSeen = now()`

**Why event listener instead of controller:** The previous `PresenceWebSocketController` required the client to explicitly send a `/app/connect` message. The event listener is automatic — it fires on the STOMP CONNECT/DISCONNECT lifecycle events without client cooperation.

---

### 4.4 Group Chat Layer

#### 4.4.1 GroupController

**Location:** `controller/GroupController.java`

**Purpose:** REST API for group management and group messaging.

**Endpoints:**

| Method | Path                              | Description                    |
|--------|----------------------------------|--------------------------------|
| POST   | /api/groups                       | Create group                   |
| GET    | /api/groups                       | List current user's groups     |
| POST   | /api/groups/{groupId}/members     | Add member (admin only)        |
| DELETE | /api/groups/{groupId}/members/{userId} | Remove member (admin only) |
| POST   | /api/groups/{groupId}/messages    | Send group message             |
| GET    | /api/groups/{groupId}/messages    | Get group message history      |

**Send Group Message Flow:**
1. Extract senderId from Principal
2. Verify sender is a member of the group
3. Build Message entity with `groupId` set and `messageType = GROUP`
4. Save to DB with status = SENT
5. Build ChatMessageDto (receiverId is null, groupId is set)
6. Publish to Kafka topic `chat-group-messages`
7. Return DTO

#### 4.4.2 GroupService

**Location:** `service/GroupService.java`

**Purpose:** Business logic for group lifecycle management.

**createGroup():**
1. Create `ChatGroup` entity with name and creatorId
2. Save to DB
3. Add creator as `GroupMember` with role `ADMIN`
4. Add each provided member with role `MEMBER`
5. Skip if memberId equals creatorId (avoid duplicate)

**addMember():**
1. Validate requester is ADMIN of the group
2. Check if user is already a member → error if yes
3. Add as MEMBER role

**removeMember():**
1. Validate requester is ADMIN
2. Delete the GroupMember record

**Admin validation:** Only users with `GroupRole.ADMIN` in the `group_members` table can add/remove members. The group creator is automatically assigned ADMIN role.

---

### 4.5 Session & Presence Layer

#### 4.5.1 WebSocketSessionService

**Location:** `service/WebSocketSessionService.java`

**Purpose:** Redis-backed session tracking and presence management. Replaces the previous in-memory `ConcurrentHashMap` implementation.

**Redis Data Structures:**

```
Redis Hash:  ws:sessions
┌──────────────┬───────────────────────────┐
│ Key (userId) │ Value (sessionId)         │
├──────────────┼───────────────────────────┤
│ "1"          │ "abc123-session-id"       │
│ "2"          │ "def456-session-id"       │
│ "5"          │ "ghi789-session-id"       │
└──────────────┴───────────────────────────┘

Redis Keys:  presence:{userId}
┌──────────────────┬─────────┬──────────┐
│ Key              │ Value   │ TTL      │
├──────────────────┼─────────┼──────────┤
│ presence:1       │ "ONLINE"│ 5 min    │
│ presence:2       │ "ONLINE"│ 5 min    │
└──────────────────┴─────────┴──────────┘
```

**Why Redis Hash for sessions:** All instances share the same Redis, so any instance can check if a user is online. With the old `ConcurrentHashMap`, each instance only knew about its own connections.

**Why TTL on presence:** If an app instance crashes without cleanly disconnecting, the presence key auto-expires after 5 minutes. The `renewPresence()` method is called on heartbeats to keep it alive.

---

### 4.6 Error Handling Layer

#### 4.6.1 GlobalExceptionHandler

**Location:** `controller/GlobalExceptionHandler.java`

**Purpose:** Centralized exception handling using `@RestControllerAdvice`. Converts exceptions to proper HTTP responses instead of raw 500 errors.

**Handled Exceptions:**

| Exception                          | HTTP Status | When                              |
|-----------------------------------|-------------|-----------------------------------|
| `MethodArgumentNotValidException` | 400         | `@Valid` validation failures       |
| `IllegalArgumentException`        | 400         | Bad business logic input           |
| `JwtException`                    | 401         | Invalid/expired/reused JWT tokens  |
| `SecurityException`               | 403         | Unauthorized group operations      |
| `RuntimeException`                | 500         | Unexpected errors (logged)         |

**Response Format:**
```json
{
  "error": "receiverId: must not be null; content: must not be blank",
  "timestamp": "2026-02-01T12:30:00.000"
}
```

---

## 5. Data Flow Diagrams

### 5.1 User Registration Flow

```
Client                    AuthController           UserRepo        JwtService        Redis
  │                            │                      │                │               │
  │ POST /api/auth/register    │                      │                │               │
  │ {username, password}       │                      │                │               │
  │───────────────────────────▶│                      │                │               │
  │                            │                      │                │               │
  │                            │ findByUsername()      │                │               │
  │                            │─────────────────────▶│                │               │
  │                            │     Optional.empty()  │                │               │
  │                            │◀─────────────────────│                │               │
  │                            │                      │                │               │
  │                            │ BCrypt.encode(pass)   │                │               │
  │                            │ save(User)            │                │               │
  │                            │─────────────────────▶│                │               │
  │                            │     User(id=1)       │                │               │
  │                            │◀─────────────────────│                │               │
  │                            │                      │                │               │
  │                            │ generateAccessToken("1")              │               │
  │                            │──────────────────────────────────────▶│               │
  │                            │                    accessToken        │               │
  │                            │◀──────────────────────────────────────│               │
  │                            │ generateRefreshToken("1")             │               │
  │                            │──────────────────────────────────────▶│               │
  │                            │                   refreshToken        │               │
  │                            │◀──────────────────────────────────────│               │
  │                            │                      │                │               │
  │                            │ storeRefreshToken("1", refreshToken)  │               │
  │                            │──────────────────────────────────────▶│               │
  │                            │                      │                │ SET           │
  │                            │                      │                │ refresh_token │
  │                            │                      │                │ :1 <sha256>   │
  │                            │                      │                │ EX 7days      │
  │                            │                      │                │──────────────▶│
  │                            │                      │                │               │
  │ 200 {accessToken,          │                      │                │               │
  │      refreshToken}         │                      │                │               │
  │◀───────────────────────────│                      │                │               │
```

### 5.2 Direct Message — End-to-End Flow

```
User A          REST API       ChatService     Kafka        Consumer      Redis        User B
(Browser)       Controller                     Broker                    Pub/Sub      (Browser)
  │                │               │              │             │           │             │
  │ POST /send     │               │              │             │           │             │
  │ {receiverId:2, │               │              │             │           │             │
  │  content:"Hi"} │               │              │             │           │             │
  │───────────────▶│               │              │             │           │             │
  │                │               │              │             │           │             │
  │                │ sendMessage() │              │             │           │             │
  │                │──────────────▶│              │             │           │             │
  │                │               │              │             │           │             │
  │                │               │ DB save      │             │           │             │
  │                │               │ status=SENT  │             │           │             │
  │                │               │──────┐       │             │           │             │
  │                │               │◀─────┘       │             │           │             │
  │                │               │              │             │           │             │
  │                │               │ Kafka publish│             │           │             │
  │                │               │ key=receiverId             │           │             │
  │                │               │─────────────▶│             │           │             │
  │                │               │              │             │           │             │
  │ 200 {messageId │               │              │             │           │             │
  │  senderId:1}   │               │              │             │           │             │
  │◀───────────────│               │              │             │           │             │
  │                                               │             │           │             │
  │                                               │ consume()   │           │             │
  │                                               │────────────▶│           │             │
  │                                               │             │           │             │
  │                                               │             │ PUBLISH   │             │
  │                                               │             │ chat:     │             │
  │                                               │             │ deliver:2 │             │
  │                                               │             │──────────▶│             │
  │                                               │             │           │             │
  │                                               │             │ DB update │             │
  │                                               │             │ DELIVERED │             │
  │                                               │             │──────┐    │             │
  │                                               │             │◀─────┘    │             │
  │                                               │             │           │             │
  │                                               │             │           │ WebSocket   │
  │                                               │             │           │ push to     │
  │                                               │             │           │ /topic/     │
  │                                               │             │           │ messages/2  │
  │                                               │             │           │────────────▶│
  │                                               │             │           │             │
  │                                               │             │           │  STOMP      │
  │                                               │             │           │  /app/read  │
  │                                               │             │           │◀────────────│
  │                                               │             │           │             │
  │ WebSocket: /topic/read/1                      │             │           │             │
  │ {messageId, status: READ}                     │             │           │             │
  │◀──────────────────────────────────────────────────────────────────────────────────────│
  │                                                                                       │
  │ Show ✓✓                                                                               │
```

### 5.3 Group Message Flow

```
User A         GroupController    MessageRepo    Kafka           GroupConsumer    Redis      User B, C
  │                 │                 │             │                 │             │          │
  │ POST /groups/   │                 │             │                 │             │          │
  │  1/messages     │                 │             │                 │             │          │
  │ {content:"Hi"}  │                 │             │                 │             │          │
  │────────────────▶│                 │             │                 │             │          │
  │                 │                 │             │                 │             │          │
  │                 │ isMember(1, A)? │             │                 │             │          │
  │                 │ → true          │             │                 │             │          │
  │                 │                 │             │                 │             │          │
  │                 │ save(msg,       │             │                 │             │          │
  │                 │  groupId=1,     │             │                 │             │          │
  │                 │  type=GROUP)    │             │                 │             │          │
  │                 │────────────────▶│             │                 │             │          │
  │                 │                 │             │                 │             │          │
  │                 │ publishGroupMsg │             │                 │             │          │
  │                 │ topic: chat-    │             │                 │             │          │
  │                 │ group-messages  │             │                 │             │          │
  │                 │ key: groupId    │             │                 │             │          │
  │                 │────────────────────────────▶  │                 │             │          │
  │                 │                 │             │                 │             │          │
  │ 200 {dto}       │                 │             │                 │             │          │
  │◀────────────────│                 │             │                 │             │          │
  │                                                │  consume()      │             │          │
  │                                                │────────────────▶│             │          │
  │                                                │                 │             │          │
  │                                                │                 │ get members │          │
  │                                                │                 │ [A, B, C]   │          │
  │                                                │                 │             │          │
  │                                                │                 │ PUBLISH     │          │
  │                                                │                 │ chat:deliver│          │
  │                                                │                 │ :B          │          │
  │                                                │                 │────────────▶│──WS────▶ │
  │                                                │                 │             │          │
  │                                                │                 │ PUBLISH     │          │
  │                                                │                 │ chat:deliver│          │
  │                                                │                 │ :C          │          │
  │                                                │                 │────────────▶│──WS────▶ │
  │                                                │                 │             │          │
  │                                                │                 │ PUBLISH     │          │
  │                                                │                 │ chat:group:1│          │
  │                                                │                 │────────────▶│          │
```

### 5.4 WebSocket Connection Lifecycle

```
Browser                SockJS/STOMP       Interceptor       EventListener      SessionService     Redis
  │                        │                  │                  │                   │              │
  │ new SockJS("/ws")      │                  │                  │                   │              │
  │───────────────────────▶│                  │                  │                   │              │
  │                        │                  │                  │                   │              │
  │ STOMP CONNECT          │                  │                  │                   │              │
  │ Authorization: Bearer  │                  │                  │                   │              │
  │ <token>                │                  │                  │                   │              │
  │───────────────────────▶│                  │                  │                   │              │
  │                        │                  │                  │                   │              │
  │                        │ preSend()        │                  │                   │              │
  │                        │─────────────────▶│                  │                   │              │
  │                        │                  │                  │                   │              │
  │                        │                  │ validateAccess   │                   │              │
  │                        │                  │ Token(token)     │                   │              │
  │                        │                  │ → userId = "1"   │                   │              │
  │                        │                  │                  │                   │              │
  │                        │                  │ setUser(auth)    │                   │              │
  │                        │◀─────────────────│                  │                   │              │
  │                        │                  │                  │                   │              │
  │ CONNECTED              │                  │                  │                   │              │
  │◀───────────────────────│                  │                  │                   │              │
  │                        │                  │                  │                   │              │
  │                        │        SessionConnectEvent          │                   │              │
  │                        │────────────────────────────────────▶│                   │              │
  │                        │                  │                  │                   │              │
  │                        │                  │                  │ registerUser(1,sid)│              │
  │                        │                  │                  │──────────────────▶ │              │
  │                        │                  │                  │                   │ HSET ws:     │
  │                        │                  │                  │                   │ sessions 1   │
  │                        │                  │                  │                   │─────────────▶│
  │                        │                  │                  │                   │ SET presence │
  │                        │                  │                  │                   │ :1 ONLINE    │
  │                        │                  │                  │                   │ EX 300       │
  │                        │                  │                  │                   │─────────────▶│
  │                        │                  │                  │                   │              │
  │  ... chat session ...  │                  │                  │                   │              │
  │                        │                  │                  │                   │              │
  │ DISCONNECT             │                  │                  │                   │              │
  │───────────────────────▶│                  │                  │                   │              │
  │                        │       SessionDisconnectEvent        │                   │              │
  │                        │────────────────────────────────────▶│                   │              │
  │                        │                  │                  │ removeUser(1)     │              │
  │                        │                  │                  │──────────────────▶ │              │
  │                        │                  │                  │                   │ HDEL ws:     │
  │                        │                  │                  │                   │ sessions 1   │
  │                        │                  │                  │                   │─────────────▶│
  │                        │                  │                  │                   │ DEL presence │
  │                        │                  │                  │                   │ :1           │
  │                        │                  │                  │                   │─────────────▶│
  │                        │                  │                  │                   │              │
  │                        │                  │                  │ DB: isOnline=false │              │
  │                        │                  │                  │     lastSeen=now() │              │
```

---

## 6. Database Design

### 6.1 Entity-Relationship Diagram

```
┌───────────┐       ┌──────────────┐       ┌──────────────┐
│   users   │       │   messages   │       │ chat_groups  │
├───────────┤       ├──────────────┤       ├──────────────┤
│*user_id PK│◀──┐   │*message_id PK│       │*group_id  PK │
│ username  │   ├──│ sender_id FK │   ┌──▶│ name         │
│ password  │   ├──│ receiver_id  │   │   │ created_by FK│
│ first_name│   │   │ group_id  FK─┼───┘   │ created_at   │
│ last_name │   │   │ content TEXT │       └──────┬───────┘
│ status    │   │   │ status ENUM │              │
│ created_at│   │   │ message_type │              │
└───────────┘   │   │ created_at   │       ┌──────▼───────┐
                │   └──────────────┘       │group_members │
┌───────────┐   │                          ├──────────────┤
│ presence  │   │                          │*id        PK │
├───────────┤   │                          │ group_id  FK │
│*user_id PK│───┘                          │ user_id   FK │
│ is_online │                              │ role ENUM    │
│ last_seen │                              │ joined_at    │
└───────────┘                              └──────────────┘
```

### 6.2 Table Definitions

#### users
```sql
CREATE TABLE users (
    user_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,          -- BCrypt hash ($2a$10$...)
    first_name VARCHAR(255),
    last_name  VARCHAR(255),
    status     VARCHAR(255),
    created_at DATETIME
);
```

#### messages
```sql
CREATE TABLE messages (
    message_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id    BIGINT NOT NULL,                -- FK → users.user_id
    receiver_id  BIGINT,                          -- NULL for group messages
    group_id     BIGINT,                          -- NULL for direct messages
    content      TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL,            -- SENT | DELIVERED | READ
    message_type VARCHAR(20) NOT NULL DEFAULT 'DIRECT', -- DIRECT | GROUP
    created_at   DATETIME
);
```

#### presence
```sql
CREATE TABLE presence (
    user_id   BIGINT PRIMARY KEY,              -- FK → users.user_id
    is_online BOOLEAN,
    last_seen DATETIME
);
```

#### chat_groups
```sql
CREATE TABLE chat_groups (
    group_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,                 -- FK → users.user_id
    created_at DATETIME
);
```

#### group_members
```sql
CREATE TABLE group_members (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id  BIGINT NOT NULL,                  -- FK → chat_groups.group_id
    user_id   BIGINT NOT NULL,                  -- FK → users.user_id
    role      VARCHAR(20) NOT NULL,             -- ADMIN | MEMBER
    joined_at DATETIME
);
```

### 6.3 Redis Data Structures

| Key/Pattern                | Type    | Value                 | TTL      | Purpose                              |
|---------------------------|---------|----------------------|----------|--------------------------------------|
| `ws:sessions`             | Hash    | `{userId: sessionId}` | None    | Track which users are connected      |
| `presence:{userId}`       | String  | `"ONLINE"`           | 5 min    | Real-time online status              |
| `refresh_token:{userId}`  | String  | SHA-256 hash         | 7 days   | Refresh token rotation (one-time use)|
| `rate_limit:login:{ip}`   | Binary  | Bucket4j bucket state | 15 min  | Login rate limit (5 req/min per IP)  |
| `rate_limit:register:{ip}`| Binary  | Bucket4j bucket state | 15 min  | Register rate limit (3 req/10min per IP) |

### 6.4 Kafka Topics

| Topic                  | Partitions | Key        | Value          | Consumer Group |
|------------------------|-----------|------------|----------------|----------------|
| `chat-messages`        | Auto      | receiverId | ChatMessageDto | chat-group     |
| `chat-group-messages`  | Auto      | groupId    | ChatMessageDto | chat-group     |

---

## 7. API Specification

### 7.1 Authentication APIs

#### POST /api/auth/register
**Request:**
```json
{
    "username": "alice",
    "password": "securePassword123"
}
```
**Response (200):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
**Errors:** 409 (username taken), 400 (validation)

#### POST /api/auth/login
**Request:**
```json
{
    "username": "alice",
    "password": "securePassword123"
}
```
**Response (200):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
**Errors:** 401 (invalid credentials)

#### POST /api/auth/refresh
**Request:**
```json
{
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
**Response (200):**
```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```
**Errors:** 401 (token already rotated/revoked, expired, or invalid)

**Note:** Each refresh token can only be used once. On success, a new token pair is issued. Reusing an already-rotated token revokes the session (theft detection).

### 7.2 Chat APIs (Require Authorization: Bearer \<token\>)

#### POST /api/chat/send
**Request:**
```json
{
    "receiverId": 2,
    "content": "Hello!"
}
```
**Response (200):**
```json
{
    "messageId": 1,
    "senderId": 1,
    "receiverId": 2,
    "content": "Hello!",
    "groupId": null
}
```

#### GET /api/chat/offline
**Response (200):**
```json
[
    {
        "messageId": 3,
        "senderId": 2,
        "receiverId": 1,
        "content": "Hey there",
        "status": "DELIVERED",
        "messageType": "DIRECT",
        "createdAt": "2026-02-01T12:00:00"
    }
]
```

#### GET /api/chat/history?otherUserId=2&page=0&size=50
**Response (200):** Spring Page object with `content` array of Message entities.

### 7.3 Group APIs (Require Authorization: Bearer \<token\>)

#### POST /api/groups
**Request:**
```json
{
    "name": "Project Team",
    "memberIds": [1, 2, 3]
}
```
**Response (200):**
```json
{
    "groupId": 1,
    "name": "Project Team",
    "createdBy": 1,
    "createdAt": "2026-02-01T12:00:00",
    "members": [
        {"userId": 1, "username": "alice", "role": "ADMIN"},
        {"userId": 2, "username": "bob", "role": "MEMBER"},
        {"userId": 3, "username": "charlie", "role": "MEMBER"}
    ]
}
```

#### GET /api/groups
**Response (200):** Array of GroupDto objects.

#### POST /api/groups/{groupId}/members?userId=4
**Response (200):** Empty (void)

#### DELETE /api/groups/{groupId}/members/{userId}
**Response (200):** Empty (void)

#### POST /api/groups/{groupId}/messages
**Request:**
```json
{
    "groupId": 1,
    "content": "Hello everyone!"
}
```
**Response (200):** ChatMessageDto

#### GET /api/groups/{groupId}/messages?page=0&size=50
**Response (200):** Spring Page of Message entities.

### 7.4 WebSocket STOMP Messages

| Direction | Destination                  | Payload                                              |
|-----------|------------------------------|------------------------------------------------------|
| Client→Server | `/app/read`             | `{messageId, senderId, receiverId}`                  |
| Client→Server | `/app/typing`           | `{senderId, receiverId}`                             |
| Server→Client | `/topic/messages/{uid}` | `{messageId, senderId, receiverId, content, groupId}` |
| Server→Client | `/topic/read/{uid}`     | `{messageId, senderId, receiverId, status}`           |
| Server→Client | `/topic/typing/{uid}`   | `senderId` (number)                                   |
| Server→Client | `/topic/groups/{gid}`   | `{messageId, senderId, content, groupId}`             |

### 7.5 Health Check

#### GET /actuator/health
**Response (200):**
```json
{
    "status": "UP",
    "components": {
        "db": {"status": "UP"},
        "redis": {"status": "UP"},
        "diskSpace": {"status": "UP"}
    }
}
```

---

## 8. Security Architecture

### 8.1 Authentication Flow (with Refresh Token Rotation)

```
                    ┌─────────────────────────────────────────────┐
                    │              TOKEN LIFECYCLE                  │
                    │                                               │
                    │  Register/Login                               │
                    │       │                                       │
                    │       ▼                                       │
                    │  ┌─────────────┐    ┌──────────────┐         │
                    │  │Access Token │    │Refresh Token │         │
                    │  │ (15 min)    │    │ (7 days)     │         │
                    │  │ type:access │    │ type:refresh │         │
                    │  └──────┬──────┘    └──────┬───────┘         │
                    │         │                   │                 │
                    │         │            SHA-256 hash stored      │
                    │         │            in Redis with TTL        │
                    │         ▼                   │                 │
                    │  Used for:                  │                 │
                    │  • REST API calls           │                 │
                    │  • WebSocket CONNECT         │                 │
                    │         │                   │                 │
                    │         ▼                   │                 │
                    │  Access token expires        │                 │
                    │         │                   │                 │
                    │         ▼                   ▼                 │
                    │  POST /api/auth/refresh ◀───┘                │
                    │  (sends JSON body)                            │
                    │         │                                     │
                    │         ▼                                     │
                    │  Validate hash matches Redis                  │
                    │         │                                     │
                    │    ┌────┴────┐                                │
                    │    │ Match?  │                                │
                    │    └────┬────┘                                │
                    │    Yes  │  No → 401 (revoke, possible theft)  │
                    │         ▼                                     │
                    │  Delete old hash from Redis                   │
                    │  Issue NEW access + refresh tokens            │
                    │  Store new refresh hash in Redis              │
                    │  (old refresh token is now invalid)           │
                    │                                               │
                    │  Refresh token expires → user must re-login   │
                    └─────────────────────────────────────────────┘
```

### 8.2 Security Layers

| Layer              | Component                    | Protection                          |
|-------------------|------------------------------|-------------------------------------|
| Transport         | HTTPS (via Ingress TLS)       | Encryption in transit               |
| Rate Limiting     | RateLimitFilter + Bucket4j    | 5 req/min login, 3 req/10min register per IP |
| Authentication    | JwtAuthenticationFilter       | JWT validation on HTTP requests     |
| Authentication    | WebSocketAuthInterceptor      | JWT validation on WS CONNECT        |
| Token Rotation    | JwtService + Redis            | One-time-use refresh tokens, theft detection |
| Authorization     | Spring Security filter chain  | Role-based endpoint access          |
| Authorization     | ChatWebSocketController       | Receiver-only read receipts         |
| Authorization     | GroupController/GroupService   | Admin-only member management        |
| Authorization     | GroupController                | Member-only group messaging         |
| Password          | BCryptPasswordEncoder         | Salted hash (cost factor 10)        |
| Input Sanitization| InputSanitizer (OWASP)        | Strip all HTML tags from messages   |
| Input validation  | Bean Validation annotations   | @NotBlank, @NotNull, @Size          |
| Token separation  | tokenType claim               | Prevent refresh as access token     |
| Secret management | application.yml / K8s Secret  | Externalized JWT secret             |

### 8.3 Password Storage

```
User enters: "myPassword123"
                │
                ▼
BCrypt.encode("myPassword123")
                │
                ▼
Stored in DB: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
                │
                │  Components:
                │  $2a$    → BCrypt version
                │  10$     → Cost factor (2^10 = 1024 rounds)
                │  N9qo... → 22-char salt + 31-char hash
```

On login: `BCrypt.matches("myPassword123", storedHash)` → true/false

---

## 9. Deployment Architecture

### 9.1 AWS EKS Production Architecture

The application is deployed on **Amazon EKS** with all stateful services offloaded to AWS managed services, eliminating the need for in-cluster StatefulSets.

### 9.1.1 Cluster Layout

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              AWS (us-east-1)                                  │
│                                                                               │
│  ┌────────────────────────────────────────────────────────────────────────┐   │
│  │                EKS Cluster: chat-app-v1-cluster                        │   │
│  │                     Namespace: chat-app                                 │   │
│  │                                                                         │   │
│  │  ┌───────────────────────────────────────────────────────────────┐     │   │
│  │  │                Ingress (AWS ALB)                                │     │   │
│  │  │   ingressClassName: alb, scheme: internet-facing               │     │   │
│  │  │   k8s-chatapp-chatappi-*.us-east-1.elb.amazonaws.com          │     │   │
│  │  └────────────────────────────┬──────────────────────────────────┘     │   │
│  │                                │                                        │   │
│  │  ┌────────────────────────────▼──────────────────────────────────┐     │   │
│  │  │              App Service (ClusterIP:8080)                       │     │   │
│  │  └────────────────────────────┬──────────────────────────────────┘     │   │
│  │                                │                                        │   │
│  │  ┌────────────────────────────▼──────────────────────────────────┐     │   │
│  │  │              App Deployment (ECR image, imagePullPolicy:Always) │     │   │
│  │  │                                                                 │     │   │
│  │  │  ┌──────────┐  ┌──────────┐       ┌──────────┐                │     │   │
│  │  │  │  Pod 1   │  │  Pod 2   │  ...  │  Pod N   │                │     │   │
│  │  │  │ chat-app │  │ chat-app │       │ chat-app │                │     │   │
│  │  │  └──────────┘  └──────────┘       └──────────┘                │     │   │
│  │  │                                                                 │     │   │
│  │  │  HPA: 2-10 replicas, CPU target: 70%                           │     │   │
│  │  └─────────────────────────────────────────────────────────────────┘     │   │
│  │                                                                         │   │
│  │  ┌────────────────┐  ┌────────────────┐                                │   │
│  │  │   ConfigMap    │  │    Secret      │                                │   │
│  │  │ - RDS endpoint │  │ - DB_USERNAME  │                                │   │
│  │  │ - MSK brokers  │  │ - DB_PASSWORD  │                                │   │
│  │  │ - ElastiCache  │  │ - JWT_SECRET   │                                │   │
│  │  │ - Kafka SSL    │  │               │                                │   │
│  │  └────────────────┘  └────────────────┘                                │   │
│  └────────────────────────────────────────────────────────────────────────┘   │
│                           │          │          │                              │
│                    ┌──────┘          │          └──────┐                       │
│                    ▼                 ▼                  ▼                       │
│  ┌─────────────────────┐ ┌──────────────────┐ ┌───────────────────┐          │
│  │  Amazon RDS         │ │ Amazon           │ │ Amazon MSK        │          │
│  │  (MySQL 8.0)        │ │ ElastiCache      │ │ (Kafka 3.5.1)     │          │
│  │  db.t3.small        │ │ (Redis 7.1)      │ │ kafka.t3.small    │          │
│  │                     │ │ cache.t3.micro   │ │ 2 brokers         │          │
│  │  chat-app-mysql.    │ │                  │ │                   │          │
│  │  cod8o8ck2y1e.      │ │ chat-app-redis.  │ │ b-1.chatappkafka  │          │
│  │  us-east-1.rds.     │ │ ybwh7f.0001.     │ │ .xo3j3c.c22.     │          │
│  │  amazonaws.com      │ │ use1.cache.      │ │ kafka.us-east-1.  │          │
│  │  :3306              │ │ amazonaws.com    │ │ amazonaws.com     │          │
│  │                     │ │ :6379            │ │ :9094 (TLS)       │          │
│  └─────────────────────┘ └──────────────────┘ └───────────────────┘          │
│                                                                               │
│  ┌────────────────────────────────────────────────────────────────────────┐   │
│  │  ECR: 620179522575.dkr.ecr.us-east-1.amazonaws.com/chat-app-v1        │   │
│  └────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 EKS Cluster Details

| Component               | Value                                                  |
|-------------------------|--------------------------------------------------------|
| Cluster name            | `chat-app-v1-cluster`                                  |
| Region                  | `us-east-1`                                            |
| Kubernetes version      | 1.29                                                   |
| Node count              | 2 (`t3.medium`)                                        |
| VPC                     | `vpc-070572af6742779ef`                                |
| Cluster security group  | `sg-0da5432b3b651a245`                                 |
| Managed services SG     | `sg-029f1707a28f0edc9`                                 |
| ECR repository          | `620179522575.dkr.ecr.us-east-1.amazonaws.com/chat-app-v1` |
| ALB endpoint            | `k8s-chatapp-chatappi-*.us-east-1.elb.amazonaws.com`  |

### 9.3 AWS Managed Services

| Service         | AWS Product       | Endpoint                                                             | Port | Protocol |
|-----------------|-------------------|----------------------------------------------------------------------|------|----------|
| MySQL Database  | Amazon RDS        | `chat-app-mysql.cod8o8ck2y1e.us-east-1.rds.amazonaws.com`           | 3306 | TCP      |
| Redis Cache     | Amazon ElastiCache| `chat-app-redis.ybwh7f.0001.use1.cache.amazonaws.com`               | 6379 | TCP      |
| Kafka Broker 1  | Amazon MSK        | `b-1.chatappkafka.xo3j3c.c22.kafka.us-east-1.amazonaws.com`        | 9094 | TLS/SSL  |
| Kafka Broker 2  | Amazon MSK        | `b-2.chatappkafka.xo3j3c.c22.kafka.us-east-1.amazonaws.com`        | 9094 | TLS/SSL  |

**Security:** EKS pods connect to managed services via security group rules. The managed services SG (`sg-029f1707a28f0edc9`) allows inbound traffic from the EKS cluster SG (`sg-0da5432b3b651a245`) on ports 3306, 6379, and 9094.

### 9.4 Docker Image

```dockerfile
# Stage 1: Build (maven:3.9-eclipse-temurin-21)
COPY pom.xml → mvn dependency:go-offline
COPY src → mvn clean package -DskipTests

# Stage 2: Runtime (eclipse-temurin:21-jre-alpine)
COPY app.jar → java -jar app.jar
```

**Image size:** ~165MB (Alpine JRE base)
**Platform:** `linux/amd64` (EKS node architecture)

### 9.5 Kubernetes Manifests

| File                      | Kind                    | Purpose                                           |
|---------------------------|-------------------------|---------------------------------------------------|
| `namespace.yaml`          | Namespace               | Isolate chat-app resources                        |
| `configmap.yaml`          | ConfigMap               | AWS service endpoints, Kafka SSL, Spring profile  |
| `secret.yaml`             | Secret                  | DB credentials, JWT secret                        |
| `app-deployment.yaml`     | Deployment (2 replicas) | Spring Boot app pods (ECR image, `Always` pull)   |
| `app-service.yaml`        | Service (ClusterIP)     | Internal load balancing for app                   |
| `app-hpa.yaml`            | HPA                     | Auto-scale 2-10 pods at 70% CPU                  |
| `ingress.yaml`            | Ingress                 | AWS ALB with health check annotations             |

**Note:** Local StatefulSet/Service manifests for MySQL, Redis, and Kafka were removed — these services are provided by AWS RDS, ElastiCache, and MSK respectively.

### 9.6 AWS Load Balancer Controller

The AWS Load Balancer Controller provisions an internet-facing ALB from the Ingress resource.

**Installation:**
- IAM OIDC provider associated with EKS cluster
- IAM service account `aws-load-balancer-controller` in `kube-system`
- IAM role `AmazonEKSLoadBalancerControllerRole` with `AWSLoadBalancerControllerIAMPolicy`
- Installed via Helm chart `eks/aws-load-balancer-controller`

**ALB Configuration (via Ingress annotations):**

| Annotation                                          | Value               | Purpose                          |
|-----------------------------------------------------|---------------------|----------------------------------|
| `alb.ingress.kubernetes.io/scheme`                 | `internet-facing`   | Public ALB                       |
| `alb.ingress.kubernetes.io/target-type`            | `ip`                | Route directly to pod IPs        |
| `alb.ingress.kubernetes.io/healthcheck-path`       | `/actuator/health`  | ALB health check endpoint        |
| `alb.ingress.kubernetes.io/listen-ports`           | `[{"HTTP": 80}]`   | HTTP listener on port 80         |

### 9.7 Health Probes

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 30      # Wait for Spring Boot to start
  periodSeconds: 10             # Check every 10 seconds

livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60      # Give more time before first liveness check
  periodSeconds: 15             # Check every 15 seconds
```

**Readiness:** Pod receives traffic only when healthy.
**Liveness:** Pod is restarted if unhealthy.

### 9.8 CI/CD Pipeline (GitHub Actions)

The application uses a GitHub Actions workflow that automatically builds, pushes, and deploys on every push to `main`.

**File:** `.github/workflows/deploy.yml`

**Authentication:** GitHub OIDC → AWS IAM role (`github-actions-chat-app`) — no long-lived credentials stored in GitHub.

**Pipeline Flow:**

```
Push to main
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  GitHub Actions: build-and-deploy                    │
│                                                       │
│  1. Checkout code                                     │
│  2. Configure AWS credentials (OIDC)                  │
│  3. Login to Amazon ECR                               │
│  4. Build Docker image                                │
│     docker build --build-arg APP_VERSION=$SHA ...     │
│  5. Tag with commit SHA + latest                      │
│  6. Push both tags to ECR                             │
│  7. Install kubectl                                   │
│  8. Update kubeconfig for EKS                         │
│  9. kubectl set image deployment/chat-app             │
│     → chat-app=ECR_REPO:$SHA                         │
│ 10. kubectl rollout status (wait up to 5 min)         │
└─────────────────────────────────────────────────────┘
```

**Key Design Decisions:**

| Decision | Rationale |
|----------|-----------|
| OIDC auth | No long-lived AWS credentials; GitHub mints short-lived tokens |
| Image tagged with SHA | Ensures traceability; triggers rolling update (`:latest` alone wouldn't) |
| `kubectl set image` | Updates deployment without modifying K8s manifests in repo |
| `kubectl rollout status` | Fails pipeline if health checks don't pass within 5 minutes |
| `APP_VERSION` build-arg | Injects commit SHA into container; exposed via `/api/version` endpoint |

**IAM Requirements:**

| Permission | Resource | Purpose |
|------------|----------|---------|
| `ecr:GetAuthorizationToken` | `*` | ECR login |
| `ecr:PutImage`, `ecr:*LayerUpload`, etc. | `arn:aws:ecr:...:repository/chat-app-v1` | Push images |
| `eks:DescribeCluster` | `arn:aws:eks:...:cluster/chat-app-v1-cluster` | Get kubeconfig |

**EKS Access:** The IAM role is mapped to `system:masters` in the `aws-auth` ConfigMap, granting kubectl access to the cluster.

### 9.9 Version Endpoint

| Method | Path | Auth | Response |
|--------|------|------|----------|
| GET | `/api/version` | Public | `{"version": "<commit-sha>"}` |

The commit SHA is injected at Docker build time via `APP_VERSION` build-arg → environment variable → Spring property (`app.version`). This allows verifying which exact commit is running in production.

---

## 10. Scalability & Reliability

### 10.1 Horizontal Scaling Strategy

| Component      | AWS Service       | Scaling                           | How                                                    |
|----------------|-------------------|-----------------------------------|---------------------------------------------------------|
| App instances  | EKS Pods          | Horizontal (2-10 pods)            | HPA based on CPU; Redis ensures shared state            |
| Kafka          | Amazon MSK        | Broker count + partitions         | Add brokers/partitions via AWS console or CLI            |
| MySQL          | Amazon RDS        | Vertical / Read replicas          | Scale instance class; add read replicas if needed       |
| Redis          | Amazon ElastiCache| Vertical / Cluster mode           | Scale node type; enable cluster mode for HA             |

### 10.2 Why Multi-Instance Works

| Challenge                  | Solution                                          |
|----------------------------|---------------------------------------------------|
| WebSocket sessions are local | Redis Hash tracks which user is on which instance |
| Kafka consumer is on one instance | Redis Pub/Sub broadcasts to all instances    |
| Session state is in-memory   | Moved to Redis (shared across instances)         |
| Presence tracking            | Redis keys with TTL (auto-expire on crash)       |

### 10.3 Failure Scenarios

| Scenario                    | Behavior                                               |
|-----------------------------|--------------------------------------------------------|
| App instance crashes         | K8s restarts pod; Redis presence TTL expires in 5 min |
| Message send + Kafka down    | Message saved in DB (SENT); retried via offline fetch |
| Redis down                   | WebSocket delivery fails; messages safe in DB          |
| MySQL down                   | All operations fail; app reports unhealthy via actuator |
| Client disconnects           | Auto-reconnect with token refresh in frontend          |

### 10.4 Message Delivery Guarantees

| Stage              | Guarantee                                                   |
|--------------------|-------------------------------------------------------------|
| REST → DB          | Synchronous save, transactional. Message is persisted.      |
| DB → Kafka         | At-least-once (Kafka producer default acks)                 |
| Kafka → Consumer   | At-least-once (consumer group offset tracking)              |
| Consumer → Redis   | Best-effort (if Redis down, message still in DB as SENT)    |
| Redis → WebSocket  | Best-effort (if user offline, fetched via /offline endpoint)|
| Overall            | At-least-once delivery. No message loss due to DB as source of truth. |

### 10.5 Message Status State Machine

```
    ┌──────┐     ┌───────────┐     ┌──────┐
    │ SENT │────▶│ DELIVERED │────▶│ READ │
    └──────┘     └───────────┘     └──────┘
       │               │               │
       │               │               │
  Message saved    Kafka consumer    Receiver sends
  to database      processed and    /app/read via
                   pushed via       WebSocket
                   WebSocket
```
