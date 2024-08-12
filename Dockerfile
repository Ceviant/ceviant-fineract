# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
#

FROM azul/zulu-openjdk:17 AS builder

RUN apt-get update -qq && apt-get install -y wget

COPY . /fineract
WORKDIR /fineract

RUN ./gradlew --no-daemon -q -x rat -x compileTestJava -x test -x spotlessJavaCheck -x spotlessJava bootJar
RUN mv /fineract/fineract-provider/build/libs/*.jar /fineract/fineract-provider/build/libs/fineract-provider.jar

# Move pentaho files
# RUN mkdir -p /.mifosx/pentahoReports

# RUN echo "Before copy : " && ls -al /fineract/fineract-provider || true

# COPY fineract-provider/pentahoReports /.mifosx/pentahoReports

# RUN echo "After COPY:" && ls -al /.mifosx/ || true

# =========================================

WORKDIR /app/libs

FROM azul/zulu-openjdk:17 as fineract
COPY --from=builder /fineract/fineract-provider/build/libs/ /app
# COPY --from=builder /.mifosx/pentahoReports /.mifosx/pentahoReports

WORKDIR /app

ENTRYPOINT ["java", "-XX:-OmitStackTraceInFastThrow", "-Dloader.path=/app/libs/", "-jar", "/app/fineract-provider.jar", "--spring.profiles.active=basicauth"]
