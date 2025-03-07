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
import static org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.restoreThreadContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.fineract.camel.data.TransactionStatus;
import org.apache.fineract.camel.domain.TransactionStatusTracking;
import org.apache.fineract.camel.domain.TransactionStatusTrackingRepository;
import org.apache.fineract.camel.helper.BusinessDateSerializer;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.sse.service.SseEmitterService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CamelAsyncProcessingServiceHelper {

    private final BusinessDateSerializer businessDateSerializer;
    private final ProducerTemplate producerTemplate;
    private final PlatformSecurityContext securityContext;
    private final FineractProperties fineractProperties;
    private final TransactionStatusTrackingRepository transactionStatusTrackingRepository;
    private final ObjectMapper objectMapper; // Add ObjectMapper for JSON serialization
    private final SseEmitterService emitterService;

    @Async
    public void executeAsyncCommand(CommandWrapper wrapper, String correlationId, boolean isApprovedByChecker,
            Map<String, Object> threadContextCopy) {
        restoreThreadContext(threadContextCopy);
        final AppUser appUser = this.securityContext
                .authenticatedUser(CommandWrapper.wrap(wrapper.actionName(), wrapper.entityName(), null, null));
        final String authToken = ThreadLocalContextUtil.getAuthToken();
        final String tenantIdentifier = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
        final HashMap<BusinessDateType, LocalDate> businessDate = ThreadLocalContextUtil.getBusinessDates();
        final ActionContext actionContext = ThreadLocalContextUtil.getActionContext();
        final String serializedBusinessDates = getSerializedBusinessDates(businessDate);
        final String ipAddress = (String) threadContextCopy.get("ipAddress");
        final String userAgent = (String) threadContextCopy.get("userAgent");
        final String fingerPrint = emitterService.sseFingerPrint(userAgent, ipAddress);

        final Map<String, Object> requestHeaders = Map.of(FINERACT_HEADER_CORRELATION_ID, correlationId, FINERACT_HEADER_AUTH_TOKEN,
                authToken, FINERACT_HEADER_RUN_AS, appUser.getUsername(), FINERACT_HEADER_TENANT_ID, tenantIdentifier,
                FINERACT_HEADER_APPROVED_BY_CHECKER, isApprovedByChecker, FINERACT_HEADER_BUSINESS_DATE, serializedBusinessDates,
                FINERACT_HEADER_ACTION_CONTEXT, actionContext.toString(), FINERACT_HEADER_X_FINGERPRINT, fingerPrint);

        final String queueName = getQueueProducerConnectionString(
                fineractProperties.getEvents().getExternal().getConsumer().getRabbitmq().getExchangeName(),
                fineractProperties.getEvents().getCamel().getAsync().getRequestQueueName());

        TransactionStatusTracking status = TransactionStatusTracking.builder().id(correlationId).status(TransactionStatus.QUEUED).build();
        transactionStatusTrackingRepository.save(status);

        try {
            // Serialize CommandWrapper to JSON string or byte array
            String serializedWrapper = objectMapper.writeValueAsString(wrapper);
            producerTemplate.sendBodyAndHeaders(queueName, ExchangePattern.InOnly, serializedWrapper, requestHeaders);
        } catch (Exception e) {
            status.updateStatus(TransactionStatus.FAILED, "Failed to serialize command wrapper");
            transactionStatusTrackingRepository.saveAndFlush(status);
            throw new PlatformDataIntegrityException("async.camel.command.serialize.error", "Failed to serialize command wrapper", e);
        }
    }

    private String getSerializedBusinessDates(final HashMap<BusinessDateType, LocalDate> businessDate) {

        if (businessDate != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                businessDateSerializer.marshal(businessDate, baos);
            } catch (Exception e) {
                throw new PlatformDataIntegrityException("async.camel.businessdate.serialize.error", "Failed to serialize business dates");
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
        return null;
    }

    public String getQueueProducerConnectionString(String exchangeName, String routingKey) {
        return fineractProperties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?queues=" + routingKey
                + "&testConnectionOnStartup=true&exchangeType=direct&arg.queue.durable="
                + fineractProperties.getEvents().getExternal().getConsumer().getRabbitmq().getDurable();
    }

}
