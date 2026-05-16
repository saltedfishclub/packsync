package io.ib67.packsync.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public class CompletableFutureRoller<T ,R> implements Function<Throwable, CompletionStage<R>>{
    private final Deque<T> listOfFutures;
    private final Function<T, CompletableFuture<R>> mapper;

    public CompletableFutureRoller(List<T> listOfFutures, Function<T, CompletableFuture<R>> mapper) {
        this.listOfFutures = new ArrayDeque<>(listOfFutures);
        this.mapper = mapper;
    }

    @Override
    public CompletionStage<R> apply(Throwable throwable) {
        System.out.println("[PackSync] Task failed: " + throwable.getMessage());
        if(listOfFutures.isEmpty()) return CompletableFuture.failedFuture(throwable);
        var target = listOfFutures.pop();
        System.out.println("[PackSync] Retrying with " + target);
        return mapper.apply(target).exceptionallyCompose(this);
    }
}
