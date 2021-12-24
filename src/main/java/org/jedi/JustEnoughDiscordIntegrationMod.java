package org.jedi;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.damagesource.DamageSource;
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

    public static final ForgeConfigSpec GENERAL_SPEC;

    private static ForgeConfigSpec.ConfigValue<String> botTokenEntry;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> webhookEntries;

    private static DiscordApiBuilder discordBuilder;
    private static DiscordApi discord;

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
    }

    private void setup(final FMLCommonSetupEvent event) {
        discordBuilder = new DiscordApiBuilder();
        discordBuilder.addMessageCreateListener(new DiscordMessageListener());
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        LOGGER.info("SERVER STARTING");
        discordBuilder.setToken(botTokenEntry.get());
        discordBuilder.login().thenAccept(dcObject -> {
            discord = dcObject;
            sendMessage("Server Starting!");
        });
    }

    @SubscribeEvent
    public void onServerShutdown(FMLServerStoppingEvent event) {
        if (discord != null) {
            LOGGER.info("Shutting down discord");
            sendMessage("Server shutting down!");
        }
    }

    @SubscribeEvent
    public void onServerShutdown(FMLServerStoppedEvent event) {
        if (discord != null) {
            LOGGER.info("Discord stopped");
            sendMessage("Server stopped!", wh -> discord.disconnect());
        }
    }

    @SubscribeEvent
    public void death(LivingDeathEvent event) {
        if (discord != null) {
            final DamageSource source = event.getSource();

            sendMessage(strip(source.getLocalizedDeathMessage(event.getEntityLiving()).getString()));
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (discord != null) {
            final String playerName = strip(event.getPlayer().getDisplayName().getString());

            sendMessage(String.format("%s left the game", playerName));
        }
    }

    @SubscribeEvent
    public void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (discord != null) {
            final String playerName = strip(event.getPlayer().getDisplayName().getString());

            sendMessage(String.format("%s joined the game", playerName));
        }
    }

    @SubscribeEvent
    public void playerLeave(AdvancementEvent event) {
        if (discord != null) {
            final Advancement advancement = event.getAdvancement();
            if (advancement.getDisplay() == null) {
                return;
            }
            final String playerName = strip(event.getPlayer().getDisplayName().getString());
            final String advancementName = strip(advancement.getDisplay().getTitle().getString());
            final String advancementDesc = strip(advancement.getDisplay().getDescription().getString());

            sendMessage(String.format("%s has made the advancement **%s** - %s", playerName, advancementName, advancementDesc));
        }
    }

    private static String strip(final String original) {
        return ChatFormatting.stripFormatting(original);
    }

    @SubscribeEvent
    public void onServerChatEvent(ServerChatEvent event) {
        try {
            final String username = event.getUsername();
            final String message = event.getMessage();
            final String uuid = event.getPlayer().getStringUUID();

            sendMessage(message, username, new URL(String.format("https://visage.surgeplay.com/bust/128/%s.png", uuid)));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
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

                chatMessage.append(" replying to ");
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
        if (discord != null) {
            try {
                webhookEntries.get().forEach(webhookUrl -> {
                    final CompletableFuture<IncomingWebhook> w = discord.getIncomingWebhookByUrl(webhookUrl);
                    w.thenAccept(wh -> wh.sendMessage(message).thenAccept(m -> afterSend.accept(wh)));
                });
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }
    }

    private static void sendMessage(final String message, final String username, final URL url, final Consumer<IncomingWebhook> afterSend) {
        if (discord != null) {
            try {
                webhookEntries.get().forEach(webhookUrl -> {
                    final CompletableFuture<IncomingWebhook> w = discord.getIncomingWebhookByUrl(webhookUrl);
                    w.thenAccept(wh -> wh.sendMessage(message, username, url).thenAccept(m -> afterSend.accept(wh)));
                });
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }
    }
}
