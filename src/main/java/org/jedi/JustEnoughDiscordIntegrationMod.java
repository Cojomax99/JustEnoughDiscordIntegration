package org.jedi;

import com.google.common.collect.Maps;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraftforge.network.NetworkConstants.IGNORESERVERONLY;

@Mod("jedi")
public class JustEnoughDiscordIntegrationMod {
    private static final Logger LOGGER = LogManager.getLogger(JustEnoughDiscordIntegrationMod.class);

    private static final Map<UUID, String> CACHE_BUSTS = Maps.newHashMap();

    private static final ForgeConfigSpec SERVER_CONFIG;
    private static final String VISAGE_URL = "https://visage.surgeplay.com/bust/128/%s.png?bust=%s";

    private static final AllowedMentions NO_MENTIONS = new AllowedMentionsBuilder().build();

    private static ForgeConfigSpec.ConfigValue<String> joinedGameEntry;
    private static ForgeConfigSpec.ConfigValue<String> leftGameEntry;
    private static ForgeConfigSpec.ConfigValue<String> advancementEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverStoppedEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverStartedEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverShuttingDownEntry;
    private static ForgeConfigSpec.ConfigValue<String> replyingToEntry;

    private static ForgeConfigSpec.ConfigValue<String> botTokenEntry;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> webhookEntries;
    private static ForgeConfigSpec.ConfigValue<List<? extends Long>> readChannels;

    private static DiscordApiBuilder discordBuilder;
    private static Optional<DiscordApi> discord = Optional.empty();
    private static Optional<DiscordWebhooks> webhooks = Optional.empty();

    private static DiscordMessageFormatter messageFormatter = new DiscordMessageFormatter("replying to");

    static {
        ForgeConfigSpec.Builder configBuilder = new ForgeConfigSpec.Builder();
        setupConfig(configBuilder);
        SERVER_CONFIG = configBuilder.build();
    }

    public JustEnoughDiscordIntegrationMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::setup);
        modBus.addListener(this::loadModConfig);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG);
        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> {
            return new IExtensionPoint.DisplayTest(
                    () -> IGNORESERVERONLY,
                    (a, b) -> true
            );
        });
    }

    private static void setupConfig(ForgeConfigSpec.Builder builder) {
        builder.comment(" Create a bot here: https://discord.com/developers/applications");
        builder.push("Discord Values");

        botTokenEntry = builder.comment(" The bot-specific token for your Discord bot")
                .define("bot_token", "");

        webhookEntries = builder.comment(" List of webhook URLs to post to from the game")
                .defineList("webhook_urls", List.of(""), entry -> true);

        readChannels = builder.comment(" List of IDs of discord channels to listen to")
                .defineList("read_channels", List.of(), entry -> true);

        builder.push("Translations");

        joinedGameEntry = builder.comment(" Posted in discord when someone joins the world")
                .define("joined_game", "%s joined the game");
        leftGameEntry = builder.comment(" Posted in discord when someone leaves the world")
                .define("left_game", "%s left the game");
        advancementEntry = builder.comment(" Posted in discord when someone makes an advancement")
                .define("advancement", "%s has made the advancement **%s** - %s");
        serverStartedEntry = builder.comment(" Posted in discord when the server has started up")
                .define("server_started", "Server started!");
        serverStoppedEntry = builder.comment(" Posted in discord when the server has stopped")
                .define("server_stopped", "Server stopped!");
        serverShuttingDownEntry = builder.comment(" Posted in discord when the server begins shutting down")
                .define("server_shutting_down", "Server shutting down!");
        replyingToEntry = builder.comment(" The text to use to relay that a Discord message is being replied to")
                .define("replying_to", "replying to");
    }

    private void setup(final FMLCommonSetupEvent event) {
        discordBuilder = new DiscordApiBuilder();
        discordBuilder.addMessageCreateListener(new DiscordMessageListener());
        discordBuilder.addWebhooksUpdateListener((WebhooksUpdateListener) e -> loadWebhooks());
        discordBuilder.setShutdownHookRegistrationEnabled(false);
    }

    private void loadModConfig(ModConfigEvent event) {
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
            discordBuilder.login().thenAccept(dcObject -> {
                discord = Optional.of(dcObject);
                loadWebhooks();
                sendMessage(serverStartedEntry.get());
            });
        } else {
            LOGGER.warn("Couldn't connect to discord - token empty. Check server config jedi.toml!");
        }
    }

    @SubscribeEvent
    public void onServerShuttingDown(ServerStoppingEvent event) {
        sendMessage(serverShuttingDownEntry.get());
    }

    @SubscribeEvent
    public void onServerShutdown(ServerStoppedEvent event) {
        CACHE_BUSTS.clear();
        sendMessage(serverStoppedEntry.get()).join();
        discord.ifPresent(DiscordApi::disconnect);
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        if (entity.getType() == EntityType.PLAYER || entity.hasCustomName()) {
            sendMessage(strip(event.getSource().getLocalizedDeathMessage(entity).getString()));
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        CACHE_BUSTS.remove(event.getEntity().getUUID());
        sendMessage(String.format(leftGameEntry.get(), getPlayerName(event)));
    }

    @SubscribeEvent
    public void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        CACHE_BUSTS.put(event.getEntity().getUUID(), "" + System.currentTimeMillis());
        sendMessage(String.format(joinedGameEntry.get(), getPlayerName(event)));
    }

    @SubscribeEvent
    public void playerLeave(AdvancementEvent event) {
        final Advancement advancement = event.getAdvancement();
        if (advancement.getDisplay() == null) {
            return;
        }
        final String playerName = strip(event.getEntity().getDisplayName().getString());
        final String advancementName = strip(advancement.getDisplay().getTitle().getString());
        final String advancementDesc = strip(advancement.getDisplay().getDescription().getString());

        sendMessage(String.format(advancementEntry.get(), playerName, advancementName, advancementDesc));
    }

    @SubscribeEvent
    public void onServerChatEvent(ServerChatEvent event) {
        try {
            final String username = event.getUsername();
            final String message = event.getMessage();
            final String uuid = event.getPlayer().getStringUUID();
            final String cacheBust = CACHE_BUSTS.getOrDefault(event.getPlayer().getUUID(), username);

            sendMessage(message, username, new URL(String.format(VISAGE_URL, uuid, cacheBust)));
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
            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(chatMessage, ChatType.SYSTEM);
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
