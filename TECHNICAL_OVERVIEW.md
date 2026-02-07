# Chat App v1 — Comprehensive Technical Overview

## Table of Contents

1. [Technology Stack](#1-technology-stack)
2. [Project Structure](#2-project-structure)
3. [Entity/Model Structure & Relationships](#3-entitymodel-structure--relationships)
4. [Application Flow](#4-application-flow)
5. [Security Setup](#5-security-setup)
6. [WebSocket Setup & Real-Time Messaging](#6-websocket-setup--real-time-messaging)
7. [Controllers, Services & Repositories](#7-controllers-services--repositories)
8. [Configuration](#8-configuration)
9. [Message Delivery Guarantees](#9-message-delivery-guarantees)
10. [Kubernetes Deployment Architecture](#10-kubernetes-deployment-architecture)
11. [Key Architectural Decisions](#11-key-architectural-decisions)
12. [CI/CD Pipeline](#12-cicd-pipeline)
13. [Security Considerations for Production](#13-security-considerations-for-production)

---

## 1. Technology Stack

| Layer | Technology | Version | Purpose |
|-------|-----------|---------|---------|
| Language | Java | 21 | Core application language |
| Framework | Spring Boot | 4.0.1 | Application framework |
| Build Tool | Maven | 3.9 | Dependency management and build |
| Database | MySQL | 8.0 | Persistent data storage |
| ORM | Spring Data JPA | Hibernate | Database abstraction |
| Message Broker | Apache Kafka | 3.7 (KRaft) | Async message processing |
| Cache/Pub-Sub | Redis | 7.0 Alpine | Session state & message bridging |
| Real-time | Spring WebSocket | STOMP + SockJS | WebSocket messaging protocol |
| Authentication | JWT | JJWT 0.11.5 | Token-based auth |
| Password Encoding | BCrypt | Built-in | Password hashing |
| Container | Docker | Multi-stage | Application containerization |
| Orchestration | Kubernetes | 1.24+ | Cloud deployment |
| Utility | Lombok | 1.18.34 | Boilerplate code generation |

### Key Maven Dependencies

- `spring-boot-starter-security` — Authentication & authorization
- `spring-boot-starter-websocket` — WebSocket/STOMP
- `spring-boot-starter-data-jpa` — ORM
- `spring-boot-starter-kafka` — Message broker
- `spring-boot-starter-data-redis` — Cache & Pub/Sub
- `jjwt` (0.11.5) — JWT token handling
- `spring-security-crypto` — BCrypt password encoding
- `bucket4j_jdk17-core` + `bucket4j_jdk17-lettuce` (8.16.1) — Distributed rate limiting via Redis
- `owasp-java-html-sanitizer` (20240325.1) — HTML tag stripping for XSS prevention

---

## 2. Project Structure

```
chat-app-v1/
├── src/main/java/com/mg/chat_app/
│   ├── ChatAppApplication.java              # Entry point
│   ├── controller/
│   │   ├── AuthController.java              # Registration, login, refresh
│   │   ├── ChatRestController.java          # REST messaging endpoints
│   │   ├── ChatWebSocketController.java     # STOMP message handlers
│   │   ├── GroupController.java             # Group CRUD & messaging
│   │   └── VersionController.java           # /api/version endpoint (commit SHA)
│   ├── service/
│   │   ├── ChatService.java                 # Message persistence + Kafka publish
│   │   ├── GroupService.java                # Group management logic
│   │   ├── InputSanitizer.java              # OWASP HTML sanitization for chat messages
│   │   ├── JwtService.java                  # Token generation, validation + Redis-backed rotation
│   │   ├── WebSocketSessionService.java     # Session tracking in Redis
│   │   └── RedisMessageBridge.java          # Redis Pub/Sub <-> WebSocket bridge
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── MessageRepository.java
│   │   ├── ChatGroupRepository.java
│   │   ├── GroupMemberRepository.java
│   │   └── PresenceRepository.java
│   ├── entity/
│   │   ├── User.java
│   │   ├── Message.java
│   │   ├── Presence.java
│   │   ├── ChatGroup.java
│   │   └── GroupMember.java
│   ├── model/
│   │   ├── MessageStatus.java               # SENT, DELIVERED, READ
│   │   ├── MessageType.java                 # DIRECT, GROUP
│   │   └── GroupRole.java                   # ADMIN, MEMBER
│   ├── dto/
│   │   ├── ChatMessageDto.java
│   │   ├── GroupDto.java
│   │   ├── GroupMemberDto.java
│   │   ├── UserDto.java
│   │   ├── ReadReceiptDto.java
│   │   ├── LoginRequest.java
│   │   ├── TokenResponse.java
│   │   ├── RefreshTokenRequest.java         # Refresh token (validated, JSON body)
│   │   ├── SendMessageRequest.java
│   │   ├── CreateGroupRequest.java
│   │   └── GroupMessageRequest.java
│   ├── config/
│   │   ├── WebSocketConfig.java             # STOMP + SockJS setup
│   │   ├── WebSocketEventListener.java      # Connect/disconnect handling
│   │   ├── RedisConfig.java                 # Redis template + listener
│   │   ├── RateLimitConfig.java             # Bucket4j + Lettuce ProxyManager + filter registration
│   │   └── KafkaConfig.java                 # Kafka topics
│   ├── security/
│   │   ├── SecurityConfig.java              # HTTP security chain
│   │   ├── JwtAuthenticationFilter.java     # HTTP JWT filter
│   │   ├── RateLimitFilter.java             # Per-IP rate limiting on auth endpoints
│   │   └── WebSocketAuthInterceptor.java    # STOMP JWT interceptor
│   ├── kafka/
│   │   ├── ChatMessageProducer.java         # Publish to Kafka
│   │   ├── ChatMessageConsumer.java         # Direct message consumer
│   │   └── GroupMessageConsumer.java        # Group message consumer
│   └── exception/
│       └── GlobalExceptionHandler.java      # Centralized error handling
├── src/main/resources/
│   ├── application.yml                      # Local dev config
│   ├── application-k8s.yml                  # Kubernetes config
│   └── static/chat.html                     # Frontend UI
├── .github/
│   └── workflows/
│       └── deploy.yml                       # CI/CD: build, push ECR, deploy EKS
├── pom.xml
├── Dockerfile                               # Multi-stage build (injects APP_VERSION)
└── k8s/
    ├── namespace.yaml
    ├── configmap.yaml
    ├── secret.yaml
    ├── app-deployment.yaml
    ├── app-service.yaml
    ├── app-hpa.yaml
    ├── mysql-statefulset.yaml
    ├── mysql-service.yaml
    ├── redis-statefulset.yaml
    ├── redis-service.yaml
    ├── kafka-statefulset.yaml
    ├── kafka-service.yaml
    └── ingress.yaml
```

---

## 3. Entity/Model Structure & Relationships

### Entities

#### User

```java
@Entity @Table(name = "users")
- userId      (PK, BIGINT AUTO_INCREMENT)
- username    (VARCHAR UNIQUE NOT NULL)
- password    (VARCHAR NOT NULL)              // BCrypt hash
- firstName   (VARCHAR)
- lastName    (VARCHAR)
- status      (VARCHAR)
- createdAt   (DATETIME)
```

#### Message

```java
@Entity @Table(name = "messages")
- messageId   (PK, BIGINT AUTO_INCREMENT)
- senderId    (FK -> users.user_id, NOT NULL)
- receiverId  (FK -> users.user_id, nullable for group messages)
- groupId     (FK -> chat_groups.group_id, nullable for direct messages)
- content     (TEXT NOT NULL)
- status      (ENUM: SENT, DELIVERED, READ)
- messageType (ENUM: DIRECT, GROUP)
- createdAt   (DATETIME, immutable)
```

#### Presence

```java
@Entity @Table(name = "presence")
- userId      (PK -> users.user_id)
- isOnline    (BOOLEAN)
- lastSeen    (DATETIME)
```

#### ChatGroup

```java
@Entity @Table(name = "chat_groups")
- groupId     (PK, BIGINT AUTO_INCREMENT)
- name        (VARCHAR NOT NULL)
- createdBy   (FK -> users.user_id)
- createdAt   (DATETIME, immutable)
```

#### GroupMember

```java
@Entity @Table(name = "group_members")
- id          (PK, BIGINT AUTO_INCREMENT)
- groupId     (FK -> chat_groups.group_id)
- userId      (FK -> users.user_id)
- role        (ENUM: ADMIN, MEMBER)
- joinedAt    (DATETIME, immutable)
```

### Entity Relationships

```
User (1) ──── (N) Message    (as sender)
User (1) ──── (N) Message    (as receiver)
User (1) ──── (1) Presence
User (1) ──── (N) GroupMember
User (1) ──── (N) ChatGroup  (as creator)
ChatGroup (1) ──── (N) GroupMember
ChatGroup (1) ──── (N) Message
```

### Enums

| Enum | Values | Purpose |
|------|--------|---------|
| MessageStatus | SENT -> DELIVERED -> READ | Track message lifecycle |
| MessageType | DIRECT, GROUP | Distinguish 1-to-1 vs group |
| GroupRole | ADMIN, MEMBER | Authorization within groups |

---

## 4. Application Flow

### 4.1 Authentication Flow

```
User Registration/Login
    |
POST /api/auth/register or /api/auth/login
    |
AuthController validates credentials
    |
BCryptPasswordEncoder.matches(input, stored_hash)
    |
JwtService generates:
    - accessToken  (15 min, tokenType: "access")
    - refreshToken (7 days, tokenType: "refresh")
    |
Return TokenResponse {accessToken, refreshToken}
    |
Client stores tokens, uses accessToken for all API calls
```

#### Token Structure

```json
{
  "header":  { "alg": "HS256" },
  "payload": { "sub": "userId", "tokenType": "access|refresh", "iat": "...", "exp": "..." },
  "signature": "HMAC-SHA256(secret)"
}
```

**Why token type matters:** Without the type claim, a refresh token could be used as an
access token. The type field enforces strict validation — only access tokens authenticate
API/WebSocket requests, and refresh tokens only work with `/api/auth/refresh`.

### 4.2 Direct Message Flow (1-to-1)

```
User A sends message via REST
    |
POST /api/chat/send {receiverId, content}
    |
JwtAuthenticationFilter validates Authorization header
    |
ChatRestController extracts senderId from Principal
    |
ChatService.sendMessage():
    1. Set status = SENT
    2. Save to MySQL (source of truth)
    3. Build ChatMessageDto
    4. Publish to Kafka topic "chat-messages" (partitioned by receiverId)
    |
ChatMessageConsumer (Kafka listener):
    1. Receives message from Kafka
    2. Publish to Redis channel "chat:deliver:{receiverId}"
    3. Update DB: status = DELIVERED
    |
RedisMessageBridge (Redis listener on instance where User B is connected):
    1. Deserialize message
    2. Convert to WebSocket frame
    3. Send via SimpMessagingTemplate to /topic/messages/{receiverId}
    |
User B's browser receives via STOMP subscription
    |
User B marks as read -> STOMP /app/read
    |
ChatWebSocketController.markAsRead():
    1. Verify receiver authorization
    2. Update DB: status = READ
    3. Send ReadReceipt to /topic/read/{senderId}
    |
User A receives read confirmation
```

### 4.3 Group Message Flow

```
User A sends group message
    |
POST /api/groups/{groupId}/messages {content}
    |
GroupController:
    1. Verify user is group member
    2. Save Message with type=GROUP, groupId set
    3. Publish to Kafka "chat-group-messages" (partitioned by groupId)
    |
GroupMessageConsumer:
    1. Get all group member IDs (except sender)
    2. For each member: publishToUser(memberId)
    3. Also publishToGroup(groupId)
    |
Multiple Redis channels:
    - chat:deliver:memberId_1
    - chat:deliver:memberId_2
    - chat:group:groupId
    |
Each instance with connected group members receives
and pushes to /topic/groups/{groupId}
```

### 4.4 WebSocket Lifecycle

```
Browser connects
    |
new SockJS("/ws")
    |
HTTP upgrade handshake
    |
STOMP CONNECT frame with Authorization header
    |
WebSocketAuthInterceptor.preSend():
    1. Extract token from "Authorization" native header
    2. Validate with JwtService.validateAccessToken()
    3. Create UsernamePasswordAuthenticationToken
    4. Set as Principal
    |
If auth fails -> MessageDeliveryException (connection rejected)
If auth succeeds -> STOMP CONNECTED response
    |
WebSocketEventListener.handleWebSocketConnect():
    1. Extract userId from Principal
    2. WebSocketSessionService.registerUser():
       - HSET ws:sessions userId sessionId
       - SET presence:userId ONLINE EX 300 (5 min TTL)
       - Update Presence entity: isOnline=true
    3. Broadcast presence update to /topic/presence
    |
User can now send/receive messages
    |
On disconnect:
    WebSocketEventListener.handleWebSocketDisconnect():
    1. WebSocketSessionService.removeUser()
    2. HDEL ws:sessions userId
    3. DEL presence:userId
    4. Update Presence entity: isOnline=false, lastSeen=now
    5. Broadcast presence to /topic/presence
```

### 4.5 Offline Message Retrieval

```
User comes online
    |
GET /api/chat/offline
    |
Query DB: messages WHERE receiverId = userId AND status = SENT
    |
Update all found messages: status = DELIVERED
    |
Return list to client
```

---

## 5. Security Setup

### 5.1 HTTP Request Security

```
HTTP Request with "Authorization: Bearer <token>"
    |
JwtAuthenticationFilter.doFilterInternal():
    1. Extract Authorization header
    2. Check for "Bearer " prefix
    3. Extract token substring
    4. jwtService.validateAccessToken(token)
    5. If valid -> Create UsernamePasswordAuthenticationToken
    6. Set in SecurityContextHolder
    7. Continue filter chain
    |
If token invalid -> Log WARN, pass through (unauthenticated)
    |
Spring Security authorization checks apply
```

### 5.2 WebSocket Security

```
STOMP CONNECT frame
    |
WebSocketAuthInterceptor.preSend():
    1. Check StompCommand == CONNECT
    2. Extract "Authorization" native header
    3. Validate with JwtService.validateAccessToken()
    4. Create Principal with userId
    5. accessor.setUser(auth)
    |
If auth fails -> throw MessageDeliveryException
    |
All subsequent STOMP frames have Principal available
```

### 5.3 SecurityConfig Rules

```java
/ws/**                              -> PERMIT ALL (auth at STOMP level)
/api/auth/**                        -> PERMIT ALL (register, login, refresh)
/ws/info/**                         -> PERMIT ALL (SockJS info endpoint)
/actuator/**                        -> PERMIT ALL (K8s health checks)
GET /, /chat.html, /static/**       -> PERMIT ALL (frontend)
Everything else                     -> REQUIRE AUTHENTICATED
```

Additional configuration:
- **CORS:** All origins allowed with credentials
- **CSRF:** Disabled (stateless, token-based)
- **Session:** STATELESS
- **Filter order:** `JwtAuthenticationFilter` placed before `UsernamePasswordAuthenticationFilter`

### 5.4 Password Security

```
Registration:
    plaintext -> BCryptPasswordEncoder.encode() -> "$2a$10$..." stored in DB

Login:
    BCryptPasswordEncoder.matches(input, storedHash) -> true/false
```

- BCrypt version: `$2a$`
- Cost factor: 10 (1024 rounds)
- 22-character embedded salt
- 60-character total hash stored in database

### 5.5 JWT Token Validation

```java
generateAccessToken(userId):
    - Claims: sub=userId, tokenType="access"
    - Signed with HMAC-SHA256
    - Expiry: 15 minutes (900000 ms)

generateRefreshToken(userId):
    - Claims: sub=userId, tokenType="refresh"
    - Signed with HMAC-SHA256
    - Expiry: 7 days (604800000 ms)

validateAccessToken(token):
    - Parse claims with signing key
    - Verify tokenType == "access"
    - Return userId if valid, throw JwtException if invalid/expired

validateRefreshToken(token):
    - Parse claims with signing key
    - Verify tokenType == "refresh"
    - Return userId if valid
```

---

## 6. WebSocket Setup & Real-Time Messaging

### 6.1 WebSocketConfig

```java
registerStompEndpoints():
    - Endpoint: /ws
    - Allowed origins: * (all)
    - SockJS fallback enabled

configureMessageBroker():
    - Application destination prefix: /app
    - Simple broker: /topic (in-memory)

configureClientInboundChannel():
    - WebSocketAuthInterceptor added (JWT validation on STOMP CONNECT)
```

### 6.2 STOMP Destinations

| Direction | Destination | Purpose |
|-----------|------------|---------|
| Client -> Server | `/app/read` | Mark message as read |
| Client -> Server | `/app/typing` | Send typing indicator |
| Server -> Client | `/topic/messages/{userId}` | Receive direct messages |
| Server -> Client | `/topic/groups/{groupId}` | Receive group messages |
| Server -> Client | `/topic/read/{senderId}` | Receive read receipts |
| Server -> Client | `/topic/typing/{receiverId}` | Receive typing indicators |
| Server -> Client | `/topic/presence` | Receive online/offline updates |

### 6.3 Redis Pub/Sub Bridge (Multi-Instance Delivery)

**Why needed:** A single Kafka consumer processes a message on one app instance, but the
target user may be connected via WebSocket to a different instance. Redis Pub/Sub broadcasts
to all instances; the one with the active WebSocket connection delivers the message.

#### Redis Channels

```
chat:deliver:{userId}    -> Direct messages to a specific user
chat:group:{groupId}     -> Group messages to a group
```

#### Cross-Instance Delivery Flow

```
Instance 1 (Kafka consumer):
    RedisMessageBridge.publishToUser(userId, dto)
    -> PUBLISH chat:deliver:userId <serialized_dto>
    |
All instances listening on chat:deliver:userId receive it
    |
Instance 2 (has target user's WebSocket connection):
    MessageListener deserializes message
    messagingTemplate.convertAndSend("/topic/messages/" + userId, dto)
    |
User's browser receives via STOMP subscription
```

### 6.4 WebSocket Session Management

```java
registerUser(userId, sessionId):
    1. HSET ws:sessions userId sessionId     (Redis Hash)
    2. SET presence:userId "ONLINE" EX 300   (5 min TTL)
    3. presenceRepository.save():
       - isOnline = true
    4. Subscribe to Redis channel for user

removeUser(userId):
    1. HDEL ws:sessions userId
    2. DEL presence:userId
    3. presenceRepository update:
       - isOnline = false
       - lastSeen = now
    4. Unsubscribe from Redis channel

isUserOnline(userId):
    - HEXISTS ws:sessions userId -> boolean

renewPresence(userId):
    - EXPIRE presence:userId 300 (extends TTL on heartbeat)
```

---

## 7. Controllers, Services & Repositories

### 7.1 Controllers

#### AuthController (`/api/auth`)

| Method | Endpoint | Input | Output |
|--------|----------|-------|--------|
| POST | `/register` | `LoginRequest {username, password}` | `TokenResponse {accessToken, refreshToken}` |
| POST | `/login` | `LoginRequest {username, password}` | `TokenResponse {accessToken, refreshToken}` |
| POST | `/refresh` | `RefreshTokenRequest {refreshToken}` (JSON) | `TokenResponse {newAccessToken, newRefreshToken}` |

Error responses:
- 409 if username already taken (register)
- 401 if invalid credentials (login) or invalid/reused refresh token
- 400 if validation fails
- 429 if rate limited (5 req/min login, 3 req/10min register per IP)

#### ChatRestController (`/api/chat`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/send` | Send direct message |
| GET | `/offline` | Fetch undelivered messages (status SENT -> DELIVERED) |
| GET | `/history?otherUserId=&page=&size=` | Paginated chat history between two users |
| GET | `/users` | List all users except current |
| GET | `/presence` | Map of userId -> isOnline |
| GET | `/unread-counts` | Map of senderId -> unread count |
| POST | `/heartbeat` | Renew online presence |
| POST | `/go-offline` | Explicitly go offline |

#### ChatWebSocketController (STOMP)

| Mapping | Input | Output |
|---------|-------|--------|
| `/app/read` | `ChatMessageDto {messageId, senderId, receiverId}` | `ReadReceiptDto` -> `/topic/read/{senderId}` |
| `/app/typing` | `ChatMessageDto {senderId, receiverId}` | Typing indicator -> `/topic/typing/{receiverId}` |

#### GroupController (`/api/groups`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/` | Create group with members |
| GET | `/` | List current user's groups |
| POST | `/{groupId}/members?userId=` | Add member (admin only) |
| DELETE | `/{groupId}/members/{userId}` | Remove member (admin only) |
| POST | `/{groupId}/messages` | Send group message |
| GET | `/{groupId}/messages?page=&size=` | Paginated group message history |

#### GlobalExceptionHandler

| Exception | HTTP Status | When |
|-----------|-------------|------|
| `MethodArgumentNotValidException` | 400 | Validation failure |
| `IllegalArgumentException` | 400 | Business logic error |
| `JwtException` | 401 | Invalid/expired/reused JWT tokens |
| `SecurityException` | 403 | Unauthorized action |
| `RuntimeException` | 500 | Unexpected error |

Response format:
```json
{
  "error": "error message",
  "timestamp": "2026-02-01T12:30:00"
}
```

### 7.2 Services

#### ChatService

```java
@Transactional
sendMessage(Message):
    1. inputSanitizer.sanitize(content)      // Strip HTML tags (XSS prevention)
    2. message.setStatus(SENT)
    3. messageRepository.save(message)       // DB write (source of truth)
    4. Build ChatMessageDto from saved entity
    5. producer.publish(dto)                 // Kafka (async delivery)
    6. Return DTO
```

#### GroupService

```java
@Transactional
createGroup(name, creatorId, memberIds):
    1. Save ChatGroup
    2. Add creator as ADMIN
    3. Add each member as MEMBER
    4. Return GroupDto

@Transactional
addMember(groupId, userId, requesterId):
    1. validateAdmin(groupId, requesterId)
    2. Check if already member
    3. Save GroupMember with role=MEMBER

@Transactional
removeMember(groupId, userId, requesterId):
    1. validateAdmin(groupId, requesterId)
    2. Delete GroupMember record

getGroupsForUser(userId):
    - Query all GroupMember records for user
    - Map to GroupDto with member details

isMember(groupId, userId):
    - Check if GroupMember exists

validateAdmin(groupId, userId):
    - Verify user has ADMIN role in group
    - Throw SecurityException if not
```

#### JwtService

```java
generateAccessToken(userId):
    - Build JWT: sub=userId, tokenType="access", exp=15min
    - Sign with HMAC-SHA256
    - Return compact serialized JWT

generateRefreshToken(userId):
    - Build JWT: sub=userId, tokenType="refresh", exp=7days
    - Sign with HMAC-SHA256

storeRefreshToken(userId, token):
    - Compute SHA-256 hash of token
    - Store in Redis: key="refresh_token:{userId}", TTL=7 days

rotateRefreshToken(oldToken):
    - Validate JWT signature and tokenType == "refresh"
    - Check SHA-256 hash matches Redis value
    - If mismatch → revoke (delete key) → throw JwtException (theft detection)
    - Delete old hash, generate new access + refresh pair
    - Store new refresh hash in Redis
    - Return TokenResponse with both new tokens

revokeRefreshToken(userId):
    - Delete Redis key "refresh_token:{userId}"

validateAccessToken(token):
    - Parse claims -> verify tokenType == "access"
    - Return userId if valid, throw JwtException otherwise

validateRefreshToken(token):
    - Parse claims -> verify tokenType == "refresh"
    - Return userId if valid

extractUserId(token):
    - Parse and return subject (userId)
```

#### WebSocketSessionService

```java
registerUser(userId, sessionId):
    1. HSET ws:sessions userId sessionId    (Redis Hash)
    2. SET presence:userId "ONLINE" EX 300  (5 min TTL)
    3. presenceRepository.save(): isOnline = true

removeUser(userId):
    1. HDEL ws:sessions userId
    2. DEL presence:userId
    3. presenceRepository: isOnline = false, lastSeen = now

isUserOnline(userId):
    - HEXISTS ws:sessions userId -> boolean

renewPresence(userId):
    - EXPIRE presence:userId 300 (extend TTL on heartbeat)
```

#### RedisMessageBridge

```java
publishToUser(userId, ChatMessageDto):
    - redisTemplate.convertAndSend("chat:deliver:" + userId, dto)

publishToGroup(groupId, ChatMessageDto):
    - redisTemplate.convertAndSend("chat:group:" + groupId, dto)

subscribeUser(userId):
    - Create MessageListener -> deliver via SimpMessagingTemplate
    - listenerContainer.addMessageListener(listener, ChannelTopic)

unsubscribeUser(userId):
    - listenerContainer.removeMessageListener()

subscribeGroup(groupId):
    - Similar to subscribeUser but for group channel
    - Deliver to /topic/groups/{groupId}
```

### 7.3 Repositories

| Repository | Key Methods |
|-----------|-------------|
| `UserRepository` | `findByUsername(String): Optional<User>` |
| `MessageRepository` | `findByReceiverIdAndStatus(Long, MessageStatus): List<Message>` |
| | `findConversation(user1, user2, Pageable): Page<Message>` (JPQL) |
| | `findByGroupIdOrderByCreatedAtDesc(Long, Pageable): Page<Message>` |
| | `countUnreadBySender(receiverId, statuses[]): List<Object[]>` (native query) |
| `ChatGroupRepository` | Inherited CRUD operations |
| `GroupMemberRepository` | `findByGroupId(Long)`, `findByUserId(Long)` |
| | `existsByGroupIdAndUserId(Long, Long): boolean` |
| | `deleteByGroupIdAndUserId(Long, Long): void` |
| `PresenceRepository` | Inherited CRUD operations |

### 7.4 Kafka Components

#### ChatMessageProducer

```java
publish(ChatMessageDto):
    - kafkaTemplate.send("chat-messages", receiverId.toString(), dto)
    - Partition key: receiverId (ensures ordering per receiver)

publishGroupMessage(ChatMessageDto):
    - kafkaTemplate.send("chat-group-messages", groupId.toString(), dto)
    - Partition key: groupId (ensures ordering per group)
```

#### ChatMessageConsumer (topic: `chat-messages`)

```java
@KafkaListener(topics = "chat-messages", groupId = "chat-group")
consume(ChatMessageDto):
    1. redisMessageBridge.publishToUser(receiverId, dto)
    2. messageRepository.findById() and update status = DELIVERED
```

#### GroupMessageConsumer (topic: `chat-group-messages`)

```java
@KafkaListener(topics = "chat-group-messages", groupId = "chat-group")
consume(ChatMessageDto):
    1. groupService.getGroupMemberIds(groupId) -> List<Long>
    2. For each member (except sender):
       - redisMessageBridge.publishToUser(memberId, dto)
    3. redisMessageBridge.publishToGroup(groupId, dto)
```

---

## 8. Configuration

### 8.1 application.yml (Local Development)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/chat-app-database
    username: chat-app-user
    password: dummypassword
  jpa:
    hibernate:
      ddl-auto: update
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      value-serializer: JsonSerializer
    consumer:
      group-id: chat-group
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8080

jwt:
  secret: this-is-a-very-long-secret-key-for-hs256-algorithm!!
  access-expiry-ms: 900000          # 15 minutes
  refresh-expiry-ms: 604800000      # 7 days
```

### 8.2 application-k8s.yml (Kubernetes)

```yaml
spring:
  datasource:
    url: jdbc:mysql://mysql-service.chat-app.svc.cluster.local:3306/chat-app-database
    username: ${DB_USERNAME}         # From K8s Secret
    password: ${DB_PASSWORD}         # From K8s Secret
  kafka:
    bootstrap-servers: kafka-service.chat-app.svc.cluster.local:9092
  data:
    redis:
      host: redis-service.chat-app.svc.cluster.local

jwt:
  secret: ${JWT_SECRET}              # From K8s Secret
```

### 8.3 Dockerfile (Multi-stage)

```
Stage 1 (Build):
    - Maven base image
    - Dependency caching layer
    - Compile and package into JAR

Stage 2 (Runtime):
    - Eclipse Temurin JRE 21 Alpine
    - Copy JAR from Stage 1
    - Expose port 8080
    - Run: java -jar app.jar
```

---

## 9. Message Delivery Guarantees

| Stage | Guarantee | Detail |
|-------|-----------|--------|
| REST -> DB | Synchronous, Transactional | Persisted before response |
| DB -> Kafka | At-least-once | Producer acknowledgements |
| Kafka -> Consumer | At-least-once | Consumer group offset tracking |
| Consumer -> Redis | Best-effort | If Redis fails, message stays SENT in DB |
| Redis -> WebSocket | Best-effort | If user offline, fetched via `/api/chat/offline` |
| **Overall** | **At-least-once** | **DB is source of truth, no message loss** |

### Message Status State Machine

```
SENT (saved in DB)
    |
DELIVERED (Kafka consumed, pushed via WebSocket or fetched via /offline)
    |
READ (user explicitly marked as read)
```

---

## 10. Kubernetes Deployment Architecture

### Namespace: `chat-app`

| Kind | Name | Replicas | Details |
|------|------|----------|---------|
| Deployment | chat-app | 2 (HPA 2-10) | CPU target 70% auto-scaling |
| StatefulSet | mysql | 1 | 10Gi persistent volume |
| StatefulSet | redis | 1 | 2Gi persistent volume |
| StatefulSet | kafka | 1 | 10Gi persistent volume, KRaft mode |
| Service | chat-app-service | - | ClusterIP, load balance across pods |
| Service | mysql-service | - | Headless, stable DNS |
| Service | redis-service | - | ClusterIP |
| Service | kafka-service | - | Headless for broker discovery |
| Ingress | chat-app-ingress | - | Nginx, WebSocket support |
| ConfigMap | chat-app-config | - | DB URL, Kafka/Redis hosts |
| Secret | chat-app-secret | - | DB credentials, JWT secret |
| HPA | chat-app-hpa | - | 2-10 pods, CPU 70% target |

### Pod Health Probes

```yaml
readinessProbe:
    httpGet: /actuator/health
    initialDelaySeconds: 30
    periodSeconds: 10

livenessProbe:
    httpGet: /actuator/health
    initialDelaySeconds: 60
    periodSeconds: 15
```

### Resource Requests/Limits

| Component | Memory (req/limit) | CPU (req/limit) |
|-----------|-------------------|-----------------|
| App | 512Mi / 1Gi | 250m / 1000m |
| MySQL | 512Mi / 1Gi | 250m / 500m |
| Redis | 128Mi / 256Mi | 100m / 250m |
| Kafka | 512Mi / 1Gi | 250m / 500m |

### Ingress Configuration

```yaml
Host: chat-app.local
Annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/websocket-services: "chat-app-service"
    nginx.ingress.kubernetes.io/proxy-http-version: "1.1"
    # Upgrade/Connection headers for WebSocket support
Path: / -> chat-app-service:8080
```

### Environment Variables

**From ConfigMap:**
```
SPRING_PROFILES_ACTIVE: k8s
SPRING_DATASOURCE_URL: jdbc:mysql://mysql-service.chat-app.svc.cluster.local:3306/chat-app-database
SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka-service.chat-app.svc.cluster.local:9092
SPRING_DATA_REDIS_HOST: redis-service.chat-app.svc.cluster.local
SPRING_DATA_REDIS_PORT: 6379
```

**From Secret:**
```
DB_USERNAME: chat-app-user
DB_PASSWORD: CHANGE_ME_IN_PRODUCTION
JWT_SECRET: CHANGE_ME_use-a-long-random-secret-at-least-256-bits
```

---

## 11. Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| Kafka for async messaging | Decouples persistence from delivery; ordering per partition by receiverId/groupId |
| Redis Pub/Sub for cross-node delivery | All app instances receive broadcast; instance with active WebSocket connection delivers |
| Redis for session state | Shared across instances; TTL auto-expires on crash |
| JWT access + refresh tokens | Stateless auth; short-lived access limits exposure; refresh enables long sessions |
| Token type differentiation | Prevents refresh token misuse as access token |
| STOMP over SockJS | Structured messaging protocol; browser fallback for environments without native WebSocket |
| MySQL for core data | Relational integrity; ACID guarantees for user and message data |
| DB-first then Kafka | Message persisted before async delivery; database is the single source of truth |
| Multi-instance with HPA | Horizontal scaling; no single point of failure for the application layer |
| Kubernetes + StatefulSets | Production-grade orchestration; persistent volumes for stateful services |
| Multi-stage Docker build | Smaller runtime image; build dependencies not included in final image |

---

## 12. CI/CD Pipeline

### Overview

GitHub Actions workflow (`.github/workflows/deploy.yml`) automates the full build-deploy cycle on every push to `main`.

### Pipeline Steps

```
Push to main → Checkout → AWS OIDC auth → ECR login → Docker build (SHA tag + latest)
→ Push to ECR → kubectl set image → rollout status (5 min timeout)
```

### Authentication

- **GitHub → AWS:** OIDC federation (no stored credentials). GitHub mints short-lived token → assumes IAM role `github-actions-chat-app`
- **AWS → EKS:** IAM role mapped in `aws-auth` ConfigMap with `system:masters` access

### Image Tagging

- Every build is tagged with the full commit SHA for traceability
- Also tagged as `latest` for baseline
- `kubectl set image` uses the SHA tag, which triggers a rolling update

### Version Endpoint

```
GET /api/version → {"version": "<commit-sha>"}
```

The commit SHA is passed as a Docker build-arg (`APP_VERSION`) → container ENV → Spring property (`-Dapp.version`), exposed via `VersionController`.

---

## 13. Security Considerations for Production

### Current Strengths

- JWT with HMAC-SHA256 signing and token type differentiation
- BCrypt password hashing with cost factor 10
- **Refresh token rotation** with SHA-256 hash stored in Redis; one-time-use with theft detection
- **Rate limiting** on auth endpoints via Bucket4j + Redis (5 req/min login, 3 req/10min register per IP)
- **Input sanitization** using OWASP HTML sanitizer on all chat messages (direct + group) to prevent stored XSS
- **JWT exception handling** via GlobalExceptionHandler mapping `JwtException` → 401
- HTTPS/TLS via ACM certificate on ALB Ingress
- CORS restricted to `https://chat.mukeshg.work.gd`
- Transactional message persistence before Kafka publish
- Authorization checks on group operations (admin validation)
- Centralized exception handling with proper HTTP status codes
- Stateless session management (no server-side session)
- WebSocket authentication at STOMP protocol level

### Recommendations for Further Hardening

- **Secret rotation:** Establish regular rotation schedule for JWT secrets and DB passwords
- **Audit logging:** Add logging for sensitive operations (login, group admin actions)
- **Kafka consumer lag monitoring:** Alert on growing consumer lag to detect delivery issues
- **Database connection pooling:** Configure HikariCP pool sizes for production load
- **Redis authentication:** Enable Redis AUTH in production
