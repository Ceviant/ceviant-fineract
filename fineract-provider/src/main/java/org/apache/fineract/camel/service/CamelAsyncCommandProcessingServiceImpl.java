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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.fineract.commands.domain.CommandSource;
import org.apache.fineract.camel.domain.TransactionStatusTracking;
import org.apache.fineract.camel.domain.TransactionStatusTrackingRepository;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.provider.CommandHandlerProvider;
import org.apache.fineract.commands.service.CommandSourceService;
import org.apache.fineract.commands.service.IdempotencyKeyGenerator;
import org.apache.fineract.commands.service.IdempotencyKeyResolver;
import org.apache.fineract.commands.service.SynchronousCommandProcessingService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(value = "fineract.events.camel.async.enabled", havingValue = "true")
public class CamelAsyncCommandProcessingServiceImpl extends SynchronousCommandProcessingService {

    @Autowired
    private FineractProperties properties;

    @Autowired
    private MDCWrapper mdcWrapper;

    @Autowired
    private CamelAsyncProcessingServiceHelper camelAsyncProcessingServiceHelper;

    @Autowired
    private TransactionStatusTrackingRepository transactionStatusTrackingRepository;

    public CamelAsyncCommandProcessingServiceImpl(PlatformSecurityContext context, ApplicationContext applicationContext,
            ToApiJsonSerializer<Map<String, Object>> toApiJsonSerializer,
            ToApiJsonSerializer<CommandProcessingResult> toApiResultJsonSerializer, ConfigurationDomainService configurationDomainService,
            CommandHandlerProvider commandHandlerProvider, IdempotencyKeyResolver idempotencyKeyResolver,
            CommandSourceService commandSourceService, FineractRequestContextHolder fineractRequestContextHolder, IdempotencyKeyGenerator idempotencyKeyGenerator) {
        super(context, applicationContext, toApiJsonSerializer, toApiResultJsonSerializer, configurationDomainService,
                commandHandlerProvider, idempotencyKeyResolver, commandSourceService, fineractRequestContextHolder, idempotencyKeyGenerator);
    }

    @Override
    public CommandProcessingResult executeCommand(CommandWrapper wrapper, JsonCommand command, boolean isApprovedByChecker) {

        if (properties.getEvents().getCamel().isEnabled() && properties.getEvents().getCamel().getAsync().isEnabled()
                && wrapper.isRequestAsync()) {
            final String correlationId = mdcWrapper.get("correlationId");
            this.executeAsyncCommand(wrapper, isApprovedByChecker);
            return CommandProcessingResult.correlationIdResult(correlationId);

        }

        return super.executeCommand(wrapper, command, isApprovedByChecker);
    }

    @Override
    public CommandProcessingResult logCommand(CommandSource commandSource) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public void executeAsyncCommand(CommandWrapper wrapper, boolean isApprovedByChecker) {
        final String correlationId = mdcWrapper.get("correlationId");

        Optional<TransactionStatusTracking> exists = transactionStatusTrackingRepository.findById(correlationId);

        if (exists.isPresent()) {
            throw new PlatformDataIntegrityException("async.camel.command.duplicate.error", "Command with correlation id [" + correlationId
                    + "] already exists with status [{" + exists.get().getStatus().name() + "}]", "correlationId", correlationId);
        }
        camelAsyncProcessingServiceHelper.executeAsyncCommand(wrapper, correlationId, isApprovedByChecker, captureThreadContext());
    }

    private Map<String, Object> captureThreadContext() {
        Map<String, Object> context = new HashMap<>();

        // Capture authentication and tenant context
        context.put("authToken", ThreadLocalContextUtil.getAuthToken());
        if (ThreadLocalContextUtil.getTenant() != null) {
            context.put("tenant", ThreadLocalContextUtil.getTenant());
        }

        // Capture business dates and action context
        if (ThreadLocalContextUtil.getBusinessDates() != null) {
            context.put("businessDate", ThreadLocalContextUtil.getBusinessDates());
        }
        context.put("actionContext", ThreadLocalContextUtil.getActionContext().toString());

        // Capture client information
        if (ThreadLocalContextUtil.getClientUserAgent() != null) {
            context.put("userAgent", ThreadLocalContextUtil.getClientUserAgent());
        }
        if (ThreadLocalContextUtil.getClientIpAddr() != null) {
            context.put("ipAddress", ThreadLocalContextUtil.getClientIpAddr());
        }

        // Capture data source context if available
        if (ThreadLocalContextUtil.getDataSourceContext() != null) {
            context.put("dataSourceContext", ThreadLocalContextUtil.getDataSourceContext());
        }

        return context;
    }

}
