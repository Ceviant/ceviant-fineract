
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

package org.apache.fineract.camel.service;

import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_ACTION_CONTEXT;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_APPROVED_BY_CHECKER;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_AUTH_TOKEN;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_BUSINESS_DATE;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_CORRELATION_ID;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_RUN_AS;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_TENANT_ID;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_X_FINGERPRINT;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.jano7.executor.BoundedStrategy;
import com.jano7.executor.KeyRunnable;
import com.jano7.executor.KeySequentialBoundedExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.camel.data.CamelOperation;
import org.apache.fineract.camel.data.TransactionStatus;
import org.apache.fineract.camel.domain.TransactionStatusTracking;
import org.apache.fineract.camel.domain.TransactionStatusTrackingRepository;
import org.apache.fineract.camel.helper.BusinessDateSerializer;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandProcessingService;
import org.apache.fineract.commands.service.CommandSourceService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.sse.service.SseEmitterService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "fineract.events.camel.enabled", havingValue = "true")
public class CamelQueueProcessingService {

    private final TenantDetailsService tenantDetailsService;

    @Lazy
    private final CommandProcessingService processingService;

    private final FromJsonHelper fromApiJsonHelper;

    private final AppUserRepository appUserRepository;

    private final BusinessDateSerializer businessDateSerializer;

    private final Gson gson = new Gson();

    private final SseEmitterService emitterService;

    private final ProducerTemplate producerTemplate;

    private final CommandSourceService commandSourceService;

    private final FineractProperties properties;

    private final TransactionStatusTrackingRepository transactionStatusTrackingRepository;

    private ExecutorService underlyingExecutor;
    private KeySequentialBoundedExecutor keyExecutor;

