/*
 * MIT License
 *
 * Copyright (c) 2022 bakdata
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.bakdata.kafka;

import static com.bakdata.kafka.AbstractLargeMessageConfig.PREFIX;
import static com.bakdata.kafka.FlagHelper.asFlag;
import static com.bakdata.kafka.FlagHelper.isBacked;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

final class HeaderLargeMessagePayloadProtocol implements LargeMessagePayloadProtocol {
    private static final String HEADER_PREFIX = "__" + PREFIX + "backed.";
    private static final String KEY_HEADER_SUFFIX = "key";
    private static final String VALUE_HEADER_SUFFIX = "value";
    private final boolean isKey;

    @Override
    public byte[] serialize(final LargeMessagePayload payload, final Headers headers) {
        final byte flag = asFlag(payload.isBacked());
        this.addHeader(headers, flag);
        return payload.getData();
    }

    static String getHeaderName(final boolean isKey) {
        return HEADER_PREFIX + (isKey ? KEY_HEADER_SUFFIX : VALUE_HEADER_SUFFIX);
    }

    @Override
    public LargeMessagePayload deserialize(final byte[] bytes, final Headers headers) {
        final Header header = headers.lastHeader(this.getHeaderName());
        final byte flag = header.value()[0];
        return new LargeMessagePayload(isBacked(flag), bytes);
    }

    boolean usesHeaders(final Headers headers) {
        return headers.lastHeader(this.getHeaderName()) != null;
    }

    private void addHeader(final Headers headers, final byte flag) {
        final String header = this.getHeaderName();
        headers.remove(header);
        headers.add(header, new byte[]{flag});
    }

    private String getHeaderName() {
        return getHeaderName(this.isKey);
    }
}
