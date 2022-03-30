package ru.netology.dsw.processor;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static ru.netology.dsw.processor.QuantityAlertsApp.STATE_STORE_NAME;

public class PurchaseQuantityAlertTrasformer implements Processor<String, GenericRecord, String, GenericRecord> {
    public static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private KeyValueStore<byte[], Long> stateStore;
    private ProcessorContext<String, GenericRecord> context;
    private long lastProcessedWindowEnd = 0;

    @Override
    public void init(ProcessorContext<String, GenericRecord> context) {
        stateStore = context.getStateStore(STATE_STORE_NAME);
        this.context = context;
        context.schedule(ONE_MINUTE, PunctuationType.WALL_CLOCK_TIME, this::sendAlerts);
    }

    @Override
    public void process(Record<String, GenericRecord> record) {
        long timestamp = record.timestamp();
        // округляем наш таймстемп вниз до ближайшей минуты - получаем начало окна
        long nearestMinutesTs = timestamp - timestamp % 60_000;
        String productId = record.value().get("productid").toString();
        Long quantity = (Long) record.value().get("quantity");
        // создаем ключ в сторе конкатенации начала окна и id продукта
        byte[] stateStoreKey = createKey(nearestMinutesTs, productId);
        Long oldVal = stateStore.get(stateStoreKey);
        long newVal = oldVal != null ? oldVal + quantity : quantity;
        stateStore.put(stateStoreKey, newVal);
    }

    @Override
    public void close() {
    }

    private void sendAlerts(long timestamp) {
        long nearestMinutesTs = timestamp - timestamp % 60_000;
        // в RocksDB, которая является стором для Kafka Streams, записи лежат в отсортированном виде
        // тогда при запросе записей range запросом, мы получим ответ быстро
        // если интересно побольше прочитать, как устроены key-value хранилища и конкретно RocksDB
        // смотри https://www.confluent.io/blog/how-to-tune-rocksdb-kafka-streams-state-stores-performance/
        // а также Martin Kleppmann - Designing Data Intensive Applications, глава 3, SSTables
        stateStore.range(createKey(lastProcessedWindowEnd, ""), createKey(nearestMinutesTs, ""))
                        .forEachRemaining(keyVal -> {
                            long ts = extractTsFromKey(keyVal.key);
                            String productId = extractProductIdFromKey(keyVal.key);
                            Long count = keyVal.value;
                            if (count > QuantityAlertsApp.MAX_PURCHASES_PER_MINUTE) {
                                // создаем схему нашего алерта
                                Schema schema = SchemaBuilder.record("QuantityAlert").fields()
                                        .name("window_start")
                                        // AVRO допускает использование "логических" типов
                                        // в данном случае мы показываем, что в данном поле лежит таймстемп
                                        // в миллисекундах epoch
                                        .type(LogicalTypes.timestampMillis().addToSchema(Schema.create(Schema.Type.LONG)))
                                        .noDefault()
                                        .requiredLong("number_of_purchases")
                                        .endRecord();
                                GenericRecord record = new GenericData.Record(schema);
                                // старт окна у нас в миллисекундах
                                record.put("window_start", ts);
                                record.put("number_of_purchases", count);
                                context.forward(new Record<>(productId, record, ts));
                            }
                            // удаляем ключ из стора, так как по этому окну уже обработаны записи для этого ключа
                            // чтобы исключить повторную обработку
                            stateStore.delete(keyVal.key);
                        });
        lastProcessedWindowEnd = nearestMinutesTs;
    }

    private String extractProductIdFromKey(byte[] key) {
        int productIdLength = key.length - Long.BYTES;
        byte[] productIdBytes = new byte[productIdLength];
        System.arraycopy(key, Long.BYTES, productIdBytes, 0, productIdLength);
        return new String(productIdBytes);
    }

    private long extractTsFromKey(byte[] key) {
        return ByteBuffer.wrap(key, 0, Long.BYTES).getLong();
    }

    private byte[] createKey(long nearestMinutesTs, String productId) {
        // создаем ключ конкатенацией начала окна и productId
        byte[] key = new byte[Long.BYTES + productId.length()];
        System.arraycopy(
                ByteBuffer.allocate(Long.BYTES).putLong(nearestMinutesTs).array(),
                0,
                key,
                0,
                Long.BYTES
        );
        System.arraycopy(
                productId.getBytes(StandardCharsets.UTF_8),
                0,
                key,
                Long.BYTES,
                productId.getBytes(StandardCharsets.UTF_8).length
        );
        return key;
    }
}
