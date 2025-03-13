/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fineract.sse.service;

import com.google.common.base.Splitter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@ConditionalOnProperty(value = "fineract.redis.enabled", havingValue = "true")
@Service
@Slf4j
public class SseEmitterServiceImpl implements SseEmitterService {

    private static final ConcurrentHashMap<String, ClientConnection> SSE_CLIENT_CONNECTION = new ConcurrentHashMap<>();
    private final PlatformSecurityContext context;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String REDIS_CLIENT_KEY_PREFIX = "sse:client:";
    private static final String REDIS_MESSAGE_KEY_PREFIX = "sse:messages:";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final RetryRegistry retryRegistry;

    private Sse sse;

    @Autowired
    FineractProperties fineractProperties;

    @Autowired
    @Lazy
    private ProducerTemplate producerTemplate;

    @Override
    public void init(Sse sse) {
        if (this.sse == null) {
            this.sse = sse;
        }
    }

    @Autowired
    public SseEmitterServiceImpl(PlatformSecurityContext context, RedisTemplate<String, String> redisTemplate) {
        this.context = context;
        this.redisTemplate = redisTemplate;

        RetryConfig config = RetryConfig.custom().maxAttempts(MAX_RETRY_ATTEMPTS).waitDuration(Duration.ofMillis(100))
                .retryOnException(e -> e instanceof IOException).build();
        this.retryRegistry = RetryRegistry.of(config);
    }

    @Override
    public void sendEvent(String fingerPrint, String correlationId, String event) {
        final ClientConnection connection = SSE_CLIENT_CONNECTION.get(fingerPrint);
        if (connection != null) {
            SseEventSink emitter = connection.getEventSink();
            try {

                if (emitter.isClosed()) {
                    log.info("Emitter already closed for fingerprint: {}", fingerPrint);
                    removeClient(fingerPrint);
                    cacheMessageInRedis(fingerPrint, correlationId, event);
                    return;
                }

                emitter.send(sse.newEventBuilder().id(correlationId).data(String.class, event).build()).exceptionally(e -> {
                    log.error("Failed to send event to client. Fingerprint: {}, CorrelationId: {}", fingerPrint, correlationId, e);
                    cacheMessageInRedis(fingerPrint, correlationId, event);
                    removeClient(fingerPrint);
                    return null;
                }).thenAccept(result -> {
                    log.info("Event sent to client. Fingerprint: {}, CorrelationId: {}", fingerPrint, correlationId);
                });
            } catch (IllegalStateException e) {
                // The emitter is closed or in an invalid state
                log.warn("Failed to send event to client. Emitter might be closed. Fingerprint: {}, CorrelationId: {}", fingerPrint,
                        correlationId);
                removeClient(fingerPrint);
                cacheMessageInRedis(fingerPrint, correlationId, event);
            }
        } else {
            log.warn("No client found while sending event for fingerprint: {} and correlationId: {}", fingerPrint, correlationId);
        }
    }

    @SneakyThrows
    @Override
    public void registerClient(SseEventSink eventSink) {
        final String userAgent = ThreadLocalContextUtil.getClientUserAgent();
        final String ipAddress = ThreadLocalContextUtil.getClientIpAddr();
        String fingerPrint = sseFingerPrint(userAgent, ipAddress);

        if (SSE_CLIENT_CONNECTION.containsKey(fingerPrint)) {
            removeClient(fingerPrint);
        }

        ClientConnection clientConnection = new ClientConnection(fingerPrint, System.currentTimeMillis(), eventSink);
        SSE_CLIENT_CONNECTION.put(fingerPrint, clientConnection);

        if (!eventSink.isClosed()) {
            try {
                // Store client info in Redis with expiration
                String redisClientKey = REDIS_CLIENT_KEY_PREFIX + fingerPrint;
                redisTemplate.opsForValue().set(redisClientKey, "active", 1, TimeUnit.HOURS);

                // Attempt to send any cached messages
                sendCachedMessages(fingerPrint);
                broadcastNewConnection(clientConnection);
            } catch (Exception ex) {
                log.error("Error for client with fingerprint: {}", fingerPrint, ex);
                removeClient(fingerPrint);
                eventSink.close(); // Close the event sink in case of error
            }
        }

    }

