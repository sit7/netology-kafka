package ru.netology.dsw.processor;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.netology.dsw.TestUtils;

import java.io.IOException;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductJoinerAppTest {
    private final MockSchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
    private final StringSerializer stringSerializer = new StringSerializer();
    private final StringDeserializer stringDeserializer = new StringDeserializer();

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, Object> purchasesTopic;
    private TestInputTopic<String, Object> productsTopic;
    private TestOutputTopic<String, Object> resultTopic;
    private TestOutputTopic<String, Object> dlqTopic;

    @BeforeEach
    void setUp() {
        var serDeProps = Map.of(
                KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "true",
                KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8090"
        );
        KafkaAvroSerializer avroSerializer = new KafkaAvroSerializer(schemaRegistry, serDeProps);
        KafkaAvroDeserializer avroDeserializer = new KafkaAvroDeserializer(schemaRegistry, serDeProps);
        testDriver = new TopologyTestDriver(ProductJoinerApp.buildTopology(schemaRegistry, serDeProps), ProductJoinerApp.getStreamsConfig());
        purchasesTopic = testDriver.createInputTopic(ProductJoinerApp.PURCHASE_TOPIC_NAME, stringSerializer, avroSerializer);
        productsTopic = testDriver.createInputTopic(ProductJoinerApp.PRODUCT_TOPIC_NAME, stringSerializer, avroSerializer);
        resultTopic = testDriver.createOutputTopic(ProductJoinerApp.RESULT_TOPIC, stringDeserializer, avroDeserializer);
        dlqTopic = testDriver.createOutputTopic(ProductJoinerApp.DLQ_TOPIC, stringDeserializer, avroDeserializer);
    }

    @AfterEach
    void tearDown() {
        testDriver.close();
    }

    @Test
    public void shouldJoinProductAndPurchase() throws Exception {
        GenericRecord product = createTestProduct();
        GenericRecord purchase = createTestPurchase();
        productsTopic.pipeInput("1", product);
        purchasesTopic.pipeInput("123", purchase);

        assertTrue(dlqTopic.isEmpty());
        var result = resultTopic.readKeyValue();
        assertThat(result.key, is("123"));
        assertThat(((GenericRecord) result.value).get("product_id"), is(product.get("id")));
        assertThat(((GenericRecord) result.value).get("product_name"), is(product.get("name")));
        assertThat(((GenericRecord) result.value).get("product_price"), is(product.get("price")));
    }

    @Test
    public void shouldSendNotFoundProductToDlq() throws Exception {
        GenericRecord purchase = createTestPurchase();
        purchasesTopic.pipeInput("123", purchase);

        assertTrue(resultTopic.isEmpty());
        var result = dlqTopic.readRecord();
        assertThat(result.getKey(), is("123"));
        assertThat(result.getValue(), is(purchase));
        assertThat(
                new String(result.getHeaders().lastHeader("ERROR").value()),
                is("Cannot invoke \"org.apache.avro.generic.GenericRecord.get(String)\" because \"product\" is null")
        );
    }

    @Test
    public void shouldSendPurchaseWithNullKeyToDlq() throws Exception {
        GenericRecord product = createTestProduct();
        GenericRecord purchase = createTestPurchase();
        productsTopic.pipeInput("1", product);
        purchasesTopic.pipeInput(null, purchase);

        assertTrue(resultTopic.isEmpty());
        // Упадет, так как Kafka Streams DSL не позволяет указать, что делать, если ключ null
        var result = dlqTopic.readRecord();
        assertThat(result.getKey(), nullValue());
        assertThat(result.getValue(), is(purchase));
        assertThat(new String(result.getHeaders().lastHeader("ERROR").value()), is("Key for message can't be null!"));
    }

    private GenericRecord createTestProduct() throws IOException, RestClientException {
        Schema schema = SchemaBuilder.record("Product").fields()
                .requiredLong("id")
                .requiredString("name")
                .requiredString("description")
                .requiredDouble("price")
                .endRecord();
        schemaRegistry.register(ProductJoinerApp.PRODUCT_TOPIC_NAME + "-value", schema);
        GenericRecord record = new GenericData.Record(schema);
        record.put("id", 1L);
        record.put("name", new Utf8("TV"));
        record.put("description", new Utf8("TV set"));
        record.put("price", 100.5D);
        return record;
    }

    private GenericRecord createTestPurchase() throws IOException, RestClientException {
        Schema schema = TestUtils.createPurchaseSchema();
        schemaRegistry.register(ProductJoinerApp.PURCHASE_TOPIC_NAME + "-value", schema);
        GenericRecord record = new GenericData.Record(schema);
        record.put("id", 123L);
        record.put("quantity", 1L);
        record.put("productid", 1L);
        return record;
    }
}
