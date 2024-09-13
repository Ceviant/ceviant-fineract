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

package org.apache.fineract.camel.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.camel.service.CamelProcessingError;
import org.apache.fineract.camel.service.CamelQueueProcessingService;
import org.apache.fineract.commands.service.CommandSourceService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformException;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.sse.service.SseEmitterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(value = "fineract.events.camel.jms.enabled", havingValue = "true")
public class CamelRouteBuilder extends RouteBuilder {

    @Autowired
    private FineractProperties properties;

    @Autowired
    private CamelQueueProcessingService commandProcessingService;

    @Autowired
    private CamelProcessingError commandErrorProcessor;

    @Autowired
    private SseEmitterService emitterService;

    @Autowired
    private CommandProcessingResultJsonSerializer commandProcessingResultJsonSerializer;

    @Autowired
    private CommandSourceService commandSourceService;

    @Override
    public void configure() throws Exception {

        FineractProperties.FineractCamelEventsProperties camelJmsProperties = properties.getEvents().getCamel();
        FineractProperties.FineractCamelJmsAsyncProperties asyncProperties = camelJmsProperties.getJms().getAsync();
        final String asyncQueueSystem = camelJmsProperties.getJms().getQueueSystem();
        log.info("****** CamelBackendAsyncRoute.configure(): fineract.events.camel.jms.async.enabled = [{}] *****",
                asyncProperties.isEnabled());

        if (asyncProperties.isEnabled()) {

            final String errorTopic = asyncQueueSystem + ":topic:" + asyncProperties.getErrorQueueName();
            final String resultTopic = asyncQueueSystem + ":topic:" + asyncProperties.getResultQueueName();
            final String requestQueue = asyncQueueSystem + ":queue:" + asyncProperties.getRequestQueueName();

            from(errorTopic).bean(commandProcessingService, "emitErrorResult").end();

            from(resultTopic).bean(commandProcessingService, "emitResult").end();

            from(requestQueue).onException(AbstractPlatformException.class).handled(true).process(exchange -> {
                Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);

                final RuntimeException mappable = ErrorHandler.getMappable(caused);
                final ErrorInfo errorInfo = commandSourceService.generateErrorInfo(mappable);
                exchange.getIn().setBody(commandProcessingResultJsonSerializer.serialize(errorInfo));
            }).handled(true).to(ExchangePattern.InOnly, errorTopic).end().bean(commandProcessingService, "process").multicast()
                    .to(ExchangePattern.InOnly, resultTopic).threads(asyncProperties.getMaxRequestConcurrentConsumers()).end();

            final String sseEventConnectionTopic = properties.getEvents().getCamel().getProducer().getJms().getSseTopicName();

            from("activemq:topic:" + sseEventConnectionTopic).bean(emitterService, "cleanupOldConnections").end();

        }

    }

}
