package ru.netology.dsw;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;

public class TestUtils {
    public static Schema createPurchaseSchema() {
        return SchemaBuilder.record("Purchase").fields()
                .requiredLong("id")
                .requiredLong("quantity")
                .requiredLong("productid")
                .endRecord();
    }
}
