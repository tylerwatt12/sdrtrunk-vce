/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

/** Compiles a small user search language to quoted FTS5 terms; raw FTS syntax never reaches MATCH. */
final class ManagedRecordingTranscriptQuery
{
    private final String text;
    private int position, terms, depth;

    private ManagedRecordingTranscriptQuery(String text) { this.text = text; }

    static String compile(String text)
    {
        if(text == null || text.isBlank()) return null;
        if(text.length() > 240) throw invalid("must be at most 240 characters");
        if(text.codePoints().anyMatch(value -> Character.isISOControl(value) && !Character.isWhitespace(value)))
            throw invalid("contains an invalid character");
        var parser = new ManagedRecordingTranscriptQuery(text);
        String result = parser.or();
        parser.space();
        if(parser.position != text.length()) throw invalid("has unexpected syntax");
        return result;
    }

    private String or()
    {
        String result = and();
        while(operator("OR")) result = "(" + result + " OR " + and() + ")";
        return result;
    }

    private String and()
    {
        String result = atom();
        while(true)
        {
            if(operator("AND")) result = "(" + result + " AND " + atom() + ")";
            else
            {
                space();
                if(position == text.length() || text.charAt(position) == ')' || nextOperator("OR")) return result;
                result = "(" + result + " AND " + atom() + ")";
            }
        }
    }

    private String atom()
    {
        space();
        if(position >= text.length() || text.charAt(position) == ')' || nextOperator("AND") || nextOperator("OR"))
            throw invalid("requires a word or quoted phrase after each operator");
        if(text.charAt(position) == '(')
        {
            if(++depth > 8) throw invalid("has too many nested parentheses");
            position++;
            String result = or();
            space();
            if(position >= text.length() || text.charAt(position++) != ')') throw invalid("has an unclosed parenthesis");
            depth--;
            return "(" + result + ")";
        }
        StringBuilder word = new StringBuilder();
        if(text.charAt(position) == '"')
        {
            position++;
            boolean closed = false;
            while(position < text.length())
            {
                char c = text.charAt(position++);
                if(c == '"')
                {
                    if(position < text.length() && text.charAt(position) == '"') { word.append('"'); position++; }
                    else { closed = true; break; }
                }
                else word.append(c);
            }
            if(!closed) throw invalid("has an unclosed quoted phrase");
        }
        else
        {
            while(position < text.length() && !Character.isWhitespace(text.charAt(position)) &&
                "()\"".indexOf(text.charAt(position)) < 0)
            {
                char c = text.charAt(position++);
                if("*:^{}+".indexOf(c) >= 0) throw invalid("supports words, quoted phrases, AND, OR and parentheses");
                word.append(c);
            }
        }
        if(word.codePoints().noneMatch(Character::isLetterOrDigit)) throw invalid("requires searchable words");
        if(++terms > 32) throw invalid("has too many terms");
        return "\"" + word.toString().replace("\"", "\"\"") + "\"";
    }

    private void space() { while(position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    private boolean nextOperator(String value)
    {
        if(!text.regionMatches(true, position, value, 0, value.length())) return false;
        int end = position + value.length();
        return end == text.length() || Character.isWhitespace(text.charAt(end)) || "()\"".indexOf(text.charAt(end)) >= 0;
    }
    private boolean operator(String value)
    {
        space();
        if(!nextOperator(value)) return false;
        position += value.length();
        return true;
    }
    private static IllegalArgumentException invalid(String reason)
    { return new IllegalArgumentException("Transcript search " + reason); }
}
