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

class DistributionDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void testGettersSettersAndConstructors() {
        DistributionDto distribution = new DistributionDto(
                "https://example.org/datasets/example/part-0001.parquet",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertEquals("https://example.org/datasets/example/part-0001.parquet", distribution.getContentUrl());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", distribution.getSha256());

        DistributionDto empty = new DistributionDto();
        empty.setContentUrl("https://example.org/models/example/weights.safetensors");
        empty.setSha256("9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7");

        assertEquals("https://example.org/models/example/weights.safetensors", empty.getContentUrl());
        assertEquals("9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7", empty.getSha256());
    }

    @Test
    void testEqualsHashCodeToString() {
        DistributionDto d1 = new DistributionDto("https://example.org/f/one", "a".repeat(64));
        DistributionDto d2 = new DistributionDto("https://example.org/f/one", "a".repeat(64));
        DistributionDto d3 = new DistributionDto("https://example.org/f/two", "b".repeat(64));

        assertEquals(d1, d1);
        assertEquals(d1, d2);
        assertNotEquals(d1, d3);
        assertNotEquals(d1, null);
        assertNotEquals(d1, "other");

        assertEquals(d1.hashCode(), d2.hashCode());
        assertTrue(d1.toString().contains("https://example.org/f/one"));
    }

    @Test
    void testSerializationAndValidation() throws Exception {
        DistributionDto distribution = new DistributionDto(
                "https://example.org/datasets/example/part-0001.parquet",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        String json = mapper.writeValueAsString(distribution);
        DistributionDto deserialized = mapper.readValue(json, DistributionDto.class);

        assertEquals(distribution, deserialized);
        Set<ConstraintViolation<DistributionDto>> violations = validator.validate(deserialized);
        assertTrue(violations.isEmpty());

        DistributionDto invalid = new DistributionDto("", null);
        Set<ConstraintViolation<DistributionDto>> invalidViolations = validator.validate(invalid);
        assertFalse(invalidViolations.isEmpty());
        assertEquals(2, invalidViolations.size());
    }
}
