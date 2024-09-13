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

import lombok.RequiredArgsConstructor;
import org.apache.camel.CamelContext;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.spring.SpringCamelContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@RequiredArgsConstructor
@ConditionalOnProperty(value = "fineract.events.camel.jms.enabled", havingValue = "true")
public class CamelConfig {

    @Autowired
    @Qualifier("camelJmsComponent")
    private final JmsComponent jmsComponent;

    @Autowired
    private final CamelRouteBuilder camelRouteBuilder;

    @Bean
    public CamelContext camelContext(ApplicationContext applicationContext) throws Exception {
        SpringCamelContext camelContext = new SpringCamelContext(applicationContext);
        camelContext.addComponent("activemq", jmsComponent);

        // Add routes
        camelContext.addRoutes(camelRouteBuilder);

        return camelContext;
    }

}
