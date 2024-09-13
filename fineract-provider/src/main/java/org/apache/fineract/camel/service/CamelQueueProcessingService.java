
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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.camel.Body;
import org.apache.camel.Header;
import org.apache.fineract.camel.helper.BusinessDateSerializer;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandProcessingService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.sse.service.SseEmitterService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "fineract.events.camel.jms.enabled", havingValue = "true")
public class CamelQueueProcessingService {

    @Autowired
    private TenantDetailsService tenantDetailsService;

    @Autowired
    @Lazy
    private CommandProcessingService processingService;

    @Autowired
    private FromJsonHelper fromApiJsonHelper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private BusinessDateSerializer businessDateSerializer;

    @Autowired
    private SseEmitterService emitterService;

    @Autowired
    private CommandProcessingResultJsonSerializer commandProcessingResultJsonSerializer;

    public CommandProcessingResult process(@Header(FINERACT_HEADER_CORRELATION_ID) String correlationId,
            @Header(FINERACT_HEADER_AUTH_TOKEN) String authToken, @Header(FINERACT_HEADER_RUN_AS) String username,
            @Header(FINERACT_HEADER_TENANT_ID) String tenantId, @Header(FINERACT_HEADER_APPROVED_BY_CHECKER) Boolean approvedByChecker,
            @Header(FINERACT_HEADER_BUSINESS_DATE) String businessDate, @Header(FINERACT_HEADER_ACTION_CONTEXT) String actionContext,
            @Body CommandWrapper commandWrapper) {

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
                commandWrapper.getEntityName(), commandWrapper.getEntityId(), commandWrapper.getSubentityId(), commandWrapper.getGroupId(),
                commandWrapper.getClientId(), commandWrapper.getLoanId(), commandWrapper.getSavingsId(), commandWrapper.getTransactionId(),
                commandWrapper.getHref(), commandWrapper.getProductId(), commandWrapper.getCreditBureauId(),
                commandWrapper.getOrganisationCreditBureauId(), commandWrapper.getJobName());
        commandWrapper.setRequestAsync(false);

        CommandProcessingResult result = processingService.executeCommand(commandWrapper, command, approvedByChecker);
        result.setCorrelationId(correlationId);

        return result;
    }

    public void emitResult(@Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint, @Body CommandProcessingResult commandResult) {

        emitterService.sendEvent(fingerPrint, commandResult.getCorrelationId(),
                commandProcessingResultJsonSerializer.serialize(commandResult));

    }

    public void emitErrorResult(@Header(FINERACT_HEADER_X_FINGERPRINT) String fingerPrint,
            @Header(FINERACT_HEADER_CORRELATION_ID) String correlationId, @Body String commandErrorResult) {

        emitterService.sendEvent(fingerPrint, correlationId, commandErrorResult);

    }
}
