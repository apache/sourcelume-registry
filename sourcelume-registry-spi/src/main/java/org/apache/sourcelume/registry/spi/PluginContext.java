/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sourcelume.registry.spi;

import java.util.Map;

/** Configuration for one selected plugin. Values may contain credentials and must not be logged. */
public record PluginContext(Map<String, String> properties) {
    public PluginContext {
        properties = Map.copyOf(properties);
    }

    @Override
    public String toString() {
        return "PluginContext[redacted]";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Map<String, String> properties = Map.of();

        private Builder() {}

        public Builder properties(Map<String, String> value) {
            this.properties = value;
            return this;
        }

        public PluginContext build() {
            return new PluginContext(properties);
        }
    }
}