    public void process(@Header(FINERACT_HEADER_CORRELATION_ID) String correlationId, @Header(FINERACT_HEADER_AUTH_TOKEN) String authToken,
            @Header(FINERACT_HEADER_RUN_AS) String username, @Header(FINERACT_HEADER_TENANT_ID) String tenantId,
            @Header(FINERACT_HEADER_APPROVED_BY_CHECKER) Boolean approvedByChecker,
            @Header(FINERACT_HEADER_BUSINESS_DATE) String businessDate, @Header(FINERACT_HEADER_ACTION_CONTEXT) String actionContext,
            @Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint, @Body CommandWrapper commandWrapper) {

        String savingsId = generateKey(commandWrapper);

        KeyRunnable<String> runnable = new KeyRunnable<>(savingsId, () -> {

            try {
                final FineractPlatformTenant tenant = this.tenantDetailsService.loadTenantById(tenantId);

                ThreadLocalContextUtil.setTenant(tenant);
                ThreadLocalContextUtil.setAuthToken(authToken);

                if (actionContext != null) {
                    ThreadLocalContextUtil.setActionContext(ActionContext.valueOf(actionContext));
                }

                if (businessDate != null) {
                    try (InputStream stream = new ByteArrayInputStream(businessDate.getBytes(StandardCharsets.UTF_8))) {
                        HashMap<BusinessDateType, LocalDate> businessDates = (HashMap<BusinessDateType, LocalDate>) businessDateSerializer
                                .unmarshal(stream);
                        ThreadLocalContextUtil.setBusinessDates(businessDates);

                    } catch (Exception e) {
                        // Handle deserialization error
                        throw new RuntimeException("Failed to deserialize business dates", e);
                    }
                }

                final AppUser user = appUserRepository.findAppUserByName(username);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, user.getPassword()));

                final JsonElement parsedCommand = this.fromApiJsonHelper.parse(commandWrapper.getJson());
                JsonCommand command = JsonCommand.from(commandWrapper.getJson(), parsedCommand, this.fromApiJsonHelper,
                        commandWrapper.getEntityName(), commandWrapper.getEntityId(), commandWrapper.getSubentityId(),
                        commandWrapper.getGroupId(), commandWrapper.getClientId(), commandWrapper.getLoanId(),
                        commandWrapper.getSavingsId(), commandWrapper.getTransactionId(), commandWrapper.getHref(),
                        commandWrapper.getProductId(), commandWrapper.getCreditBureauId(), commandWrapper.getOrganisationCreditBureauId(),
                        commandWrapper.getJobName(), commandWrapper.getTransactionAmount(), commandWrapper.getUseRef(),
                        commandWrapper.getReference());
                commandWrapper.setRequestAsync(false);

                CommandProcessingResult result = processingService.executeCommand(commandWrapper, command, approvedByChecker);
                result.setCorrelationId(correlationId);

                TransactionStatusTracking successStatus = TransactionStatusTracking.builder().id(correlationId)
                        .operation(CamelOperation.fromString(commandWrapper.getOperation())).lastModifiedDate(LocalDateTime.now())
                        .errorMessage(gson.toJson(result)).status(TransactionStatus.COMPLETED).build();
                transactionStatusTrackingRepository.saveAndFlush(successStatus);

                final String resultTopic = getTopicProducer(
                        properties.getEvents().getExternal().getConsumer().getRabbitmq().getTopicExchangeName(),
                        properties.getEvents().getCamel().getAsync().getResultQueueName());

                Map<String, Object> headers = Map.of(FINERACT_HEADER_X_FINGERPRINT, fingerPrint, FINERACT_HEADER_CORRELATION_ID,
                        correlationId);

                producerTemplate.sendBodyAndHeaders(resultTopic, ExchangePattern.InOnly, getWrappedResponse(successStatus, result),
                        headers);

            } catch (Throwable caused) {
                final RuntimeException mappable = ErrorHandler.getMappable(caused);
                final ErrorInfo errorInfo = commandSourceService.generateErrorInfo(mappable);
                String body = gson.toJson(errorInfo);

                String errorQueue = getTopicProducer(
                        properties.getEvents().getExternal().getConsumer().getRabbitmq().getTopicExchangeName(),
                        properties.getEvents().getCamel().getAsync().getErrorQueueName());

                Map<String, Object> headers = Map.of(FINERACT_HEADER_X_FINGERPRINT, fingerPrint, FINERACT_HEADER_CORRELATION_ID,
                        correlationId);

                // Create or update transaction status as FAILED
                TransactionStatusTracking failedStatus = TransactionStatusTracking.builder().id(correlationId).errorMessage(body)
                        .operation(CamelOperation.fromString(commandWrapper.getOperation())).lastModifiedDate(LocalDateTime.now())
                        .status(TransactionStatus.FAILED).build();
                transactionStatusTrackingRepository.save(failedStatus);
                log.info("Transaction {} marked as FAILED with error: {}", correlationId, body);

                producerTemplate.sendBodyAndHeaders(errorQueue, ExchangePattern.InOnly, getWrappedResponse(failedStatus, errorInfo),
                        headers);
            }
        });

        keyExecutor.execute(runnable);
    }

    public void emitResult(@Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint,
            @Header(FINERACT_HEADER_CORRELATION_ID) String correlationId, @Body CommandProcessingResult commandResult) {

        log.debug("Emitting result for fingerprint {} and correlationId {}", fingerPrint, correlationId);

        if (correlationId == null || correlationId.isEmpty()) {
            log.error("Cannot emit error result: Missing correlation ID");
            return;
        }
        emitterService.sendEvent(fingerPrint, correlationId, gson.toJson(commandResult));

    }

    public void emitErrorResult(String fingerPrint, String correlationId, String commandErrorResult) {

        log.debug("==== ERROR QUEUE DIAGNOSTIC LOG ====");
        log.debug("Received error message with correlationId: {}", correlationId);
        log.debug("Fingerprint: {}", fingerPrint);

        // Log the first part of the error result (might be large)
        String truncatedResult = commandErrorResult != null && commandErrorResult.length() > 500
                ? commandErrorResult.substring(0, 500) + "..."
                : commandErrorResult;
        log.debug("Error result content (truncated): {}", truncatedResult);

        // Log call stack to see what's triggering this method
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        log.debug("Call stack (top 5 elements):");
        for (int i = 1; i < Math.min(6, stackTrace.length); i++) {
            log.debug("  at {}", stackTrace[i]);
        }

        // Get and log current thread name and ID
        log.debug("Current thread: {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());

        log.debug("==== END DIAGNOSTIC LOG ====");

        log.info("Emitting result for fingerprint {} and correlationId {}", fingerPrint, correlationId);

        // Ensure we have both required parameters
        if (fingerPrint == null || correlationId == null) {
            log.error("Cannot emit result: Missing fingerprint or correlation ID");
            return;
        }

        emitterService.sendEvent(fingerPrint, correlationId, gson.toJson(commandErrorResult));
    }

    private String generateKey(CommandWrapper cmd) {
        String entityName = cmd.entityName();
        Long entityId = ObjectUtils.firstNonNull(cmd.getSavingsId(), cmd.getLoanId(), cmd.getClientId(), cmd.getGroupId(),
                cmd.getEntityId());
        return entityName + "-" + entityId;
    }

    @PostConstruct
    public void init() {
        log.info("ThreadPool Size: {}", properties.getEvents().getCamel().getAsync().getThreadPoolSize());
        log.info("ThreadPool Queue Size: {}", properties.getEvents().getCamel().getAsync().getThreadPoolQueueSize());

        var td = new ThreadFactory() {

            private final AtomicInteger threadNumber = new AtomicInteger(0);

            @Override
            public Thread newThread(@NotNull Runnable r) {
                Thread t = new Thread(r);
                t.setName("be-thread-" + threadNumber.incrementAndGet());
                t.setDaemon(false);
                return t;
            }
        };

        this.underlyingExecutor = Executors.newFixedThreadPool(properties.getEvents().getCamel().getAsync().getThreadPoolSize(), td);
        this.keyExecutor = new KeySequentialBoundedExecutor(properties.getEvents().getCamel().getAsync().getThreadPoolQueueSize(),
                BoundedStrategy.BLOCK, underlyingExecutor);
    }

    private String getTopicProducer(String exchangeName, String routingKey) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?routingKey=" + routingKey
                + "&testConnectionOnStartup=true&exchangeType=topic&arg.queue.durable="
                + properties.getEvents().getExternal().getConsumer().getRabbitmq().getDurable();
    }

    public void logError(Exchange exchange) {
        // Get the exception that caused the error
        Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String errorMessage = exception != null ? exception.getMessage() : "Unknown error";

        // Get correlation ID from exchange
        String correlationId = exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class);
        if (correlationId != null) {

            String tenantId = exchange.getIn().getHeader(FINERACT_HEADER_TENANT_ID, String.class);
            String authToken = exchange.getIn().getHeader(FINERACT_HEADER_AUTH_TOKEN, String.class);

            final FineractPlatformTenant tenant = this.tenantDetailsService.loadTenantById(tenantId);

            ThreadLocalContextUtil.setTenant(tenant);
            ThreadLocalContextUtil.setAuthToken(authToken);
            // Create or update transaction status as FAILED
            Optional<TransactionStatusTracking> trackingData = transactionStatusTrackingRepository.findById(correlationId);

            if (trackingData.isPresent()) {
                TransactionStatusTracking failedStatus = trackingData.get();
                failedStatus.setStatus(TransactionStatus.FAILED);
                failedStatus.setErrorMessage(errorMessage);
                failedStatus.setLastModifiedDate(LocalDateTime.now());
                transactionStatusTrackingRepository.save(failedStatus);

            } else {
                String operation = exchange.getIn().getHeader("operation", String.class);
                TransactionStatusTracking failedStatus = TransactionStatusTracking.builder().id(correlationId)
                        .status(TransactionStatus.FAILED).operation(CamelOperation.fromString(operation))
                        .lastModifiedDate(LocalDateTime.now()).build();
                transactionStatusTrackingRepository.save(failedStatus);
            }

        }

    }

    public void markAsProcessing(Exchange exchange) {
        // Get correlation ID from exchange
        String correlationId = exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class);
        String operation = exchange.getIn().getHeader("operation", String.class);

        // Create or update transaction status as QUEUED
        TransactionStatusTracking queuedStatus = TransactionStatusTracking.builder().operation(CamelOperation.fromString(operation))
                .id(correlationId).status(TransactionStatus.PROCESSING).build();
        queuedStatus.setLastModifiedDate(LocalDateTime.now());
        transactionStatusTrackingRepository.save(queuedStatus);
        log.info("Transaction {} marked as PROCESSING", correlationId);

    }

    public boolean isAlreadyProcessed(Exchange exchange) {

        String correlationId = exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class);

        if (correlationId == null) {
            log.info("No correlation ID found, treating as new message");
            return false;
        }

        String tenantId = exchange.getIn().getHeader(FINERACT_HEADER_TENANT_ID, String.class);
        String authToken = exchange.getIn().getHeader(FINERACT_HEADER_AUTH_TOKEN, String.class);

        final FineractPlatformTenant tenant = this.tenantDetailsService.loadTenantById(tenantId);

        ThreadLocalContextUtil.setTenant(tenant);
        ThreadLocalContextUtil.setAuthToken(authToken);

        // Check if the message has been processed before
        boolean processed = transactionStatusTrackingRepository.findById(correlationId).stream()
                .noneMatch(t -> t.getStatus().equals(TransactionStatus.QUEUED));

        log.debug("Message with correlationId {} already processed: {}", correlationId, processed);
        return processed;
    }

    public String getWrappedResponse(TransactionStatusTracking status, Object result) {
        return gson.toJson(SseEmitterBodyWrapper.builder().status(status.getStatus().name()).operation(status.getOperation().name())
                .body(result).build());
    }

    @Builder
    private static final class SseEmitterBodyWrapper {

        private String operation;
        private String status;
        private Object body;
    }
}
