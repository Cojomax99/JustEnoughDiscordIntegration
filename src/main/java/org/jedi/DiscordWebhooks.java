package org.jedi;

import org.javacord.api.DiscordApi;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.WebhookMessageBuilder;
import org.javacord.api.entity.webhook.IncomingWebhook;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DiscordWebhooks {
    private final List<IncomingWebhook> webhooks = new ArrayList<>();
    private final List<CompletableFuture<IncomingWebhook>> pendingWebhooks;

    public DiscordWebhooks(List<CompletableFuture<IncomingWebhook>> pendingWebhooks) {
        this.pendingWebhooks = pendingWebhooks;
    }

    public static DiscordWebhooks load(DiscordApi discord, List<? extends String> urls) {
        List<CompletableFuture<IncomingWebhook>> pendingWebhooks = new ArrayList<>(urls.size());
        for (String url : urls) {
            pendingWebhooks.add(discord.getIncomingWebhookByUrl(url));
        }

        return new DiscordWebhooks(pendingWebhooks);
    }

    private void resolvePendingWebhooks() {
        this.pendingWebhooks.removeIf(pending -> {
            if (pending.isDone()) {
                this.webhooks.add(pending.join());
                return true;
            } else {
                return false;
            }
        });
    }

    public CompletableFuture<Void> send(WebhookMessageBuilder message) {
        this.resolvePendingWebhooks();

        List<CompletableFuture<Message>> futures = new ArrayList<>(this.webhooks.size() + this.pendingWebhooks.size());

        for (IncomingWebhook webhook : this.webhooks) {
            futures.add(message.send(webhook));
        }

        for (CompletableFuture<IncomingWebhook> pending : this.pendingWebhooks) {
            futures.add(pending.thenCompose(message::send));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[] {}));
    }
}
