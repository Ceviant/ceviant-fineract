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

package org.apache.fineract.infrastructure.springbatch.messagehandler.jms;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
@ConditionalOnProperty(value = "fineract.events.camel.enabled", havingValue = "true")
public class RabbitMQJmsBrokerConfiguration {

    private final FineractProperties fineractProperties;

    @Bean
    public ConnectionFactory connectionFactory() throws NoSuchAlgorithmException, KeyManagementException {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        FineractProperties.FineractExternalEventsConsumerRabbitMQProperties config = fineractProperties.getEvents().getExternal()
                .getConsumer().getRabbitmq();
        connectionFactory.setAddresses(config.getBrokerHost());
        connectionFactory.setPort(config.getBrokerPort());
        connectionFactory.setRequestedHeartBeat(30);

        if (config.isPasswordProtected()) {
            connectionFactory.setUsername(config.getUsername());
            connectionFactory.setPassword(config.getPassword());
        }

        if (config.isBrokerSslEnabled()) {
            connectionFactory.getRabbitConnectionFactory().useSslProtocol();
        }

        connectionFactory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);
        connectionFactory.getRabbitConnectionFactory().setNetworkRecoveryInterval(5000);
        connectionFactory.getRabbitConnectionFactory().setRequestedHeartbeat(30);

        return connectionFactory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

}
