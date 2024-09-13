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

import jakarta.jms.ConnectionFactory;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.messagehandler.jms.JmsBrokerConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.connection.CachingConnectionFactory;

@Configuration
@ConditionalOnProperty(value = "fineract.events.camel.jms.enabled", havingValue = "true")
@Import(value = { JmsBrokerConfiguration.class })
public class CamelEventJMSConfiguration {

    @Autowired
    private FineractProperties fineractProperties;

    @Bean(name = "camelJmsComponent")
    JmsComponent activeMQComponent(ConnectionFactory connectionFactory) {

        FineractProperties.FineractCamelEventsProducerJmsProperties jmsProps = fineractProperties.getEvents().getCamel().getProducer()
                .getJms();
        CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory();
        cachingConnectionFactory.setSessionCacheSize(jmsProps.getProducerCount());
        cachingConnectionFactory.setReconnectOnException(true);
        cachingConnectionFactory.setTargetConnectionFactory(connectionFactory);

        return JmsComponent.jmsComponentAutoAcknowledge(cachingConnectionFactory);
    }

}
