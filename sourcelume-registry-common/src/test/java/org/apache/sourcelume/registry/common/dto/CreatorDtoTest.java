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
package org.apache.sourcelume.registry.common.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreatorDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void testGettersSettersAndConstructors() {
        CreatorDto creator = new CreatorDto("schema:Person", "Jane Doe", "curator");
        assertEquals("schema:Person", creator.getType());
        assertEquals("Jane Doe", creator.getName());
        assertEquals("curator", creator.getRole());

        CreatorDto empty = new CreatorDto();
        empty.setType("schema:Organization");
        empty.setName("Apache");
        empty.setRole("originator");

        assertEquals("schema:Organization", empty.getType());
        assertEquals("Apache", empty.getName());
        assertEquals("originator", empty.getRole());
    }

    @Test
    void testEqualsHashCodeToString() {
        CreatorDto c1 = new CreatorDto("schema:Person", "Jane Doe", "curator");
        CreatorDto c2 = new CreatorDto("schema:Person", "Jane Doe", "curator");
        CreatorDto c3 = new CreatorDto("schema:Organization", "Apache", "originator");

        assertEquals(c1, c1);
        assertEquals(c1, c2);
        assertNotEquals(c1, c3);
        assertNotEquals(c1, null);
        assertNotEquals(c1, "other");

        assertEquals(c1.hashCode(), c2.hashCode());
        assertTrue(c1.toString().contains("Jane Doe"));
    }

    @Test
    void testSerializationAndValidation() throws Exception {
        CreatorDto creator = new CreatorDto("schema:Person", "Jane Doe", "curator");
        String json = mapper.writeValueAsString(creator);
        CreatorDto deserialized = mapper.readValue(json, CreatorDto.class);

        assertEquals(creator, deserialized);
        Set<ConstraintViolation<CreatorDto>> violations = validator.validate(deserialized);
        assertTrue(violations.isEmpty());

        CreatorDto invalid = new CreatorDto("", "", null);
        Set<ConstraintViolation<CreatorDto>> invalidViolations = validator.validate(invalid);
        assertFalse(invalidViolations.isEmpty());
        assertEquals(2, invalidViolations.size());
    }

    @Test
    void testIdIsOptionalAndRoundTrips() throws Exception {
        CreatorDto creator = new CreatorDto("schema:Organization", "Apache Sourcelume", "originator");
        creator.setId("did:web:example.org");
        assertEquals("did:web:example.org", creator.getId());

        String json = mapper.writeValueAsString(creator);
        assertTrue(json.contains("did:web:example.org"));
        CreatorDto deserialized = mapper.readValue(json, CreatorDto.class);
        assertEquals(creator, deserialized);
        assertEquals("did:web:example.org", deserialized.getId());

        CreatorDto withoutId = new CreatorDto("schema:Organization", "Apache Sourcelume", "originator");
        assertFalse(mapper.writeValueAsString(withoutId).contains("did:web"));
        assertNotEquals(creator, withoutId);
        assertTrue(validator.validate(withoutId).isEmpty());
    }
}
