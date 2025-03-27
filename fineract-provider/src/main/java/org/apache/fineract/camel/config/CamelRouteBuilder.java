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

import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_CORRELATION_ID;
import static org.apache.fineract.camel.constants.CamelConstants.FINERACT_HEADER_X_FINGERPRINT;

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

    private final CommandProcessingResultJsonSerializer commandProcessingResultJsonSerializer;
    private final CommandWrapperJsonSerializer commandWrapperJsonSerializer;

    private boolean routesConfigured = false;

    private final CamelContext camelContext;

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
        boolean isRequestNode = asyncProperties.isActiveRequestNode();
        boolean isSseNode = asyncProperties.isActiveSseNode();

        log.info("****** CamelBackendAsyncRoute.configure(): fineract.events.camel.jms.async.enabled = [{}] *****",
                asyncProperties.isEnabled());

        if (asyncProperties.isEnabled()) {

            camelContext.getTypeConverterRegistry()
                    .addTypeConverters(new StringToCommandProcessingResultConverter(commandProcessingResultJsonSerializer));
            camelContext.getTypeConverterRegistry().addTypeConverters(new StringToCommandWrapperConverter(commandWrapperJsonSerializer));

            FineractProperties.FineractExternalEventsConsumerRabbitMQProperties rabbitMQProperties = properties.getEvents().getExternal()
                    .getConsumer().getRabbitmq();

            final String errorQueue = getTopicConnectionString(rabbitMQProperties.getTopicExchangeName(),
                    asyncProperties.getErrorQueueName());

            if (isSseNode) {

                final String resultQueue = getTopicConnectionString(rabbitMQProperties.getTopicExchangeName(),
                        asyncProperties.getResultQueueName());

                from(errorQueue).routeId("errorQueueRoute").log("Processing message from error queue: ${body}").process(exchange -> {
                    // Pass the exchange to emitErrorResult method
                    String fingerprint = exchange.getIn().getHeader(FINERACT_HEADER_X_FINGERPRINT, String.class);
                    String correlationId = exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class);
                    String body = exchange.getIn().getBody(String.class);
                    commandProcessingService.emitErrorResult(fingerprint, correlationId, body);
                }).threads(asyncProperties.getMaxRequestConcurrentConsumers()).end();

                from(resultQueue).routeId("resultQueueRoute").bean(commandProcessingService, "emitResult").end();

                final String sseEventConnectionTopic = getTopicConnectionString(rabbitMQProperties.getTopicExchangeName(),
                        asyncProperties.getSseRoutingKey());

                from(sseEventConnectionTopic).bean(emitterService, "cleanupOldConnections").end();

            }

            if (isRequestNode) {
                final String requestQueue = getQueueConsumerConnectionString(rabbitMQProperties.getExchangeName(),
                        asyncProperties.getRequestQueueName(), asyncProperties.getRequestQueueName(), 1);

                from(requestQueue).routeId("requestQueueRoute")
                        .onException(InvalidPayloadException.class)
                            .handled(true)
                            .log("Invalid payload exception: ${exception.message}")
                            .process(exchange -> {
                                Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                                log.error("Type conversion error:", caused);
                                commandProcessingService.logError(exchange);
                            })
                            .to(ExchangePattern.InOnly, errorQueue)
                        .end()
                        .onException(Exception.class)
                            .handled(true)
                            .log("General exception: ${exception.message}")
                            .process(exchange -> {
                                Throwable caused = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                                final RuntimeException mappable = ErrorHandler.getMappable(caused);
                                exchange.getIn().setBody(commandProcessingResultJsonSerializer.serialize(mappable));
                                commandProcessingService.logError(exchange);
                            })
                            .to(ExchangePattern.InOnly, errorQueue)
                        .end()
                        .process(exchange -> {
                            String correlationId = exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class);
                            log.info("Received message with correlationId: {}", correlationId);
                            
                            // Check if already processed
                            boolean alreadyProcessed = commandProcessingService.isAlreadyProcessed(exchange);
                            if (alreadyProcessed) {
                                log.warn("Message with correlationId {} has already been processed. Acknowledging and skipping.", correlationId);
                            }
                            exchange.setProperty("alreadyProcessed", alreadyProcessed);
                        })
                        // Use two separate filters instead of choice/otherwise
                        .filter(exchange -> !exchange.getProperty("alreadyProcessed", Boolean.class))
                            .process(commandProcessingService::markAsProcessing)
                            .bean(commandProcessingService, "process")
                            .threads(1)
                        .end()
                        // Second filter for already processed messages
                        .filter(exchange -> exchange.getProperty("alreadyProcessed", Boolean.class))
                            // Explicitly acknowledge already processed messages by completing the route
                            .log("Acknowledging already processed message with correlationId: ${header." + FINERACT_HEADER_CORRELATION_ID + "}")
                            .process(exchange -> log.info("Route completed for already processed message with correlationId: {}",
                                    exchange.getIn().getHeader(FINERACT_HEADER_CORRELATION_ID, String.class)))
                        .end();

            }

        }

    }

    private String getQueueConsumerConnectionString(String exchangeName, String queueName, String routingKey,
            int totalConcurrentConsumers) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + exchangeName + "?queues=" + queueName + "&routingKey="
                + routingKey + "&concurrentConsumers=" + totalConcurrentConsumers + "&testConnectionOnStartup=true"
                + "&acknowledgeMode=AUTO" + "&asyncConsumer=true" + "&rejectAndDontRequeue=true" + "&autoDeclare=true"
                + "&exchangeType=direct" + "&arg.queue.durable="
                + properties.getEvents().getExternal().getConsumer().getRabbitmq().getDurable();
    }

    private String getTopicConnectionString(String topicExchangeName, String sseRoutingKey) {
        return properties.getEvents().getCamel().getQueueSystem() + ":" + topicExchangeName + "?queues=" + sseRoutingKey + "&routingKey="
                + sseRoutingKey + "&rejectAndDontRequeue=true" + "&testConnectionOnStartup=true" + "&exchangeType=topic"
                + "&acknowledgeMode=AUTO";
    }
}
