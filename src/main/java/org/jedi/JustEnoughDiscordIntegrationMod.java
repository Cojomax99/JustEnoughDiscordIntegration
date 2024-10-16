package org.jedi;

import com.google.common.collect.Maps;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.WebhookMessageBuilder;
import org.javacord.api.entity.message.mention.AllowedMentions;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.channel.server.text.WebhooksUpdateListener;
import org.javacord.api.listener.message.MessageCreateListener;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Mod("jedi")
public class JustEnoughDiscordIntegrationMod {
    private static final Logger LOGGER = LogManager.getLogger(JustEnoughDiscordIntegrationMod.class);

    private static final Map<UUID, String> CACHE_BUSTS = Maps.newHashMap();

    private static final ModConfigSpec SERVER_CONFIG;
    private static final String VISAGE_URL = "https://visage.surgeplay.com/bust/128/%s.png?bust=%s";

    private static final AllowedMentions NO_MENTIONS = new AllowedMentionsBuilder().build();

    private static ModConfigSpec.ConfigValue<String> joinedGameEntry;
    private static ModConfigSpec.ConfigValue<String> leftGameEntry;
    private static ModConfigSpec.ConfigValue<String> advancementEntry;
    private static ModConfigSpec.ConfigValue<String> serverStoppedEntry;
    private static ModConfigSpec.ConfigValue<String> serverStartedEntry;
    private static ModConfigSpec.ConfigValue<String> serverShuttingDownEntry;
    private static ModConfigSpec.ConfigValue<String> replyingToEntry;
    private static ModConfigSpec.BooleanValue sendDeathMessages;

    private static ModConfigSpec.ConfigValue<String> botTokenEntry;
    private static ModConfigSpec.ConfigValue<List<? extends String>> webhookEntries;
    private static ModConfigSpec.ConfigValue<List<? extends Long>> readChannels;

    private static DiscordApiBuilder discordBuilder;
    private static Optional<DiscordApi> discord = Optional.empty();
    private static Optional<DiscordWebhooks> webhooks = Optional.empty();

    private static DiscordMessageFormatter messageFormatter = new DiscordMessageFormatter("replying to");

    static {
        ModConfigSpec.Builder configBuilder = new ModConfigSpec.Builder();
        setupConfig(configBuilder);
        SERVER_CONFIG = configBuilder.build();
    }

    public JustEnoughDiscordIntegrationMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::setup);
        modBus.addListener(this::loadModConfig);

        container.registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG);
        NeoForge.EVENT_BUS.register(this);
    }

    private static void setupConfig(ModConfigSpec.Builder builder) {
        builder.comment(" Create a bot here: https://discord.com/developers/applications \"Make sure 'Message Content Intent' is enabled\"");
        builder.push("Discord Values");

        botTokenEntry = builder.comment(" The bot-specific token for your Discord bot")
                .define("bot_token", "");

        webhookEntries = builder.comment(" List of webhook URLs to post to from the game")
                .defineList("webhook_urls", List.of(""), entry -> true);

        readChannels = builder.comment(" List of IDs of discord channels to listen to")
                .defineList("read_channels", List.of(), entry -> true);

        builder.push("Translations");

        joinedGameEntry = builder.comment(" Posted in discord when someone joins the world (leave blank to disable)")
                .define("joined_game", "%s joined the game");
        leftGameEntry = builder.comment(" Posted in discord when someone leaves the world (leave blank to disable)")
                .define("left_game", "%s left the game");
        advancementEntry = builder.comment(" Posted in discord when someone makes an advancement (leave blank to disable)")
                .define("advancement", "%s has made the advancement **%s** - %s");
        serverStartedEntry = builder.comment(" Posted in discord when the server has started up (leave blank to disable)")
                .define("server_started", "Server started!");
        serverStoppedEntry = builder.comment(" Posted in discord when the server has stopped (leave blank to disable)")
                .define("server_stopped", "Server stopped!");
        serverShuttingDownEntry = builder.comment(" Posted in discord when the server begins shutting down (leave blank to disable)")
                .define("server_shutting_down", "Server shutting down!");
        replyingToEntry = builder.comment(" The text to use to relay that a Discord message is being replied to (leave blank to disable)")
                .define("replying_to", "replying to");
        sendDeathMessages = builder.comment(" Whether to send a Discord message when a player or named entity dies")
                .define("send_death_messages", true);
    }

    private void setup(final FMLCommonSetupEvent event) {
        discordBuilder = new DiscordApiBuilder();
        discordBuilder.addMessageCreateListener(new DiscordMessageListener());
        discordBuilder.addWebhooksUpdateListener((WebhooksUpdateListener) e -> loadWebhooks());
        discordBuilder.setShutdownHookRegistrationEnabled(false);
    }

    private void loadModConfig(ModConfigEvent.Loading event) {
        loadFromConfig();
    }

    private void reloadModConfig(ModConfigEvent.Reloading event) {
        loadFromConfig();
    }

    private static void loadFromConfig() {
        loadWebhooks();
        messageFormatter = new DiscordMessageFormatter(replyingToEntry.get());
    }

    private static void loadWebhooks() {
        webhooks = discord.map(discord -> DiscordWebhooks.load(discord, webhookEntries.get()));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        final String token = botTokenEntry.get();
        if (token.length() > 0) {
            discordBuilder.setToken(token);
            discordBuilder.addIntents(Intent.MESSAGE_CONTENT);
            discordBuilder.login().thenAccept(dcObject -> {
                discord = Optional.of(dcObject);
                loadWebhooks();
                sendMessage(serverStartedEntry);
            });
        } else {
            LOGGER.warn("Couldn't connect to discord - token empty. Check server config jedi.toml!");
        }
    }

    @SubscribeEvent
    public void onServerShuttingDown(ServerStoppingEvent event) {
        sendMessage(serverShuttingDownEntry);
    }

    @SubscribeEvent
    public void onServerShutdown(ServerStoppedEvent event) {
        CACHE_BUSTS.clear();
        try {
            sendMessage(serverStoppedEntry).get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        discord.ifPresent(DiscordApi::disconnect);
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        if (!sendDeathMessages.get()) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (entity.getType() == EntityType.PLAYER || entity.hasCustomName()) {
            sendMessage(strip(event.getSource().getLocalizedDeathMessage(entity).getString()));
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        CACHE_BUSTS.remove(event.getEntity().getUUID());
        sendMessage(leftGameEntry, getPlayerName(event));
    }

    @SubscribeEvent
    public void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        CACHE_BUSTS.put(event.getEntity().getUUID(), String.valueOf(System.currentTimeMillis()));
        sendMessage(joinedGameEntry, getPlayerName(event));
    }

    @SubscribeEvent
    public void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        final Advancement advancement = event.getAdvancement().value();
        if (advancement.display().isEmpty()) {
            return;
        }
        final String playerName = strip(event.getEntity().getDisplayName().getString());
        final String advancementName = strip(advancement.display().get().getTitle().getString());
        final String advancementDesc = strip(advancement.display().get().getDescription().getString());
        sendMessage(advancementEntry, playerName, advancementName, advancementDesc);
    }

    private static CompletableFuture<?> sendMessage(final ModConfigSpec.ConfigValue<String> entry, final Object... args) {
        final String value = entry.get();
        if (!value.isBlank()) {
            return sendMessage(String.format(Locale.ROOT, value, args));
        }
        return CompletableFuture.completedFuture(null);
    }

    @SubscribeEvent
    public void onServerChatEvent(ServerChatEvent event) {
        try {
            final String username = event.getUsername();
            final String message = event.getMessage().getString();
            if (!message.isBlank()) {
                final String uuid = event.getPlayer().getStringUUID();
                final String cacheBust = CACHE_BUSTS.getOrDefault(event.getPlayer().getUUID(), username);

                sendMessage(message, username, new URL(String.format(VISAGE_URL, uuid, cacheBust)));
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    private static String getPlayerName(final PlayerEvent event) {
        return strip(event.getEntity().getDisplayName().getString());
    }

    private static class DiscordMessageListener implements MessageCreateListener {
        @Override
        public void onMessageCreate(MessageCreateEvent event) {
            final Message message = event.getMessage();

            if (!message.getAuthor().isRegularUser()) return;
            if (!readChannels.get().contains(event.getChannel().getId())) return;

            Component chatMessage = messageFormatter.format(message);
            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(chatMessage, false);
        }
    }

    private static CompletableFuture<Void> sendMessage(final String message) {
        return webhooks.map(webhooks -> {
                    final WebhookMessageBuilder builder = new WebhookMessageBuilder().append(message).setAllowedMentions(NO_MENTIONS);
                    return webhooks.send(builder);
                })
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    private static void sendMessage(final String message, final String username, final URL url) {
        webhooks.ifPresent(webhooks -> {
            webhooks.send(new WebhookMessageBuilder()
                    .append(message)
                    .setDisplayName(username)
                    .setDisplayAvatar(url)
                    .setAllowedMentions(NO_MENTIONS)
            );
        });
    }

    private static String strip(final String original) {
        return ChatFormatting.stripFormatting(original);
    }
}
