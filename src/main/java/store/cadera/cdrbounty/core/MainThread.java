package store.cadera.cdrbounty.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class MainThread {
    private MainThread() {
    }

    public static <T> CompletableFuture<T> call(JavaPlugin plugin, Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public static CompletableFuture<Void> run(JavaPlugin plugin, Runnable runnable) {
        return call(plugin, () -> {
            runnable.run();
            return null;
        });
    }
}
