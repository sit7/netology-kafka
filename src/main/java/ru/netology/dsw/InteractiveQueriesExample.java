package ru.netology.dsw;

import com.sun.net.httpserver.HttpServer;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.Stores;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class InteractiveQueriesExample {

    public static final String PRODUCTS_TOPIC = "products";
    public static final String STATE_STORE_NAME = "state-store";
    private static ReadOnlyKeyValueStore<String, GenericRecord> store;

    public static void main(String[] args) throws Exception {
        // создаем клиент для общения со schema-registry
        var client = new CachedSchemaRegistryClient("http://localhost:8090", 16);
        var serDeProps = Map.of(
                // указываем сериализатору, что может самостояетльно регистрировать схемы
                KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "true",
                KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8090"
        );

        KafkaStreams kafkaStreams = new KafkaStreams(buildTopology(client, serDeProps), getStreamsConfig());

        // создаем очень простой http сервер, который будет отвечать на запросы вида http://127.0.0.1:8080/product_name/1
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/product_name", (exchange -> {
            // при запросе достаем по ключу из стора запись
            GenericRecord genericRecord = store.get(getProductKeyFromUri(exchange.getRequestURI()));
            String name = genericRecord == null ? "null" : genericRecord.get("name").toString();
            // выставляем код ответа 200 и длину тела сообщения по длине имени нашего продукта
            exchange.sendResponseHeaders(200, name.getBytes().length);
            // записываем тело в ответ
            OutputStream output = exchange.getResponseBody();
            output.write(name.getBytes());
            output.flush();
            exchange.close();
        }));

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread("shutdown-hook") {
            @Override
            public void run() {
                kafkaStreams.close();
                server.stop(0);
                latch.countDown();
            }
        });
        kafkaStreams.setUncaughtExceptionHandler((thread, ex) -> {
            ex.printStackTrace();
            kafkaStreams.close();
            server.stop(0);
            latch.countDown();
        });

        try {
            kafkaStreams.start();
            store = kafkaStreams.store(
                    StoreQueryParameters.fromNameAndType(
                            // для получения стора используем то же имя, что мы использовали при его регистрации
                            STATE_STORE_NAME,
                            QueryableStoreTypes.keyValueStore()
                    ));
            server.start();
            // будет блокировать поток, пока из другого потока не будет вызван метод countDown()
            latch.await();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String getProductKeyFromUri(URI requestURI) {
        String str = requestURI.toString();
        int indexOfKeyStart = str.indexOf("product_name/") + "product_name/".length();
        return str.substring(indexOfKeyStart);
    }

    private static Topology buildTopology(SchemaRegistryClient client, Map<String, String> serDeConfig) {
        var avroSerde = new GenericAvroSerde(client);
        avroSerde.configure(serDeConfig, false);
        var builder = new StreamsBuilder();
        KeyValueBytesStoreSupplier storeSupplier = Stores.persistentKeyValueStore(STATE_STORE_NAME);
        builder.globalTable(
                PRODUCTS_TOPIC,
                Materialized.<String, GenericRecord>as(storeSupplier)
                        .withLoggingDisabled()
                        .withKeySerde(Serdes.String())
                        .withValueSerde(avroSerde)
        );
        return builder.build();
    }

    public static Properties getStreamsConfig() {
        Properties props = new Properties();
        // имя этого приложения для кафки
        // приложения с одинаковым именем объединятся в ConsumerGroup и распределят обработку партиций между собой
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "ProductJoinerDSL");
        // адреса брокеров нашей кафки (у нас он 1)
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return props;
    }
}
