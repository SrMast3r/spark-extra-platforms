/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.minestom;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.SparkPlugin;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;
import me.lucko.spark.common.util.SparkThreadFactory;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.Suggestion;
import net.minestom.server.command.builder.suggestion.SuggestionCallback;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * spark as a library: Minestom no longer has extensions, so the server creates this, calls
 * {@link #enable()} and {@link #disable()}, and decides who has each permission because Minestom
 * has no permission system of its own.
 */
public class MinestomSparkPlugin implements SparkPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("spark");

    private final Path dataDirectory;
    private final BiPredicate<CommandSender, String> permissions;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, new SparkThreadFactory());

    private SparkPlatform platform;
    private MinestomSparkCommand command;

    public MinestomSparkPlugin(Path dataDirectory, BiPredicate<CommandSender, String> permissions) {
        this.dataDirectory = dataDirectory;
        this.permissions = permissions;
    }

    public void enable() {
        this.platform = new SparkPlatform(this);
        this.platform.enable();
        this.command = new MinestomSparkCommand(this.platform, this.permissions);
        MinecraftServer.getCommandManager().register(this.command);
    }

    public void disable() {
        this.platform.disable();
        MinecraftServer.getCommandManager().unregister(this.command);
        this.scheduler.shutdown();
    }

    @Override
    public String getVersion() {
        try (InputStream in = MinestomSparkPlugin.class.getResourceAsStream("/spark-minestom.version")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path getPluginDirectory() {
        return this.dataDirectory;
    }

    @Override
    public String getCommandName() {
        return "spark";
    }

    @Override
    public Stream<MinestomCommandSender> getCommandSenders() {
        return Stream.concat(
                MinecraftServer.getConnectionManager().getOnlinePlayers().stream(),
                Stream.of(MinecraftServer.getCommandManager().getConsoleSender())
        ).map(sender -> new MinestomCommandSender(sender, this.permissions));
    }

    @Override
    public void executeAsync(Runnable task) {
        this.scheduler.execute(task);
    }

    @Override
    public void log(Level level, String msg) {
        if (level.intValue() >= 1000) { // severe
            LOGGER.error(msg);
        } else if (level.intValue() >= 900) { // warning
            LOGGER.warn(msg);
        } else {
            LOGGER.info(msg);
        }
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        if (level.intValue() >= 1000) { // severe
            LOGGER.error(msg, throwable);
        } else if (level.intValue() >= 900) { // warning
            LOGGER.warn(msg, throwable);
        } else {
            LOGGER.info(msg, throwable);
        }
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new MinestomPlatformInfo();
    }

    @Override
    public PlayerPingProvider createPlayerPingProvider() {
        return new MinestomPlayerPingProvider();
    }

    @Override
    public TickReporter createTickReporter() {
        return new MinestomTickReporter();
    }

    @Override
    public TickHook createTickHook() {
        return new MinestomTickHook();
    }

    private static final class MinestomSparkCommand extends Command implements CommandExecutor, SuggestionCallback {
        private final SparkPlatform platform;
        private final BiPredicate<CommandSender, String> permissions;

        public MinestomSparkCommand(SparkPlatform platform, BiPredicate<CommandSender, String> permissions) {
            super("spark");
            this.platform = platform;
            this.permissions = permissions;
            setCondition((sender, input) -> sender instanceof ConsoleSender || permissions.test(sender, "spark"));

            ArgumentStringArray arrayArgument = ArgumentType.StringArray("args");
            arrayArgument.setSuggestionCallback(this);

            addSyntax(this, arrayArgument);
            setDefaultExecutor((sender, context) -> platform.executeCommand(new MinestomCommandSender(sender, permissions), new String[0]));
        }

        // execute
        @Override
        public void apply(@NotNull CommandSender sender, @NotNull CommandContext context) {
            String[] args = processArgs(context, false);
            if (args == null) {
                return;
            }

            this.platform.executeCommand(new MinestomCommandSender(sender, this.permissions), args);
        }

        // tab complete
        @Override
        public void apply(@NotNull CommandSender sender, @NotNull CommandContext context, @NotNull Suggestion suggestion) {
            String[] args = processArgs(context, true);
            if (args == null) {
                return;
            }

            Iterable<String> suggestionEntries = this.platform.tabCompleteCommand(new MinestomCommandSender(sender, this.permissions), args);
            for (String suggestionEntry : suggestionEntries) {
                suggestion.addEntry(new SuggestionEntry(suggestionEntry));
            }
        }

        private static String [] processArgs(CommandContext context, boolean tabComplete) {
            String[] split = context.getInput().split(" ", tabComplete ? -1 : 0);
            if (split.length == 0 || !split[0].equals("/spark") && !split[0].equals("spark")) {
                return null;
            }

            return Arrays.copyOfRange(split, 1, split.length);
        }
    }
}
