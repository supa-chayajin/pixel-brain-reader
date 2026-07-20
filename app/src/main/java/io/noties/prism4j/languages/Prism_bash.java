package io.noties.prism4j.languages;

import org.jetbrains.annotations.NotNull;

import io.noties.prism4j.Prism4j;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static io.noties.prism4j.Prism4j.grammar;
import static io.noties.prism4j.Prism4j.pattern;
import static io.noties.prism4j.Prism4j.token;

/**
 * Hand-written minimal bash/shell grammar — upstream Prism4j ships no bash
 * grammar, so this covers the common vault snippets (comments, strings,
 * variables, keywords, well-known commands). Token names follow Prism.js
 * conventions so the Markwon themes color them without customization.
 */
@SuppressWarnings("unused")
public class Prism_bash {

  @NotNull
  public static Prism4j.Grammar create(@NotNull Prism4j prism4j) {
    return grammar(
        "bash",
        token("shebang", pattern(compile("^#!\\s*/.*"), false, false, "important")),
        token("comment", pattern(compile("(^|[^\"{\\\\$])#.*"), true)),
        token("string", pattern(compile("([\"'])(?:\\\\[\\s\\S]|(?!\\1)[^\\\\])*\\1"), false, true)),
        token("variable", pattern(compile("\\$(?:\\w+|[#?*!@$]|\\{[^}]+\\})"))),
        token("function", pattern(compile("(^|[\\s;|&])\\w+(?=\\s*\\(\\s*\\))"), true)),
        token("keyword", pattern(compile(
            "(^|[\\s;|&])(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|function|select|in|break|continue|return|local|export|readonly|declare|unset|trap|source|alias|shift|exit)(?=$|[)\\s;|&])"), true)),
        token("builtin", pattern(compile(
            "(^|[\\s;|&])(?:echo|printf|read|cd|pwd|ls|cp|mv|rm|mkdir|rmdir|touch|cat|grep|sed|awk|find|curl|wget|chmod|chown|sudo|git|tar|gzip|kill|ps|which|test|set|eval|exec)(?=$|[)\\s;|&])"), true, false, "class-name")),
        token("number", pattern(compile("(^|\\s)(?:0x[\\da-f]+|\\d+(?:\\.\\d+)?)(?=$|\\s)", CASE_INSENSITIVE), true)),
        token("operator", pattern(compile("&&?|\\|\\|?|==?|!=?|<<<?|>>|[<>]=?|=~"))),
        token("punctuation", pattern(compile("\\$?\\(\\(?|\\)\\)?|\\.\\.|[{}\\[\\];]")))
    );
  }

  private Prism_bash() {
  }
}
