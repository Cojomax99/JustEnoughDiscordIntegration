package org.jedi;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import org.apache.commons.io.FileUtils;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;

import java.util.ArrayList;
import java.util.List;

public final class DiscordMessageFormatter {
    private final String replyingToText;

    public DiscordMessageFormatter(String replyingToText) {
        this.replyingToText = replyingToText;
    }

    public Component format(Message message) {
        final TextComponent header = new TextComponent("<@");
        header.append(this.formatAuthorName(message));
        header.append("> ");

        final MultilineBuilder builder = new MultilineBuilder(header);

        message.getReferencedMessage().ifPresent(parentMessage -> {
            final MutableComponent replyingTo = new TextComponent(this.replyingToText + " @")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                    .append(this.formatAuthorName(parentMessage));
            builder.appendLine(replyingTo);
        });

        final String[] lines = message.getReadableContent().split("\n");
        for (String line : lines) {
            builder.appendLine(new TextComponent(line));
        }

        for (MessageAttachment attachment : message.getAttachments()) {
            builder.appendLine(this.formatAttachment(attachment));
        }

        return builder.build();
    }

    private Component formatAuthorName(Message message) {
        final TextColor color = this.getAuthorColor(message);
        final TextComponent discriminatedName = new TextComponent(message.getAuthor().getDiscriminatedName());

        return new TextComponent(message.getAuthor().getDisplayName())
                .withStyle(Style.EMPTY
                        .withColor(color)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, discriminatedName))
                );
    }

    private MutableComponent formatAttachment(MessageAttachment attachment) {
        final String url = attachment.getUrl().toString();
        final Style attachmentStyle = Style.EMPTY
                .withColor(ChatFormatting.BLUE).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(url).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)));

        final String description = this.getAttachmentDescription(attachment);
        return new TextComponent(description).withStyle(attachmentStyle);
    }

    private String getAttachmentDescription(MessageAttachment attachment) {
        final String fileName = attachment.getFileName();
        final String size = FileUtils.byteCountToDisplaySize(attachment.getSize());

        if (attachment.isImage()) {
            int width = attachment.getWidth().orElse(0);
            int height = attachment.getHeight().orElse(0);
            return "[Image: " + fileName + " (" + width + "x" + height + "/" + size + ")]";
        } else {
            return "[Attachment: " + fileName + " (" + size + ")]";
        }
    }

    private TextColor getAuthorColor(Message message) {
        return message.getAuthor().getRoleColor()
                .map(value -> TextColor.fromRgb(value.getRGB() & 0xFFFFFF))
                .orElseGet(() -> TextColor.fromLegacyFormat(ChatFormatting.WHITE));
    }

    private static final class MultilineBuilder {
        private static final MutableComponent NEW_LINE = new TextComponent("\n | ").withStyle(ChatFormatting.GRAY);

        private final MutableComponent header;
        private final List<MutableComponent> lines = new ArrayList<>();

        public MultilineBuilder(MutableComponent header) {
            this.header = header;
        }

        public void appendLine(MutableComponent line) {
            this.lines.add(line);
        }

        public Component build() {
            MutableComponent result = this.header;
            for (int i = 0; i < this.lines.size(); i++) {
                if (i > 0) result.append(NEW_LINE);
                result.append(this.lines.get(i));
            }

            return result;
        }
    }
}
