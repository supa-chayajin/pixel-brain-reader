package io.noties.prism4j.languages;

import org.jetbrains.annotations.NotNull;

import io.noties.prism4j.GrammarUtils;
import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.Extend;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static io.noties.prism4j.Prism4j.grammar;
import static io.noties.prism4j.Prism4j.pattern;
import static io.noties.prism4j.Prism4j.token;

/**
 * Hand-written PHP grammar over clike (upstream Prism4j ships none): `$variables`, `#` comments,
 * the `<?php` delimiters, namespaces, keywords (case-insensitive like PHP) and its operators.
 */
@SuppressWarnings("unused")
@Extend("clike")
public class Prism_php {

  @NotNull
  public static Prism4j.Grammar create(@NotNull Prism4j prism4j) {
    final Prism4j.Grammar php = GrammarUtils.extend(
        GrammarUtils.require(prism4j, "clike"),
        "php",
        token("keyword", pattern(compile(
            "\\b(?:abstract|and|array|as|break|callable|case|catch|class|clone|const|continue|declare|default|die|do|echo|else|elseif|empty|enum|eval|exit|extends|final|finally|fn|for|foreach|function|global|goto|if|implements|include|include_once|instanceof|insteadof|interface|isset|list|match|namespace|new|or|parent|print|private|protected|public|readonly|require|require_once|return|self|static|switch|throw|trait|try|unset|use|var|while|xor|yield)\\b",
            CASE_INSENSITIVE))),
        token("boolean", pattern(compile("\\b(?:false|true|null)\\b", CASE_INSENSITIVE), false, false, "constant")),
        token("number", pattern(compile("\\b0x[\\da-f]+\\b|(?:\\b\\d+(?:_\\d+)*(?:\\.\\d+)?|\\B\\.\\d+)(?:e[+-]?\\d+)?", CASE_INSENSITIVE))),
        token("operator", pattern(compile("<?=>|\\?\\?=?|\\.{3}|\\??->|[!=]==?|::|\\*\\*=?|--|\\+\\+|&&|\\|\\||<<|>>|[?~]|[/^|%*&<>.+\\-]=?")))
    );
    GrammarUtils.insertBeforeToken(php, "string",
        token("shell-comment", pattern(compile("(^|[^\\\\])#.*"), true, false, "comment"))
    );
    GrammarUtils.insertBeforeToken(php, "keyword",
        token("delimiter", pattern(compile("\\?>$|^<\\?(?:php(?=\\s)|=)?", CASE_INSENSITIVE), false, false, "important")),
        token("variable", pattern(compile("\\$+(?:\\w+\\b|(?=\\{))"))),
        token("package", pattern(compile("(namespace\\s+|use\\s+)[\\w\\\\]+"), true, false, null,
            grammar("inside", token("punctuation", pattern(compile("\\\\"))))))
    );
    return php;
  }

  private Prism_php() {
  }
}
