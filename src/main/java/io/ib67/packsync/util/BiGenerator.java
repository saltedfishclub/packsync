package io.ib67.packsync.util;

public interface BiGenerator<A, B> {
    interface AnyBiConsumer<A, B> {
        void accept(A a, B b) throws Exception;
    }

    void accept(AnyBiConsumer<A, B> consumer) throws Exception;
}