    @SneakyThrows
    @Override
    public void removeClient(String fingerPrint) {
        final ClientConnection connection = SSE_CLIENT_CONNECTION.get(fingerPrint);
        SseEventSink eventSink = connection.getEventSink();
        if (!eventSink.isClosed()) {
            eventSink.close();
        }
        SSE_CLIENT_CONNECTION.remove(fingerPrint);
        String redisClientKey = REDIS_CLIENT_KEY_PREFIX + fingerPrint;
        redisTemplate.delete(redisClientKey);
    }

    @Override
    public String sseFingerPrint(String userAgent, String ipAddress) {
        Long userId = context.authenticatedUser().getId();
        String rawFingerprintData = userAgent + ipAddress + userId;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawFingerprintData.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate SSE fingerprint", e);
        }
    }

    private void cacheMessageInRedis(String fingerPrint, String correlationId, String message) {
        String redisMessageKey = REDIS_MESSAGE_KEY_PREFIX + fingerPrint;
        redisTemplate.opsForList().rightPush(redisMessageKey, correlationId + ">" + message);
        redisTemplate.expire(redisMessageKey, 1, TimeUnit.HOURS); // Set expiration to clean up old messages
    }

    private void sendCachedMessages(String fingerPrint) {
        final String redisMessageKey = REDIS_MESSAGE_KEY_PREFIX + fingerPrint;
        List<String> messages = redisTemplate.opsForList().range(redisMessageKey, 0, -1);

        if (messages != null && !messages.isEmpty()) {
            Retry retry = retryRegistry.retry("sendCachedMessages");

            for (String message : messages) {
                Supplier<Boolean> retryableSupplier = Retry.decorateSupplier(retry, () -> {
                    List<String> parts = Splitter.on(">").splitToList(message);
                    this.sendEvent(fingerPrint, parts.get(0), parts.get(1));
                    return true;
                });

                try {
                    boolean sent = retryableSupplier.get();
                    if (sent) {
                        redisTemplate.opsForList().remove(redisMessageKey, 1, message);
                    } else {
                        log.error("Failed to send cached message to client {} after {} attempts. Discarding message.", fingerPrint,
                                MAX_RETRY_ATTEMPTS);
                        redisTemplate.opsForList().remove(redisMessageKey, 1, message);
                    }
                } catch (Exception e) {
                    log.error("Error occurred while trying to send cached message to client {}", fingerPrint, e);
                    redisTemplate.opsForList().remove(redisMessageKey, 1, message);
                }
            }
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void broadcastNewConnection(final ClientConnection clientConnection) {

        final String name = fineractProperties.getEvents().getCamel().getAsync().getSseRoutingKey();
        final String topicName = fineractProperties.getEvents().getCamel().getQueueSystem() + ":topic:" + name;

        producerTemplate.send(topicName, exchange -> {

            Map<String, String> messageBody = Map.of("fingerPrint", clientConnection.getFingerPrint(), "timestamp",
                    String.valueOf(clientConnection.getTimeStamp()));

            exchange.getIn().setBody(messageBody);
            exchange.getIn().setHeader("contentType", "application/json");
        });

        log.info("New SSE connection queued for client: {}", clientConnection.getFingerPrint());
    }

    @Override
    public void cleanupOldConnections(Exchange exchange) {
        try {
            // Extract information from the message
            final Map<String, String> messageBody = exchange.getIn().getBody(Map.class);
            final String fingerPrint = messageBody.get("fingerPrint");
            final Long timestamp = Long.valueOf(messageBody.get("timestamp"));

            final List<ClientConnection> clientConnections = SSE_CLIENT_CONNECTION.values().stream()
                    .filter(connection -> connection.getFingerPrint().equals(fingerPrint)).toList();

            clientConnections.forEach(clientConnection -> {
                if (clientConnection.getTimeStamp() < timestamp) {
                    removeClient(clientConnection.getFingerPrint());
                }
            });

        } catch (Exception e) {
            log.error("Error processing connection cleanup message", e);
        }
    }

    @Data
    @AllArgsConstructor
    private static final class ClientConnection {

        private String fingerPrint;
        private Long timeStamp;
        private SseEventSink eventSink;
    }
}
