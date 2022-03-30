package ru.netology.dsw.dsl;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Приложение на Kafka Streams, которое юудет писать в топик сообщение,
 * каждый раз, когда покупок продукта за минуту было больше, чем MAX_PURCHASES_PER_MINUTE
 */
public class SumAlertsApp {
    public static final String PRODUCT_TOPIC_NAME = "products";
    public static final String PURCHASE_TOPIC_NAME = "purchases";
    public static final String RESULT_TOPIC_SUM = "product_sum_alerts-dsl";
    public static final String RESULT_TOPIC_JOIN = "purchase_with_joined_product-dsl";
    private static final long MAX_SUM_PER_MINUTE = 2000L;

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

        kafkaStreams.setUncaughtExceptionHandler((thread, ex) -> {
            ex.printStackTrace();
            kafkaStreams.close();
            latch.countDown();
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
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "SumAlertsAppDSL");
        // адреса брокеров нашей кафки (у нас он 1)
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        // если вы захотите обработать записи заново, не забудьте удалить папку со стейтами
        // а лучше воспользуйтесь методом kafkaStreams.cleanUp()
        props.put(StreamsConfig.STATE_DIR_CONFIG, "states");
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

        // Table - хранит в себе последнюю запись из топика по ключу
        var productsTable = builder.globalTable(
                PRODUCT_TOPIC_NAME, // указываем топик, который мы хотим выкачать в таблицу
                Consumed.with(new Serdes.StringSerde(), avroSerde) // указываем тип ключа и тип значения в топике
        );
        var purchaseWithJoinedProduct = purchasesStream.leftJoin(
                productsTable, // указываем, какую табличку приджоинить
                (key, val) -> val.get("productid").toString(), // указываем, как получить ключ, по которому джоиним
                SumAlertsApp::joinProduct // указываем, как джоинить
        );

        purchaseWithJoinedProduct
                // фильтруем только успешные записи
                .filter((key, val) -> val.success)
                // используем именно метод mapValues, потому что он не может вызвать репартиционирования (см 2-ю лекцию)
                .mapValues(val -> val.result)
                // записываем успешные сообщения в результирующий топик
                .to(RESULT_TOPIC_JOIN, Produced.with(new Serdes.StringSerde(), avroSerde));

        Duration oneMinute = Duration.ofMinutes(1);

        var purchasesWithJoinedProductStream = builder.stream(
                RESULT_TOPIC_JOIN, // указываем имя топика
                Consumed.with(new Serdes.StringSerde(), avroSerde) // указываем тип ключа и тип значения в топике
        );

        purchasesWithJoinedProductStream.groupBy((key, val) -> val.get("product_id").toString(), Grouped.with(new Serdes.StringSerde(), avroSerde))
                .windowedBy(
                        // объединяем записи в рамках минуты
                        TimeWindows.of(oneMinute)
                                // сдвигаем окно всегда на минуту
                                .advanceBy(oneMinute))
                .aggregate(
                        () -> 0L,
                        (key, val, agg) -> agg += (Long) val.get("purchase_quantity")*((Double)val.get("product_price")).longValue(),
                        Materialized.with(new Serdes.StringSerde(), new Serdes.LongSerde())
                )
                .filter((key, val) -> val > MAX_SUM_PER_MINUTE)
                .toStream()
                .map((key, val) -> {
                    // создаем схему нашего алерта
                    Schema schema = SchemaBuilder.record("SumAlert").fields()
                            .name("window_start")
                            // AVRO допускает использование "логических" типов
                            // в данном случае мы показываем, что в данном поле лежит таймстемп
                            // в миллисекундах epoch
                            .type(LogicalTypes.timestampMillis().addToSchema(Schema.create(Schema.Type.LONG)))
                            .noDefault()
                            .requiredLong("sum_of_purchases")
                            .endRecord();
                    GenericRecord record = new GenericData.Record(schema);
                    // старт окна у нас в миллисекундах
                    record.put("window_start", key.window().start());
                    record.put("sum_of_purchases", val);
                    return KeyValue.pair(key.key(), record);
                })
                .to(RESULT_TOPIC_SUM, Produced.with(new Serdes.StringSerde(), avroSerde));

        return builder.build();
    }
    private static SumAlertsApp.JoinResult joinProduct(GenericRecord purchase, GenericRecord product) {
        try {
            // описываем схему нашего сообщения
            Schema schema = SchemaBuilder.record("PurchaseWithProduct").fields()
                    .requiredLong("purchase_id")
                    .requiredLong("purchase_quantity")
                    .requiredLong("product_id")
                    .requiredString("product_name")
                    .requiredDouble("product_price")
                    .endRecord();
            GenericRecord result = new GenericData.Record(schema);
            // копируем в наше сообщение нужные поля из сообщения о покупке
            //result.put("purchase_id", purchase.get("id").toString());
            result.put("purchase_id", purchase.get("id"));
            result.put("purchase_quantity", purchase.get("quantity"));
            result.put("product_id", purchase.get("productid"));
            // копируем в наше сообщение нужные поля из сообщения о товаре
            result.put("product_name", product.get("name"));
            result.put("product_price", product.get("price"));
            return new SumAlertsApp.JoinResult(true, result, null);
        } catch (Exception e) {
            return new SumAlertsApp.JoinResult(false, purchase, e.getMessage());
        }
    }

    private static record JoinResult(boolean success, GenericRecord result, String errorMessage){}
}
