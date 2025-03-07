
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

package org.apache.fineract.infrastructure.core.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializer for {@link CommandWrapper} objects to/from JSON.
 */
@Component
public class CommandWrapperJsonSerializer {

    private final Gson gson;

    @Autowired
    public CommandWrapperJsonSerializer() {
        final GsonBuilder builder = new GsonBuilder();
        GoogleGsonSerializerHelper.registerTypeAdapters(builder);
        builder.serializeNulls();
        this.gson = builder.create();
    }

    /**
     * Serializes a CommandWrapper object to JSON.
     *
     * @param command
     *            the CommandWrapper object to serialize
     * @return the JSON string representation
     */
    public String serialize(final CommandWrapper command) {
        return this.gson.toJson(command);
    }

    /**
     * Deserializes a JSON string into a CommandWrapper object.
     *
     * @param json
     *            the JSON string to deserialize
     * @return the CommandWrapper object
     */
    public CommandWrapper deserialize(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return this.gson.fromJson(json, CommandWrapper.class);
    }
}
