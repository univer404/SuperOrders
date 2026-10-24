package com.superorders.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared GUI text parser. Supports MiniMessage (hex, gradients, rainbow, decorations),
 * classic &/§ color codes, &#RRGGBB, and &x&F&F&0&0&A&A RGB codes.
 */
public final class GuiText {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("(?i)[&§]#([0-9a-f]{6})");
    private static final Pattern MOJANG_RGB = Pattern.compile("(?i)[&§]x(?:[&§]([0-9a-f])){6}");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§]([0-9a-fk-or])");
    private GuiText() {}

    public static Component component(String input) {
        String value = input == null ? "" : input;
        value = convertHex(value);
        value = convertMojangRgb(value);
        value = convertLegacy(value);
        return MINI.deserialize(value).decoration(TextDecoration.ITALIC, false);
    }

    public static String plain(String input) {
        return PlainTextComponentSerializer.plainText().serialize(component(input));
    }

    private static String convertHex(String input) {
        Matcher matcher = HEX.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(out, "<#" + matcher.group(1).toUpperCase(Locale.ROOT) + ">");
        matcher.appendTail(out);
        return out.toString();
    }

    private static String convertMojangRgb(String input) {
        Matcher matcher = MOJANG_RGB.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group();
            String hex = raw.replaceAll("(?i)[&§]x", "").replaceAll("[&§]", "");
            matcher.appendReplacement(out, "<#" + hex.toUpperCase(Locale.ROOT) + ">");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String convertLegacy(String input) {
        Matcher matcher = LEGACY.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1).toLowerCase(Locale.ROOT);
            String tag = switch (code) {
                case "0" -> "black"; case "1" -> "dark_blue"; case "2" -> "dark_green";
                case "3" -> "dark_aqua"; case "4" -> "dark_red"; case "5" -> "dark_purple";
                case "6" -> "gold"; case "7" -> "gray"; case "8" -> "dark_gray";
                case "9" -> "blue"; case "a" -> "green"; case "b" -> "aqua";
                case "c" -> "red"; case "d" -> "light_purple"; case "e" -> "yellow";
                case "f" -> "white"; case "k" -> "obfuscated"; case "l" -> "bold";
                case "m" -> "strikethrough"; case "n" -> "underlined"; case "o" -> "italic";
                case "r" -> "reset"; default -> null;
            };
            if (tag == null) matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            else matcher.appendReplacement(out, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
