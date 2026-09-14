/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sourcelume.registry.atlas.adapter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.atlas.AtlasClientV2;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.DefaultAtlasAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring AutoConfiguration for the Atlas adapter module.
 */
@AutoConfiguration
@EnableConfigurationProperties(SourcelumeAtlasProperties.class)
public class AtlasAdapterConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AtlasClientV2 atlasClient(SourcelumeAtlasProperties properties) {
        String url = properties.getUrl();
        String user = properties.getUser();
        String password = properties.getResolvedPassword();
        return new AtlasClientV2(new String[]{url}, new String[]{user, password});
    }

    @Bean
    @ConditionalOnMissingBean
    public AtlasAdapter atlasAdapter(AtlasClientV2 atlasClient,
                                     SourcelumeAtlasProperties properties,
                                     ObjectMapper objectMapper) {
        return new DefaultAtlasAdapter(atlasClient, properties, objectMapper);
    }
}
