# Vendored Prism4j grammars

All `Prism_*.java` files except `Prism_bash.java` are vendored verbatim from
[noties/Prism4j](https://github.com/noties/Prism4j) (`languages/` module,
Apache License 2.0). They are plain factories over the `io.noties:prism4j`
core already on the classpath.

Why vendored instead of the `prism4j-bundler` annotation processor: the
bundler is a Java APT processor and this project is KSP-only on AGP 9's
built-in Kotlin — adding kapt would destabilize the toolchain. The manual
`GrammarLocator` in `ui/utils/PrismHelper.kt` dispatches to these classes
directly (no `Class.forName`), so R8 needs no extra keep rules.

`Prism_bash.java` is hand-written here (upstream ships no bash grammar).

To add a language: drop the upstream `Prism_<lang>.java` in this package and
add a `when` branch (+ aliases) in `PrismHelper.ManualGrammarLocator`.
