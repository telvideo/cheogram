package eu.siacs.conversations.utils;

import net.fellbaum.jemoji.EmojiManager;

public class Emoticons {
    public static boolean isEmoji(String input) {
        return EmojiManager.isEmoji(input);
    }

    public static boolean isOnlyEmoji(String input) {
        return EmojiManager.removeAllEmojis(input).trim().length() == 0;
    }
}
