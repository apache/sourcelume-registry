#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Quarkus JVM-mode container image.
# Quarkus builds the runnable jar under target/quarkus-app/; the container just needs
# a JRE and the lib dir. This is the simplest JVM-mode Dockerfile — Quarkus also
# supports building a native image (GraalVM) which would shrink this further, but
# for the spike comparison we keep it JVM-mode to match the Spring spike's footprint.

FROM eclipse-temurin:21-jre

WORKDIR /work

COPY target/quarkus-app /work/quarkus-app

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /work/quarkus-app/quarkus-run.jar"]
