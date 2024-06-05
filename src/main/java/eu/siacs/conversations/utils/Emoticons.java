/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.util.LruCache;

import java.util.HashSet;
import java.util.regex.Pattern;

import net.fellbaum.jemoji.EmojiManager;

public class Emoticons {

    private static final int MAX_EMOIJS = 42;
    private static final LruCache<CharSequence, Pattern> CACHE = new LruCache<>(256);

    public static Pattern getEmojiPattern(final CharSequence input) {
        Pattern pattern = CACHE.get(input);
        if (pattern == null) {
            pattern = generatePattern(input);
            CACHE.put(input, pattern);
        }
        return pattern;
    }

    private static Pattern generatePattern(CharSequence input) {
        final HashSet<String> emojis = new HashSet<>();
        for (final var emoji : EmojiManager.extractEmojisInOrder(input.toString())) {
            emojis.add(emoji.getUnicode());
        }
        final StringBuilder pattern = new StringBuilder();
        for (String emoji : emojis) {
            if (pattern.length() != 0) {
                pattern.append('|');
            }
            pattern.append(Pattern.quote(emoji));
        }
        return Pattern.compile(pattern.toString());
    }

    public static boolean isEmoji(String input) {
        return EmojiManager.isEmoji(input);
    }

    public static boolean isOnlyEmoji(String input) {
        return EmojiManager.removeAllEmojis(input).trim().length() == 0;
    }
}
