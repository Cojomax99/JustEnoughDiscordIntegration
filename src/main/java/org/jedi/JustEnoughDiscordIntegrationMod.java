package org.jedi;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fmllegacy.server.ServerLifecycleHooks;
import net.minecraftforge.fmlserverevents.FMLServerStartingEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.webhook.IncomingWebhook;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;

import java.awt.Color;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Mod("jedi")
public class JustEnoughDiscordIntegrationMod {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final ForgeConfigSpec GENERAL_SPEC;
    private static final String VISAGE_URL = "https://visage.surgeplay.com/bust/128/%s.png";

    private static ForgeConfigSpec.ConfigValue<String> joinedGameEntry;
    private static ForgeConfigSpec.ConfigValue<String> leftGameEntry;
    private static ForgeConfigSpec.ConfigValue<String> advancementEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverStoppedEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverStartedEntry;
    private static ForgeConfigSpec.ConfigValue<String> serverShuttingDownEntry;

    private static ForgeConfigSpec.ConfigValue<String> botTokenEntry;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> webhookEntries;

    private static DiscordApiBuilder discordBuilder;
    private static Optional<DiscordApi> discord = Optional.empty();

    static {
        ForgeConfigSpec.Builder configBuilder = new ForgeConfigSpec.Builder();
        setupConfig(configBuilder);
        GENERAL_SPEC = configBuilder.build();
    }

    public JustEnoughDiscordIntegrationMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GENERAL_SPEC, "jedi.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void setupConfig(ForgeConfigSpec.Builder builder) {
        builder.comment(" Create a bot here: https://discord.com/developers/applications");
        builder.push("Discord Values");

        botTokenEntry = builder.comment(" The bot-specific token for your Discord bot")
                .define("bot_token", "");

        webhookEntries = builder.comment(" List of webhook URLs to post to from the game")
                .defineList("webhook_urls", List.of(""), entry -> true);

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
    }

    private void setup(final FMLCommonSetupEvent event) {
        discordBuilder = new DiscordApiBuilder();
        discordBuilder.addMessageCreateListener(new DiscordMessageListener());
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        discordBuilder.setToken(botTokenEntry.get());
        discordBuilder.login().thenAccept(dcObject -> {
            discord = Optional.of(dcObject);
            sendMessage(serverStartedEntry.get());
        });
    }

    @SubscribeEvent
    public void onServerShuttingDown(FMLServerStoppingEvent event) {
        sendMessage(serverShuttingDownEntry.get());
    }

    @SubscribeEvent
    public void onServerShutdown(FMLServerStoppedEvent event) {
        discord.ifPresent(dc -> sendMessage(serverStoppedEntry.get(), wh -> dc.disconnect()));
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        sendMessage(strip(event.getSource().getLocalizedDeathMessage(event.getEntityLiving()).getString()));
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        sendMessage(String.format(leftGameEntry.get(), getPlayerName(event)));
    }

    @SubscribeEvent
    public void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        sendMessage(String.format(joinedGameEntry.get(), getPlayerName(event)));
    }

    @SubscribeEvent
    public void playerLeave(AdvancementEvent event) {
        final Advancement advancement = event.getAdvancement();
        if (advancement.getDisplay() == null) {
            return;
        }
        final String playerName = strip(event.getPlayer().getDisplayName().getString());
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

            sendMessage(message, username, new URL(String.format(VISAGE_URL, uuid)));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    private static String getPlayerName(final PlayerEvent event) {
        return strip(event.getPlayer().getDisplayName().getString());
    }

    private static class DiscordMessageListener implements MessageCreateListener {
        @Override
        public void onMessageCreate(MessageCreateEvent event) {
            final Message message = event.getMessage();

            if (!message.getAuthor().isRegularUser()) return;

            final Optional<Message> parentMessage = message.getReferencedMessage();

            final Optional<Color> color = event.getMessageAuthor().getRoleColor();
            final TextColor textColor;
            textColor = color.map(value -> TextColor.fromRgb(value.getRGB())).orElseGet(() -> TextColor.fromLegacyFormat(ChatFormatting.WHITE));

            final TextComponent chatMessage = new TextComponent("<");

            final TextComponent tc = new TextComponent("");
            tc.withStyle(Style.EMPTY.withColor(textColor));
            tc.append(String.format("%s", event.getMessageAuthor().getDisplayName()));

            final TextComponent other = new TextComponent("");
            other.append(event.getReadableMessageContent());

            chatMessage.append(tc);
            if (parentMessage.isPresent()) {
                final Optional<Color> parentColor = parentMessage.get().getAuthor().getRoleColor();
                final TextColor parentTextColor;
                parentTextColor = parentColor.map(value -> TextColor.fromRgb(value.getRGB())).orElseGet(() -> TextColor.fromLegacyFormat(ChatFormatting.WHITE));

                chatMessage.append(" ").append(new TranslatableComponent("jedi.replying_to")).append(" ");
                final TextComponent parentChatMessage = new TextComponent("");
                parentChatMessage.withStyle(Style.EMPTY.withColor(parentTextColor));
                parentChatMessage.append(parentMessage.get().getAuthor().getDisplayName());
                chatMessage.append(parentChatMessage);
            }
            chatMessage.append("> ");
            chatMessage.append(other);

            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastMessage(chatMessage, ChatType.CHAT, Util.NIL_UUID);
        }
    }

    private static void sendMessage(final String message) {
        sendMessage(message, w -> {});
    }

    private static void sendMessage(final String message, final String username, final URL url) {
        sendMessage(message, username, url, w -> {});
    }

    private static void sendMessage(final String message, final Consumer<IncomingWebhook> afterSend) {
        discord.ifPresent(dc -> {
            try {
                webhookEntries.get().forEach(webhookUrl -> {
                    final CompletableFuture<IncomingWebhook> w = dc.getIncomingWebhookByUrl(webhookUrl);
                    w.thenAccept(wh -> wh.sendMessage(message).thenAccept(m -> afterSend.accept(wh)));
                });
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });
    }

    private static void sendMessage(final String message, final String username, final URL url, final Consumer<IncomingWebhook> afterSend) {
        discord.ifPresent(dc -> {
            try {
                webhookEntries.get().forEach(webhookUrl -> {
                    final CompletableFuture<IncomingWebhook> w = dc.getIncomingWebhookByUrl(webhookUrl);
                    w.thenAccept(wh -> wh.sendMessage(message, username, url).thenAccept(m -> afterSend.accept(wh)));
                });
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });
    }

    private static String strip(final String original) {
        return ChatFormatting.stripFormatting(original);
    }
}
