package com.tkisor.memorysweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MemorySweep26 implements DedicatedServerModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int TPS = 20;
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("memorysweep.json");

    private static final ExecutorService GC_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MemorySweep-GC");
                t.setDaemon(true);
                return t;
            });

    private static final AtomicBoolean GC_RUNNING = new AtomicBoolean(false);
    private final Deque<Long> samples = new ArrayDeque<>();

    private Config config;
    private long ticks;
    private long lastSweepMillis;
    private long lastFreed = -1;

    @Override
    public void onInitializeServer() {
        config = Config.load();

        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("memorysweep")
                .then(Commands.literal("now")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(c -> {
                        sweep("manual");
                        c.getSource().sendSuccess(
                                () -> Component.literal("MemorySweep GC requested."), false);
                        return 1;
                    }))
                .then(Commands.literal("status")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(c -> {
                        c.getSource().sendSuccess(
                                () -> Component.literal(status()), false);
                        return 1;
                    }))
                .then(Commands.literal("threshold")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .then(Commands.argument("percent", IntegerArgumentType.integer(1, 99))
                        .executes(c -> {
                            config.thresholdPercent =
                                    IntegerArgumentType.getInteger(c, "percent");
                            config.save();
                            c.getSource().sendSuccess(
                                    () -> Component.literal(
                                            "Threshold set to " + config.thresholdPercent + "%"),
                                    true);
                            return 1;
                        })))
            )
        );

        System.out.println("[MemorySweep] Server initialized | threshold="
                + config.thresholdPercent + "% | interval="
                + config.intervalSeconds + "s");
    }

    private void tick(MinecraftServer server) {
        if (!config.enabled || ++ticks % TPS != 0) return;

        samples.addLast(usedMemory());
        while (samples.size() > config.averageSeconds) {
            samples.removeFirst();
        }

        if (GC_RUNNING.get()) return;

        long now = System.currentTimeMillis();
        long since = lastSweepMillis == 0
                ? Long.MAX_VALUE
                : (now - lastSweepMillis) / 1000L;

        if (since < config.cooldownSeconds) return;

        double avg = averagePercent();

        if (avg >= config.thresholdPercent) {
            sweep("threshold " + String.format("%.1f", avg) + "%");
        } else if (since >= config.intervalSeconds) {
            sweep("interval");
        }
    }

    private void sweep(String reason) {
        if (!GC_RUNNING.compareAndSet(false, true)) return;

        final long before = usedMemory();
        final long max = Runtime.getRuntime().maxMemory();
        lastSweepMillis = System.currentTimeMillis();

        System.out.println("[MemorySweep] GC requested | reason=" + reason
                + " | before=" + fmt(before) + " / " + fmt(max));

        GC_EXECUTOR.submit(() -> {
            try {
                System.gc();

                if (config.doubleGc) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.gc();
                }

                long after = usedMemory();
                lastFreed = Math.max(0, before - after);

                System.out.println("[MemorySweep] GC complete | before="
                        + fmt(before) + " | after=" + fmt(after)
                        + " | freed=" + fmt(lastFreed));
            } finally {
                GC_RUNNING.set(false);
            }
        });
    }

    private long usedMemory() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private double currentPercent() {
        long max = Runtime.getRuntime().maxMemory();
        return max <= 0 ? 0 : usedMemory() * 100.0 / max;
    }

    private double averagePercent() {
        if (samples.isEmpty()) return currentPercent();

        long sum = 0;
        for (long value : samples) sum += value;

        return (sum / (double) samples.size())
                * 100.0 / Runtime.getRuntime().maxMemory();
    }

    private String status() {
        Runtime r = Runtime.getRuntime();

        return "MemorySweep | heap=" + fmt(usedMemory())
                + " / " + fmt(r.maxMemory())
                + " (" + String.format("%.1f", currentPercent()) + "%)"
                + " | threshold=" + config.thresholdPercent + "%"
                + " | interval=" + config.intervalSeconds + "s"
                + " | average=" + config.averageSeconds + "s"
                + " | cooldown=" + config.cooldownSeconds + "s"
                + " | GC running=" + GC_RUNNING.get()
                + " | last freed="
                + (lastFreed < 0 ? "N/A" : fmt(lastFreed));
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double value = bytes / 1024.0;
        if (value < 1024) return String.format("%.1f KiB", value);

        value /= 1024.0;
        if (value < 1024) return String.format("%.1f MiB", value);

        return String.format("%.2f GiB", value / 1024.0);
    }

    private static final class Config {
        boolean enabled = true;
        int intervalSeconds = 900;
        int thresholdPercent = 75;
        int averageSeconds = 10;
        int cooldownSeconds = 60;
        boolean doubleGc = false;
        boolean logEveryCheck = false;

        static Config load() {
            try {
                if (Files.exists(CONFIG_PATH)) {
                    try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                        Config c = GSON.fromJson(reader, Config.class);
                        if (c != null) {
                            c.validate();
                            return c;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[MemorySweep] Config load failed: " + e.getMessage());
            }

            Config c = new Config();
            c.save();
            return c;
        }

        void validate() {
            intervalSeconds = Math.max(10, intervalSeconds);
            thresholdPercent = Math.max(1, Math.min(99, thresholdPercent));
            averageSeconds = Math.max(1, Math.min(600, averageSeconds));
            cooldownSeconds = Math.max(10, cooldownSeconds);
        }

        void save() {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                    GSON.toJson(this, writer);
                }
            } catch (Exception e) {
                System.err.println("[MemorySweep] Config save failed: " + e.getMessage());
            }
        }
    }
}
