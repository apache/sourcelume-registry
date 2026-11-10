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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Exact UTF-8 submission bytes. Neither decoding nor storage may normalize JSON. */
public final class RawDocument {
    private final byte[] bytes;
    private final String text;
    private final String sha256;

    private RawDocument(byte[] input) {
        bytes = Objects.requireNonNull(input, "bytes").clone();
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Document must be UTF-8", e);
        }
        try {
            sha256 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String text() {
        return text;
    }

    public String sha256() {
        return sha256;
    }

    public int size() {
        return bytes.length;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private byte[] bytes;

        private Builder() {}

        public Builder bytes(byte[] value) {
            bytes = Objects.requireNonNull(value, "bytes").clone();
            return this;
        }

        public RawDocument build() {
            return new RawDocument(bytes);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RawDocument that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "RawDocument[sha256=" + sha256 + ", size=" + bytes.length + "]";
    }
}
