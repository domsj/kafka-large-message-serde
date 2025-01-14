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


import static com.bakdata.kafka.ByteFlagLargeMessagePayloadProtocol.stripFlag;
import static com.bakdata.kafka.HeaderLargeMessagePayloadProtocol.getHeaderName;
import static com.bakdata.kafka.LargeMessageConverterIntegrationTest.configureS3HTTPService;
import static com.bakdata.kafka.LargeMessagePayload.ofBytes;
import static com.bakdata.kafka.LargeMessagePayload.ofUri;
import static com.bakdata.kafka.LargeMessageRetrievingClient.deserializeUri;
import static org.assertj.core.api.Assertions.assertThat;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.StringConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LargeMessageConverterTest {
    @RegisterExtension
    static final S3MockExtension S3_MOCK = S3MockExtension.builder().silent()
            .withSecureConnection(false).build();
    private static final String TOPIC = "topic";
    private static final Converter STRING_CONVERTER = new StringConverter();
    private static final Serializer<String> STRING_SERIALIZER = new StringSerializer();
    private static final Deserializer<String> STRING_DESERIALIZER = new StringDeserializer();
    private static final LargeMessagePayloadProtocol HEADER_PROTOCOL = new HeaderLargeMessagePayloadProtocol();
    private static final LargeMessagePayloadProtocol BYTE_FLAG_PROTOCOL = new ByteFlagLargeMessagePayloadProtocol();
    private final AmazonS3 s3Client = S3_MOCK.createS3Client();
    private LargeMessageConverter converter = null;

    @BeforeAll
    static void setUp() {
        configureS3HTTPService();
    }

    private static byte[] serialize(final String uri) {
        return BYTE_FLAG_PROTOCOL.serialize(ofUri(uri), new RecordHeaders(), false);
    }

    private static byte[] serialize(final String uri, final Headers headers, final boolean isKey) {
        return HEADER_PROTOCOL.serialize(ofUri(uri), headers, isKey);
    }

    private static byte[] serialize(final byte[] bytes) {
        return BYTE_FLAG_PROTOCOL.serialize(ofBytes(bytes), new RecordHeaders(), false);
    }

    private static byte[] serialize(final byte[] bytes, final Headers headers, final boolean isKey) {
        return HEADER_PROTOCOL.serialize(ofBytes(bytes), headers, isKey);
    }

    private static byte[] createBackedText(final String bucket, final String key) {
        final String uri = "s3://" + bucket + "/" + key;
        return serialize(uri);
    }

    private static byte[] createBackedText(final String bucket, final String key, final Headers headers,
            final boolean isKey) {
        final String uri = "s3://" + bucket + "/" + key;
        return serialize(uri, headers, isKey);
    }

    private static byte[] readBytes(final BlobStorageURI uri) {
        try (final S3Object object = S3_MOCK.createS3Client().getObject(uri.getBucket(), uri.getKey());
                final S3ObjectInputStream objectContent = object.getObjectContent()) {
            return IOUtils.toByteArray(objectContent);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> createProperties(final int maxSize, final String basePath,
            final boolean useHeaders) {
        return ImmutableMap.<String, String>builder()
                .put(AbstractLargeMessageConfig.S3_ENDPOINT_CONFIG, "http://localhost:" + S3_MOCK.getHttpPort())
                .put(AbstractLargeMessageConfig.S3_REGION_CONFIG, "us-east-1")
                .put(AbstractLargeMessageConfig.S3_ACCESS_KEY_CONFIG, "foo")
                .put(AbstractLargeMessageConfig.S3_SECRET_KEY_CONFIG, "bar")
                .put(AbstractLargeMessageConfig.MAX_BYTE_SIZE_CONFIG, Integer.toString(maxSize))
                .put(AbstractLargeMessageConfig.BASE_PATH_CONFIG, basePath)
                .put(LargeMessageConverterConfig.CONVERTER_CLASS_CONFIG, StringConverter.class.getName())
                .put(AbstractLargeMessageConfig.USE_HEADERS_CONFIG, Boolean.toString(useHeaders))
                .build();
    }

    private static SchemaAndValue toConnectData(final String text) {
        return STRING_CONVERTER.toConnectData(null, text.getBytes());
    }

    private static byte[] createNonBackedText(final String text) {
        return serialize(STRING_SERIALIZER.serialize(null, text));
    }

    private static byte[] createNonBackedText(final String text, final Headers headers, final boolean isKey) {
        return serialize(STRING_SERIALIZER.serialize(null, text), headers, isKey);
    }

    private static BlobStorageURI deserializeUriWithFlag(final byte[] data) {
        final byte[] uriBytes = stripFlag(data);
        return deserializeUri(uriBytes);
    }

    private static void expectBackedText(final String basePath, final String expected, final byte[] s3BackedText,
            final String type) {
        final BlobStorageURI uri = deserializeUriWithFlag(s3BackedText);
        expectBackedText(uri, basePath, type, expected);
    }

    private static void expectBackedText(final String basePath, final String expected, final byte[] s3BackedText,
            final String type, final Headers headers, final boolean isKey) {
        final BlobStorageURI uri = deserializeUri(s3BackedText);
        expectBackedText(uri, basePath, type, expected);
        assertHasHeader(headers, isKey);
    }

    private static void expectBackedText(final BlobStorageURI uri, final String basePath, final String type,
            final String expected) {
        assertThat(uri).asString().startsWith(basePath + TOPIC + "/" + type + "/");
        final byte[] bytes = readBytes(uri);
        final String deserialized = STRING_DESERIALIZER.deserialize(null, bytes);
        assertThat(deserialized).isEqualTo(expected);
    }

    private static void expectNonBackedText(final String expected, final byte[] s3BackedText) {
        assertThat(STRING_DESERIALIZER.deserialize(null, stripFlag(s3BackedText)))
                .isInstanceOf(String.class)
                .isEqualTo(expected);
    }

    private static void expectNonBackedText(final String expected, final byte[] s3BackedText, final Headers headers,
            final boolean isKey) {
        assertThat(STRING_DESERIALIZER.deserialize(null, s3BackedText))
                .isInstanceOf(String.class)
                .isEqualTo(expected);
        assertHasHeader(headers, isKey);
    }

    private static void assertHasHeader(final Headers headers, final boolean isKey) {
        assertThat(headers.headers(getHeaderName(isKey))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertNonBackedToConnectData(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);
        final String text = "test";
        final SchemaAndValue expected = toConnectData(text);
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, new RecordHeaders(), createNonBackedText(text));
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertNonBackedToConnectDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);
        final String text = "test";
        final SchemaAndValue expected = toConnectData(text);
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, createNonBackedText(text));
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertNonBackedToConnectDataWithHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);
        final String text = "test";
        final SchemaAndValue expected = toConnectData(text);
        final Headers headers = new RecordHeaders();
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, headers, createNonBackedText(text, headers, isKey));
        assertThat(schemaAndValue).isEqualTo(expected);
        assertHasHeader(headers, isKey);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertNonBackedNullToConnectData(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);
        final SchemaAndValue expected = STRING_CONVERTER.toConnectData(null, null);
        final SchemaAndValue schemaAndValue = this.converter.toConnectData(TOPIC, new RecordHeaders(), null);
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertNonBackedNullToConnectDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);
        final SchemaAndValue expected = STRING_CONVERTER.toConnectData(null, null);
        final SchemaAndValue schemaAndValue = this.converter.toConnectData(TOPIC, null);
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertBackedToConnectData(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);
        final String bucket = "bucket";
        final String key = "key";
        final String text = "test";
        this.s3Client.createBucket("bucket");
        final SchemaAndValue expected = toConnectData(text);
        this.store(bucket, key, text, TOPIC);
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, new RecordHeaders(), createBackedText(bucket, key));
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertBackedToConnectDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);
        final String bucket = "bucket";
        final String key = "key";
        final String text = "test";
        this.s3Client.createBucket("bucket");
        final SchemaAndValue expected = toConnectData(text);
        this.store(bucket, key, text, TOPIC);
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, createBackedText(bucket, key));
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertBackedToConnectDataWithHeaders(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);
        final String bucket = "bucket";
        final String key = "key";
        final String text = "test";
        this.s3Client.createBucket("bucket");
        final SchemaAndValue expected = toConnectData(text);
        this.store(bucket, key, text, TOPIC);
        final Headers headers = new RecordHeaders();
        final SchemaAndValue schemaAndValue =
                this.converter.toConnectData(TOPIC, headers, createBackedText(bucket, key, headers, isKey));
        assertThat(schemaAndValue).isEqualTo(expected);
        assertHasHeader(headers, isKey);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertBackedNullToConnectData(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);
        final SchemaAndValue expected = STRING_CONVERTER.toConnectData(null, null);
        final SchemaAndValue schemaAndValue = this.converter.toConnectData(TOPIC, new RecordHeaders(), null);
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldConvertBackedNullToConnectDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);
        final SchemaAndValue expected = STRING_CONVERTER.toConnectData(null, null);
        final SchemaAndValue schemaAndValue = this.converter.toConnectData(TOPIC, null);
        assertThat(schemaAndValue).isEqualTo(expected);
    }

    @Test
    void shouldCreateBackedDataKey() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(true, 0, basePath, false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final byte[] bytes = this.converter.fromConnectData(TOPIC, new RecordHeaders(), data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "keys");
    }

    @Test
    void shouldCreateBackedDataKeyWithoutHeaders() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(true, 0, basePath, false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final byte[] bytes = this.converter.fromConnectData(TOPIC, data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "keys");
    }

    @Test
    void shouldCreateBackedDataKeyWithHeaders() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(true, 0, basePath, true);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final Headers headers = new RecordHeaders();
        final byte[] bytes = this.converter.fromConnectData(TOPIC, headers, data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "keys", headers, true);
    }

    @Test
    void shouldCreateBackedDataValue() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(false, 0, basePath, false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final byte[] bytes = this.converter.fromConnectData(TOPIC, new RecordHeaders(), data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "values");
    }

    @Test
    void shouldCreateBackedDataValueWithoutHeaders() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(false, 0, basePath, false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final byte[] bytes = this.converter.fromConnectData(TOPIC, data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "values");
    }

    @Test
    void shouldCreateBackedDataValueWithHeaders() {
        final String bucket = "bucket";
        final String basePath = "s3://" + bucket + "/base";
        this.initSetup(false, 0, basePath, true);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        this.s3Client.createBucket(bucket);

        final Headers headers = new RecordHeaders();
        final byte[] bytes = this.converter.fromConnectData(TOPIC, headers, data.schema(), data.value());
        expectBackedText(basePath, text, bytes, "values", headers, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateBackedNullData(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);

        final SchemaAndValue data = STRING_CONVERTER.toConnectData(null, null);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, new RecordHeaders(), data.schema(), data.value());
        assertThat(bytes).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateBackedNullDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 0, "s3://bucket/base", false);

        final SchemaAndValue data = STRING_CONVERTER.toConnectData(null, null);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, data.schema(), data.value());
        assertThat(bytes).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateNonBackedData(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, new RecordHeaders(), data.schema(), data.value());
        expectNonBackedText(text, bytes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateNonBackedDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, data.schema(), data.value());
        expectNonBackedText(text, bytes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateNonBackedDataWithHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", true);

        final String text = "test";
        final SchemaAndValue data = toConnectData(text);
        final Headers headers = new RecordHeaders();
        final byte[] bytes = this.converter.fromConnectData(TOPIC, headers, data.schema(), data.value());
        expectNonBackedText(text, bytes, headers, isKey);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateNonBackedNullData(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);

        final SchemaAndValue data = STRING_CONVERTER.toConnectData(null, null);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, new RecordHeaders(), data.schema(), data.value());
        assertThat(bytes).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCreateNonBackedNullDataWithoutHeaders(final boolean isKey) {
        this.initSetup(isKey, 5000, "s3://bucket/base", false);

        final SchemaAndValue data = STRING_CONVERTER.toConnectData(null, null);
        final byte[] bytes = this.converter.fromConnectData(TOPIC, data.schema(), data.value());
        assertThat(bytes).isNull();
    }

    private void store(final String bucket, final String key, final String s, final String topic) {
        this.s3Client.putObject(bucket, key, new ByteArrayInputStream(STRING_SERIALIZER.serialize(topic, s)),
                new ObjectMetadata());
    }

    private void initSetup(final boolean isKey, final int maxSize, final String basePath, final boolean useHeaders) {
        final Map<String, String> properties = createProperties(maxSize, basePath, useHeaders);
        this.converter = new LargeMessageConverter();
        this.converter.configure(properties, isKey);
    }
}
