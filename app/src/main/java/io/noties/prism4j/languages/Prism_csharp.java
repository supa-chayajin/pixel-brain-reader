package io.noties.prism4j.languages;

import org.jetbrains.annotations.NotNull;

import io.noties.prism4j.GrammarUtils;
import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.Extend;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.compile;
import static io.noties.prism4j.Prism4j.pattern;
import static io.noties.prism4j.Prism4j.token;

/**
 * Hand-written C# grammar over clike (Prism.js-style token names): keywords, verbatim and
 * interpolated strings, numeric suffixes, preprocessor lines and attributes.
 */
@SuppressWarnings("unused")
@Extend("clike")
public class Prism_csharp {

  @NotNull
  public static Prism4j.Grammar create(@NotNull Prism4j prism4j) {
    final Prism4j.Grammar csharp = GrammarUtils.extend(
        GrammarUtils.require(prism4j, "clike"),
        "csharp",
        token("keyword", pattern(compile(
            "\\b(?:abstract|as|async|await|base|bool|break|byte|case|catch|char|checked|class|const|continue|decimal|default|delegate|do|double|else|enum|event|explicit|extern|false|finally|fixed|float|for|foreach|get|goto|if|implicit|in|init|int|interface|internal|is|lock|long|namespace|new|null|object|operator|out|override|params|partial|private|protected|public|readonly|record|ref|return|sbyte|sealed|set|short|sizeof|stackalloc|static|string|struct|switch|this|throw|true|try|typeof|uint|ulong|unchecked|unsafe|ushort|using|var|virtual|void|volatile|when|where|while|yield)\\b"))),
        token("string",
            pattern(compile("@(\"|')(?:\\1\\1|\\\\[\\s\\S]|(?!\\1)[^\\\\])*\\1"), false, true),
            pattern(compile("\\$?(\"|')(?:\\\\.|(?!\\1)[^\\\\\\r\\n])*?\\1"), false, true)),
        token("number", pattern(compile("\\b0x[\\da-f]+\\b|(?:\\b\\d+(?:\\.\\d+)?|\\B\\.\\d+)(?:e[+-]?\\d+)?[fdmlu]*", CASE_INSENSITIVE)))
    );
    GrammarUtils.insertBeforeToken(csharp, "keyword",
        token("preprocessor", pattern(compile("(^[ \\t]*)#[a-z]+.*", MULTILINE | CASE_INSENSITIVE), true, false, "property")),
        token("attribute", pattern(compile("\\[\\s*[A-Z]\\w*(?:\\([^)\\r\\n]*\\))?\\s*\\]"), false, false, "annotation"))
    );
    return csharp;
  }

  private Prism_csharp() {
  }
}
