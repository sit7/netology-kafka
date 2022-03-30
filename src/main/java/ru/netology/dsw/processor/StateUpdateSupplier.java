package ru.netology.dsw.processor;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueStore;

public class StateUpdateSupplier implements ProcessorSupplier<String, GenericRecord> {
    private final StateUpdater stateUpdater;

    public StateUpdateSupplier(String storeName) {
        this.stateUpdater = new StateUpdater(storeName);
    }

    @Override
    public Processor<String, GenericRecord> get() {
        return stateUpdater;
    }

    private static class StateUpdater implements Processor<String, GenericRecord> {
        private final String storeName;
        private KeyValueStore<String, GenericRecord> stateStore;

        public StateUpdater(String storeName) {
            this.storeName = storeName;
        }

        @Override
        public void init(ProcessorContext context) {
            this.stateStore = (KeyValueStore<String, GenericRecord>) context.getStateStore(storeName);
        }

        @Override
        public void process(String key, GenericRecord value) {
            stateStore.put(key, value);
        }

        @Override
        public void close() {
        }
    }
}
