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
package org.apache.sourcelume.registry.ingest.worker;

import com.sun.jersey.api.client.Client;
import org.apache.atlas.AtlasClientV2;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.MediaType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkerAtlasClientCompatibilityTest {

    @Test
    void testAtlasClientV2JerseyJsonProviderRegistered() throws Exception {
        AtlasClientV2 client = new AtlasClientV2(new String[]{"http://localhost:21000"}, new String[]{"admin", "admin"});
        assertNotNull(client);

        Field contextField = org.apache.atlas.AtlasBaseClient.class.getDeclaredField("atlasClientContext");
        contextField.setAccessible(true);
        Object context = contextField.get(client);
        Method getClientMethod = context.getClass().getDeclaredMethod("getClient");
        getClientMethod.setAccessible(true);
        Client jerseyClient = (Client) getClientMethod.invoke(context);

        Object reader = jerseyClient.getProviders().getMessageBodyReader(
                AtlasTypesDef.class,
                AtlasTypesDef.class,
                new java.lang.annotation.Annotation[0],
                MediaType.APPLICATION_JSON_TYPE
        );
        System.out.println("Worker test jersey reader: " + reader);
        assertNotNull(reader, "Jersey client in AtlasClientV2 must register Jackson JSON MessageBodyReader for AtlasTypesDef");
    }
}
