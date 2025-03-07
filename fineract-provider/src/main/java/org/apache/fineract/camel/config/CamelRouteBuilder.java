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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.InvalidPayloadException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.fineract.camel.converters.StringToCommandProcessingResultConverter;
import org.apache.fineract.camel.converters.StringToCommandWrapperConverter;
import org.apache.fineract.camel.domain.TransactionStatusTrackingRepository;
import org.apache.fineract.camel.service.CamelProcessingError;
import org.apache.fineract.camel.service.CamelQueueProcessingService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.CommandWrapperJsonSerializer;
import org.apache.fineract.sse.service.SseEmitterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "fineract.events.camel.enabled", havingValue = "true")
public class CamelRouteBuilder extends RouteBuilder {

    private final FineractProperties properties;

    private final CamelQueueProcessingService commandProcessingService;

    private final CamelProcessingError commandErrorProcessor;

    private final CommandProcessingResultJsonSerializer commandProcessingResultJsonSerializer;
    private final CommandWrapperJsonSerializer commandWrapperJsonSerializer;

    private boolean routesConfigured = false;

    private final CamelContext camelContext;

    private final TransactionStatusTrackingRepository transactionStatusTrackingRepository;

    private final SseEmitterService emitterService;

    @PostConstruct
    public void init() {
        try {
            camelContext.addRoutes(this);
        } catch (Exception e) {
            log.error("Failed to add routes to CamelContext", e);
        }
    }

    @Override
    public void configure() throws Exception {

        if (routesConfigured) {
            return;
        }
        routesConfigured = true;

        FineractProperties.FineractCamelEventsProperties camelJmsProperties = properties.getEvents().getCamel();
        FineractProperties.FineractCamelEventsAsyncProperties asyncProperties = camelJmsProperties.getAsync();

        log.info("****** CamelBackendAsyncRoute.configure(): fineract.events.camel.jms.async.enabled = [{}] *****",
                asyncProperties.isEnabled());

        if (asyncProperties.isEnabled()) {

            camelContext.getTypeConverterRegistry()
                    .addTypeConverters(new StringToCommandProcessingResultConverter(commandProcessingResultJsonSerializer));
            camelContext.getTypeConverterRegistry().addTypeConverters(new StringToCommandWrapperConverter(commandWrapperJsonSerializer));

            FineractProperties.FineractExternalEventsConsumerRabbitMQProperties rabbitMQProperties = properties.getEvents().getExternal()
                    .getConsumer().getRabbitmq();

            final String requestQueue = getQueueConsumerConnectionString(rabbitMQProperties.getExchangeName(),
                    asyncProperties.getRequestQueueName());

            final String errorQueue = getQueueConsumerConnectionString(rabbitMQProperties.getExchangeName(),
                    asyncProperties.getErrorQueueName());

            final String resultQueue = getQueueConsumerConnectionString(rabbitMQProperties.getExchangeName(),
                    asyncProperties.getResultQueueName());

            from(errorQueue).bean(commandProcessingService, "emitErrorResult").multicast().to(ExchangePattern.InOnly, resultQueue)
                    .threads(asyncProperties.getMaxRequestConcurrentConsumers()).end();

            from(resultQueue).bean(commandProcessingService, "emitResult").end();

            from(requestQueue).process(commandProcessingService::markAsProcessing).onException(InvalidPayloadException.class).handled(true)
                    .process(exchange -> {
                        Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                        // Log this specific error
                        log.error("Type conversion error:", caused);
                        commandProcessingService.logError(exchange);
                    }).to(ExchangePattern.InOnly, errorQueue).end().onException(Exception.class).handled(true).process(exchange -> {
                        Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);

                        final RuntimeException mappable = ErrorHandler.getMappable(caused);
                        exchange.getIn().setBody(commandProcessingResultJsonSerializer.serialize(mappable));
                        commandProcessingService.logError(exchange);
                    }).handled(true).to(ExchangePattern.InOnly, errorQueue).end().bean(commandProcessingService, "process")
                    .threads(asyncProperties.getMaxRequestConcurrentConsumers()).end();

            final String sseEventConnectionTopic = getTopicConnectionString(rabbitMQProperties.getTopicExchangeName(),
                    asyncProperties.getSseRoutingKey());

            from(sseEventConnectionTopic).bean(emitterService, "cleanupOldConnections").end();

        }

    }

    private String getQueueConsumerConnectionString(String exchangeName, String queueName) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?queues=" + queueName + "&concurrentConsumers="
                + properties.getEvents().getCamel().getAsync().getMaxRequestConcurrentConsumers()
                + "&testConnectionOnStartup=true&acknowledgeMode=AUTO"
                + "&asyncConsumer=true&rejectAndDontRequeue=true&autoDeclare=true&exchangeType=direct&arg.queue.durable="
                + properties.getEvents().getExternal().getConsumer().getRabbitmq().getDurable();
    }

    private String getTopicConnectionString(String topicExchangeName, String sseRoutingKey) {

        return properties.getEvents().getCamel().getQueueSystem() + ":" + topicExchangeName + "?queues=" + sseRoutingKey
                + "&rejectAndDontRequeue=true&testConnectionOnStartup=true&exchangeType=topic&acknowledgeMode=AUTO";
    }
}
