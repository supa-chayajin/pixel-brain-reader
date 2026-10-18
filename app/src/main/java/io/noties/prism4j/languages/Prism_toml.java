package io.noties.prism4j.languages;

import org.jetbrains.annotations.NotNull;

import io.noties.prism4j.Prism4j;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.compile;
import static io.noties.prism4j.Prism4j.grammar;
import static io.noties.prism4j.Prism4j.pattern;
import static io.noties.prism4j.Prism4j.token;

/**
 * Hand-written TOML grammar (upstream Prism4j ships none): `[tables]`, dotted and quoted keys,
 * basic, literal and multi-line strings, dates and times, numbers and booleans.
 */
@SuppressWarnings("unused")
public class Prism_toml {

  @NotNull
  public static Prism4j.Grammar create(@NotNull Prism4j prism4j) {
    return grammar(
        "toml",
        token("comment", pattern(compile("#.*"), false, true)),
        token("table", pattern(compile("(^[ \\t]*\\[\\s*(?:\\[\\s*)?)[\\w\\-\".]+(?=\\s*\\])", MULTILINE), true, true, "class-name")),
        token("key", pattern(compile(
            "(^[ \\t]*|[{,]\\s*)(?:[\\w\\-]+|\"(?:\\\\.|[^\\\\\"\\r\\n])*\"|'[^'\\r\\n]*')(?:\\s*\\.\\s*(?:[\\w\\-]+|\"(?:\\\\.|[^\\\\\"\\r\\n])*\"|'[^'\\r\\n]*'))*(?=\\s*=)",
            MULTILINE), true, true, "property")),
        token("string", pattern(compile("\"\"\"(?:\\\\[\\s\\S]|[^\\\\])*?\"\"\"|'''[\\s\\S]*?'''|'[^'\\r\\n]*'|\"(?:\\\\.|[^\\\\\"\\r\\n])*\""), false, true)),
        token("date", pattern(compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:[T\\s]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+\\-]\\d{2}:\\d{2})?)?\\b|\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b",
            CASE_INSENSITIVE), false, false, "number")),
        token("number", pattern(compile(
            "\\b0(?:x[\\da-fA-F]+(?:_[\\da-fA-F]+)*|o[0-7]+(?:_[0-7]+)*|b[01]+(?:_[01]+)*)\\b|[+\\-]?\\b\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?(?:[eE][+\\-]?\\d+(?:_\\d+)*)?\\b|[+\\-]?\\b(?:inf|nan)\\b"))),
        token("boolean", pattern(compile("\\b(?:true|false)\\b"))),
        token("punctuation", pattern(compile("[.,=\\[\\]{}]")))
    );
  }

  private Prism_toml() {
  }
}
