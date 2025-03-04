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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.fineract.camel.helper.BusinessDateSerializer;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.provider.CommandHandlerProvider;
import org.apache.fineract.commands.service.CommandSourceService;
import org.apache.fineract.commands.service.IdempotencyKeyResolver;
import org.apache.fineract.commands.service.SynchronousCommandProcessingService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.sse.service.SseEmitterService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.PermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(value = "fineract.events.camel.jms.enabled", havingValue = "true")
public class CamelAsyncCommandProcessingServiceImpl extends SynchronousCommandProcessingService {

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private FineractProperties properties;

    @Autowired
    private MDCWrapper mdcWrapper;

    @Autowired
    private BusinessDateSerializer businessDateSerializer;

    @Autowired
    private PlatformSecurityContext securityContext;

    @Autowired
    private SseEmitterService emitterService;

    @Autowired
    private PermissionRepository permissionRepository;

    @Lazy
    @Autowired
    private SynchronousCommandProcessingService synchronousCommandProcessingService;

    public CamelAsyncCommandProcessingServiceImpl(PlatformSecurityContext context, ApplicationContext applicationContext,
            ToApiJsonSerializer<Map<String, Object>> toApiJsonSerializer,
            ToApiJsonSerializer<CommandProcessingResult> toApiResultJsonSerializer, ConfigurationDomainService configurationDomainService,
            CommandHandlerProvider commandHandlerProvider, IdempotencyKeyResolver idempotencyKeyResolver,
            CommandSourceService commandSourceService, FineractRequestContextHolder fineractRequestContextHolder) {
        super(context, applicationContext, toApiJsonSerializer, toApiResultJsonSerializer, configurationDomainService,
                commandHandlerProvider, idempotencyKeyResolver, commandSourceService, fineractRequestContextHolder);
    }

    @Override
    public CommandProcessingResult executeCommand(CommandWrapper wrapper, JsonCommand command, boolean isApprovedByChecker) {

        if (properties.getEvents().getCamel().getJms().isEnabled() && properties.getEvents().getCamel().getJms().getAsync().isEnabled()
                && wrapper.isRequestAsync()) {

            synchronousCommandProcessingService.executeAsyncCommand(wrapper, isApprovedByChecker);
            return CommandProcessingResult.correlationIdResult(mdcWrapper.get("correlationId"));

        }

        return super.executeCommand(wrapper, command, isApprovedByChecker);
    }

    @Async
    @Override
    public void executeAsyncCommand(CommandWrapper wrapper, boolean isApprovedByChecker) {
        final AppUser appUser = this.securityContext
                .authenticatedUser(CommandWrapper.wrap(wrapper.actionName(), wrapper.entityName(), null, null));
        final String authToken = ThreadLocalContextUtil.getAuthToken();
        final String correlationId = mdcWrapper.get("correlationId");
        final String tenantIdentifier = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
        final HashMap<BusinessDateType, LocalDate> businessDate = ThreadLocalContextUtil.getBusinessDates();
        final ActionContext actionContext = ThreadLocalContextUtil.getActionContext();
        final String serializedBusinessDates = getSerializedBusinessDates(businessDate);
        final String ipAddress = ThreadLocalContextUtil.getClientIpAddr();
        final String userAgent = ThreadLocalContextUtil.getClientUserAgent();
        final String fingerPrint = emitterService.sseFingerPrint(userAgent, ipAddress);

        final Map<String, Object> requestHeaders = Map.of(FINERACT_HEADER_CORRELATION_ID, correlationId, FINERACT_HEADER_AUTH_TOKEN,
                authToken, FINERACT_HEADER_RUN_AS, appUser.getUsername(), FINERACT_HEADER_TENANT_ID, tenantIdentifier,
                FINERACT_HEADER_APPROVED_BY_CHECKER, isApprovedByChecker, FINERACT_HEADER_BUSINESS_DATE, serializedBusinessDates,
                FINERACT_HEADER_ACTION_CONTEXT, actionContext.toString(), FINERACT_HEADER_X_FINGERPRINT, fingerPrint);

        final String queueName = properties.getEvents().getCamel().getJms().getQueueSystem() + ":queue:"
                + properties.getEvents().getCamel().getJms().getAsync().getRequestQueueName();

        producerTemplate.sendBodyAndHeaders(queueName, ExchangePattern.InOnly, wrapper, requestHeaders);
    }

    private String getSerializedBusinessDates(final HashMap<BusinessDateType, LocalDate> businessDate) {

        if (businessDate != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                businessDateSerializer.marshal(businessDate, baos);
            } catch (Exception e) {
                // Handle serialization error
                throw new RuntimeException("Failed to serialize business dates", e);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
        return null;
    }
}
