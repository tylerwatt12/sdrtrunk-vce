/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials and encryption material from configuration and text before it can enter a diagnostic bundle.
 */
public final class BugReportRedactor
{
    public static final String REDACTED = "<redacted>";
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(?:password|passwd|passphrase|api[._ -]?key|access[._ -]?key|secret[._ -]?key|private[._ -]?key|" +
            "encryption[._ -]?key|access[._ -]?token|refresh[._ -]?token|auth(?:entication|orization)?[._ -]?token|" +
            "auth(?:entication|orization)?|bearer|credential|client[._ -]?secret|" +
            "vault[._ -]?saved[._ -]?password|token|secret)");
    private static final Pattern LABELED_SECRET = Pattern.compile(
        "(?i)(\\b(?:password|passwd|passphrase|pwd|pw|api[._ -]?key|access[._ -]?key|secret[._ -]?key|" +
            "private[._ -]?key|encryption[._ -]?key|access[._ -]?token|refresh[._ -]?token|" +
            "auth(?:entication|orization)?[._ -]?token|client[._ -]?secret)\\b\\s*[:=]\\s*)" +
            "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern BASIC = Pattern.compile("(?i)(\\bBasic\\s+)[A-Za-z0-9+/=]+");
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
        "(?i)([?&](?:password|passwd|pwd|api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret)=)" +
            "[^&#\\s]*");

    public JsonNode redact(JsonNode node)
    {
        return redact(node, null);
    }

    private JsonNode redact(JsonNode node, String fieldName)
    {
        if(node == null || node.isNull())
        {
            return node;
        }

        if(fieldName != null && isSensitiveField(fieldName))
        {
            return TextNode.valueOf(REDACTED);
        }

        if(node.isObject())
        {
            ObjectNode object = (ObjectNode)node.deepCopy();

            for(Map.Entry<String,JsonNode> field: object.properties())
            {
                object.set(field.getKey(), redact(field.getValue(), field.getKey()));
            }

            return object;
        }

        if(node.isArray())
        {
            ArrayNode array = (ArrayNode)node.deepCopy();

            for(int x = 0; x < array.size(); x++)
            {
                array.set(x, redact(array.get(x), fieldName));
            }

            return array;
        }

        if(node.isTextual())
        {
            return TextNode.valueOf(redactText(node.textValue()));
        }

        return node.deepCopy();
    }

    public String redactFieldValue(String fieldName, String value)
    {
        return isSensitiveField(fieldName) ? REDACTED : redactText(value);
    }

    public String redactText(String text)
    {
        if(text == null || text.isEmpty())
        {
            return text;
        }

        String redacted = replaceSecret(LABELED_SECRET, text);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = BASIC.matcher(redacted).replaceAll("$1" + REDACTED);
        return SENSITIVE_QUERY.matcher(redacted).replaceAll("$1" + REDACTED);
    }

    boolean isSensitiveField(String fieldName)
    {
        return fieldName != null && SENSITIVE_FIELD.matcher(fieldName).find();
    }

    private static String replaceSecret(Pattern pattern, String text)
    {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while(matcher.find())
        {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
