
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Body;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.fineract.batch.exception.ErrorInfo;
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
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.sse.service.SseEmitterService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
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

    private final CommandProcessingResultJsonSerializer commandProcessingResultJsonSerializer;

    private final SseEmitterService emitterService;

    private final ProducerTemplate producerTemplate;

    private final CommandSourceService commandSourceService;

    private final FineractProperties properties;

    private final TransactionStatusTrackingRepository transactionStatusTrackingRepository;

    @Value("${fineract.events.camel.async.thread-pool-size}")
    private Integer threadPoolSize;

    @Value("${fineract.events.camel.async.thread-pool-queue-size}")
    private Integer threadPoolQueueSize;

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
                        .errorMessage(commandProcessingResultJsonSerializer.serialize(result)).status(TransactionStatus.COMPLETED).build();
                transactionStatusTrackingRepository.saveAndFlush(successStatus);

                final String resultTopic = getTopicConnectionString(
                        properties.getEvents().getExternal().getConsumer().getRabbitmq().getTopicExchangeName(),
                        properties.getEvents().getCamel().getAsync().getResultRoutingKey());

                Map<String, Object> headers = Map.of(FINERACT_HEADER_X_FINGERPRINT, fingerPrint, FINERACT_HEADER_CORRELATION_ID,
                        correlationId);

                producerTemplate.sendBodyAndHeaders(resultTopic, ExchangePattern.InOnly, result, headers);

            } catch (Throwable caused) {
                log.error("Error processing command", caused);

                final RuntimeException mappable = ErrorHandler.getMappable(caused);
                final ErrorInfo errorInfo = commandSourceService.generateErrorInfo(mappable);
                String body = commandProcessingResultJsonSerializer.serialize(errorInfo);

                String errorQueue = getQueueProducerConnectionString(
                        properties.getEvents().getExternal().getConsumer().getRabbitmq().getQueueName(),
                        properties.getEvents().getCamel().getAsync().getErrorRoutingKey());

                Map<String, Object> headers = Map.of(FINERACT_HEADER_X_FINGERPRINT, fingerPrint, FINERACT_HEADER_CORRELATION_ID,
                        correlationId);

                // Create or update transaction status as FAILED
                TransactionStatusTracking failedStatus = TransactionStatusTracking.builder().id(correlationId).errorMessage(body)
                        .status(TransactionStatus.FAILED).build();
                transactionStatusTrackingRepository.save(failedStatus);
                log.info("Transaction {} marked as FAILED with error: {}", correlationId, body);

                producerTemplate.sendBodyAndHeaders(errorQueue, ExchangePattern.InOnly, body, headers);
            }
        });

        keyExecutor.execute(runnable);
    }

    public void emitResult(@Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint, @Body CommandProcessingResult commandResult) {

        emitterService.sendEvent(fingerPrint, commandResult.getCorrelationId(),
                commandProcessingResultJsonSerializer.serialize(commandResult));

    }

    public void emitErrorResult(@Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint,
            @Header(FINERACT_HEADER_CORRELATION_ID) String correlationId, @Body String commandErrorResult) {

        emitterService.sendEvent(fingerPrint, correlationId, commandProcessingResultJsonSerializer.serialize(commandErrorResult));
    }

    private String generateKey(CommandWrapper cmd) {
        String entityName = cmd.entityName();
        Long entityId = ObjectUtils.firstNonNull(cmd.getSavingsId(), cmd.getLoanId(), cmd.getClientId(), cmd.getGroupId(),
                cmd.getEntityId());
        return entityName + "-" + entityId;
    }

    @PostConstruct
    public void init() {
        log.info("ThreadPool Size: {}", this.threadPoolSize);
        log.info("ThreadPool Queue Size: {}", this.threadPoolQueueSize);

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

        this.underlyingExecutor = Executors.newFixedThreadPool(this.threadPoolSize, td);
        this.keyExecutor = new KeySequentialBoundedExecutor(threadPoolQueueSize, BoundedStrategy.BLOCK, underlyingExecutor);
    }

    public String getQueueProducerConnectionString(String exchangeName, String routingKey) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?routingKey=" + routingKey
                + "&testConnectionOnStartup=true";
    }

    public String getTopicConnectionString(String exchangeName, String routingKey) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?exchangeType=topic&routingKey=" + routingKey
                + "&concurrentConsumers=" + properties.getEvents().getCamel().getAsync().getMaxRequestConcurrentConsumers()
                + "&testConnectionOnStartup=true" + "&asyncConsumer=true&autoDeclare=true";
    }
}
