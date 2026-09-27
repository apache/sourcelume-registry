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
package org.apache.sourcelume.registry.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * Represents an entry in the custody chain of a Sourcelume ProvenanceRecord.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustodyEventDto {

    @NotBlank(message = "Custody event agent IRI is required")
    private String agent;

    @NotBlank(message = "Custody event action is required")
    private String action;

    @NotBlank(message = "Custody event startTime is required")
    private String startTime;

    public CustodyEventDto() {
    }

    public CustodyEventDto(String agent, String action, String startTime) {
        this.agent = agent;
        this.action = action;
        this.startTime = startTime;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustodyEventDto that = (CustodyEventDto) o;
        return Objects.equals(agent, that.agent) &&
                Objects.equals(action, that.action) &&
                Objects.equals(startTime, that.startTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agent, action, startTime);
    }

    @Override
    public String toString() {
        return "CustodyEventDto{" +
                "agent='" + agent + '\'' +
                ", action='" + action + '\'' +
                ", startTime='" + startTime + '\'' +
                '}';
    }
}
