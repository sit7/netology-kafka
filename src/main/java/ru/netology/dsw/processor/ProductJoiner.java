package ru.netology.dsw.processor;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

import static ru.netology.dsw.processor.ProductJoinerApp.STATE_STORE_NAME;

public class ProductJoiner implements ValueTransformerWithKey<String, GenericRecord, ProductJoinerApp.JoinResult> {
    private KeyValueStore<String, GenericRecord> productStore;
    private ProcessorContext context;

    @Override
    public void init(ProcessorContext context) {
        // Достаем из контекста, который нам передает кафка-стримс, наш стор.
        // Достаем мы его по имени, с которым мы его регистрировали
        this.productStore = (KeyValueStore<String, GenericRecord>) context.getStateStore(STATE_STORE_NAME);
        this.context = context;
    }

    @Override
    public ProductJoinerApp.JoinResult transform(String key, GenericRecord purchase) {
        try {
            if (key == null) {
                throw new IllegalArgumentException("Key for message can't be null!");
            }
            GenericRecord product = productStore.get(purchase.get("productid").toString());
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
            result.put("purchase_id", purchase.get("id"));
            result.put("purchase_quantity", purchase.get("quantity"));
            result.put("product_id", purchase.get("productid"));
            // копируем в наше сообщение нужные поля из сообщения о товаре
            result.put("product_name", product.get("name"));
            result.put("product_price", product.get("price"));
            return new ProductJoinerApp.JoinResult(true, result);
        } catch (Exception e) {
            // добавляем хеддер с ошибкой к нашему сообщению
            // доступно только в Processor API
            context.headers().add("ERROR", e.getMessage().getBytes());
            return new ProductJoinerApp.JoinResult(false, purchase);
        }
    }

    @Override
    public void close() {

    }
}
