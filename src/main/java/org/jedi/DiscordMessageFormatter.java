package org.jedi;

import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.StringTextComponent;
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

    public IFormattableTextComponent format(Message message) {
        final StringTextComponent header = new StringTextComponent("<@");
        header.append(this.formatAuthorName(message));
        header.append("> ");

        final MultilineBuilder builder = new MultilineBuilder(header);

        message.getReferencedMessage().ifPresent(parentMessage -> {
            final IFormattableTextComponent replyingTo = new StringTextComponent(this.replyingToText + " @")
                    .withStyle(TextFormatting.GRAY, TextFormatting.ITALIC)
                    .append(this.formatAuthorName(parentMessage));
            builder.appendLine(replyingTo);
        });

        final String[] lines = message.getReadableContent().split("\n");
        for (String line : lines) {
            builder.appendLine(new StringTextComponent(line));
        }

        for (MessageAttachment attachment : message.getAttachments()) {
            builder.appendLine(this.formatAttachment(attachment));
        }

        return builder.build();
    }

    private IFormattableTextComponent formatAuthorName(Message message) {
        final Color color = this.getAuthorColor(message);
        final StringTextComponent discriminatedName = new StringTextComponent(message.getAuthor().getDiscriminatedName());

        return new StringTextComponent(message.getAuthor().getDisplayName())
                .withStyle(Style.EMPTY
                        .withColor(color)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, discriminatedName))
                );
    }

    private IFormattableTextComponent formatAttachment(MessageAttachment attachment) {
        final String url = attachment.getUrl().toString();
        final Style attachmentStyle = Style.EMPTY
                .withColor(TextFormatting.BLUE).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(url).withStyle(TextFormatting.BLUE, TextFormatting.UNDERLINE)));

        final String description = this.getAttachmentDescription(attachment);
        return new StringTextComponent(description).withStyle(attachmentStyle);
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

    private Color getAuthorColor(Message message) {
        return message.getAuthor().getRoleColor()
                .map(value -> Color.fromRgb(value.getRGB() & 0xFFFFFF))
                .orElseGet(() -> Color.fromLegacyFormat(TextFormatting.WHITE));
    }

    private static final class MultilineBuilder {
        private static final IFormattableTextComponent NEW_LINE = new StringTextComponent("\n | ").withStyle(TextFormatting.GRAY);

        private final IFormattableTextComponent header;
        private final List<IFormattableTextComponent> lines = new ArrayList<>();

        public MultilineBuilder(IFormattableTextComponent header) {
            this.header = header;
        }

        public void appendLine(IFormattableTextComponent line) {
            this.lines.add(line);
        }

        public IFormattableTextComponent build() {
            IFormattableTextComponent result = this.header;
            for (int i = 0; i < this.lines.size(); i++) {
                if (i > 0) result.append(NEW_LINE);
                result.append(this.lines.get(i));
            }

            return result;
        }
    }
}
