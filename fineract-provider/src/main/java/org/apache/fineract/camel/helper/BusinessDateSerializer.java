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

package org.apache.fineract.camel.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.springframework.stereotype.Component;

@Component
public class BusinessDateSerializer {

    private final ObjectMapper objectMapper;

    public BusinessDateSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void marshal(HashMap<BusinessDateType, LocalDate> businessDates, OutputStream stream) throws Exception {

        Map<String, String> serializable = new HashMap<>();
        for (Map.Entry<BusinessDateType, LocalDate> entry : businessDates.entrySet()) {
            serializable.put(entry.getKey().name(), entry.getValue().toString());
        }

        String json = objectMapper.writeValueAsString(serializable);
        stream.write(json.getBytes(StandardCharsets.UTF_8));
    }

    public Object unmarshal(InputStream stream) throws Exception {
        String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> deserialized = objectMapper.readValue(json, Map.class);

        HashMap<BusinessDateType, LocalDate> result = new HashMap<>();
        for (Map.Entry<String, String> entry : deserialized.entrySet()) {
            result.put(BusinessDateType.valueOf(entry.getKey()), LocalDate.parse(entry.getValue()));
        }

        return result;
    }
}
