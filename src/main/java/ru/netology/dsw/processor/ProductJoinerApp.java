package ru.netology.dsw.processor;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class ProductJoinerApp {
    public static final String PRODUCT_TOPIC_NAME = "products";
    public static final String PURCHASE_TOPIC_NAME = "purchases";
    public static final String RESULT_TOPIC = "purchase_with_joined_product-processor";
    public static final String DLQ_TOPIC = "purchases_product_join_dlq-processor";
    public static final String STATE_STORE_NAME = "state-store";

    public static void main(String[] args) throws InterruptedException {
        // создаем клиент для общения со schema-registry
        var client = new CachedSchemaRegistryClient("http://localhost:8090", 16);
        var serDeProps = Map.of(
                // указываем сериализатору, что может самостояетльно регистрировать схемы
                KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "true",
                KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8090"
        );

        // строим нашу топологию
        Topology topology = buildTopology(client, serDeProps);

        // если скопировать вывод этой команды вот сюда - https://zz85.github.io/kafka-streams-viz/
        // то можно получить красивую визуализацию топологии прямо в браузере
        System.out.println(topology.describe());

        KafkaStreams kafkaStreams = new KafkaStreams(topology, getStreamsConfig());
        // вызов latch.await() будет блокировать текущий поток
        // до тех пор пока latch.countDown() не вызовут 1 раз
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread("shutdown-hook") {
            @Override
            public void run() {
                kafkaStreams.close();
                latch.countDown();
            }
        });

        try {
            kafkaStreams.start();
            // будет блокировать поток, пока из другого потока не будет вызван метод countDown()
            latch.await();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    public static Properties getStreamsConfig() {
        Properties props = new Properties();
        // имя этого приложения для кафки
        // приложения с одинаковым именем объединятся в ConsumerGroup и распределят обработку партиций между собой
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "ProductJoinerProcessorAPI");
        // адреса брокеров нашей кафки (у нас он 1)
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return props;
    }

    public static Topology buildTopology(SchemaRegistryClient client, Map<String, String> serDeConfig) {
        var builder = new StreamsBuilder();
        // Создаем класс для сериализации и десериализации наших сообщений
        var avroSerde = new GenericAvroSerde(client);
        avroSerde.configure(serDeConfig, false);
        // Получаем из кафки поток сообщений из топика покупок (purchase = покупка с англ.)
        var purchasesStream = builder.stream(
                PURCHASE_TOPIC_NAME, // указываем имя топика
                Consumed.with(new Serdes.StringSerde(), avroSerde) // указываем тип ключа и тип значения в топике
        );

        // описываем, как будет работать наш стор
        var stateStoreSupplier =
                Stores.keyValueStoreBuilder(
                        // обязательно указываемя имя стора - оно нам пригодится в нашем трансформере
                        Stores.persistentKeyValueStore(STATE_STORE_NAME),
                        new Serdes.StringSerde(), avroSerde
                )
                        // выключаем ченджлог топик, потому что все необходимые сообщения есть в products топике
                        // доступно только в Processor API
                        .withLoggingDisabled();
        // нам нужно эксплицитно добавить его в нашу топологию
        builder.addGlobalStore(stateStoreSupplier, PRODUCT_TOPIC_NAME,
                Consumed.with(new Serdes.StringSerde(), avroSerde),
                // нам нужно указать, как обработать сообщения прежде чем положить в стор
                // этот dummy класс ничего не будет делать - просто сохранит в стор
                new StateUpdateSupplier(STATE_STORE_NAME));

        var purchaseWithJoinedProduct = purchasesStream
                // передаем инстанс класса, который будет осуществлять джоин
                // чтобы провалиться в класс либо кликните на него с зажатым cmd/ctrl
                // либо поставьте на него курсор и cmd/ctrl + B
                .transformValues(ProductJoiner::new);

        purchaseWithJoinedProduct
                // фильтруем только успешные записи
                .filter((key, val) -> val.success)
                // используем именно метод mapValues, потому что он не может вызвать репартиционирования (см 2-ю лекцию)
                .mapValues(val -> val.result)
                // записываем успешные сообщения в результирующий топик
                .to(RESULT_TOPIC, Produced.with(new Serdes.StringSerde(), avroSerde));

        purchaseWithJoinedProduct
                // фильтруем только неуспешные записи
                .filter((key, val) -> !val.success)
                // используем именно метод mapValues, потому что он не может вызвать репартиционирования (см 2-ю лекцию)
                .mapValues(val -> val.result)
                // записываем сообщение с ошибкой в dlq топик (dead letter queue) - очередь недоставленных сообщений
                .to(DLQ_TOPIC, Produced.with(new Serdes.StringSerde(), avroSerde));

        return builder.build();
    }

    public static record JoinResult(boolean success, GenericRecord result){}
}
