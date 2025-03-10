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

package org.apache.fineract.sse.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.spm.data.SurveyData;
import org.apache.fineract.sse.service.SseEmitterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Path("/v1/sse")
@Component
@Tag(name = "Server sent events API", description = "The API allows clients using asynchronous requests to subscribe to notifications from the server to update the state of the client application")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty("fineract.redis.enabled")
public class ServerSentEventsApiResource {

    private final SseEmitterService emitterService;

    private final PlatformSecurityContext securityContext;

    @Context
    public void setSse(Sse sse) {
        emitterService.init(sse);
    }

    @GET
    @Produces({ MediaType.SERVER_SENT_EVENTS })
    @Operation(summary = "Server sent event subscription", description = "")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SurveyData.class)))) })
    public void subscribe(@Context SseEventSink sink) {
        try {
            securityContext.authenticatedUser();
            emitterService.registerClient(sink);
        } catch (Exception e) {
            log.error("Error during SSE subscription", e);
            throw new RuntimeException("Failed to subscribe to SSE", e);
        }

    }
}
