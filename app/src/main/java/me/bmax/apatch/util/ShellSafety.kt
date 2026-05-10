package me.bmax.apatch.util

private val MODULE_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")
private val CALLBACK_REF_REGEX = Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,127}$")
private val REBOOT_REASON_REGEX = Regex("^[A-Za-z0-9_-]{1,32}$")
private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun shCommand(vararg args: String): String = args.joinToString(" ") { shQuote(it) }

fun shCommand(args: Iterable<String>): String = args.joinToString(" ") { shQuote(it) }

fun isSafeModuleId(value: String): Boolean = MODULE_ID_REGEX.matches(value)

fun isSafeCallbackRef(value: String): Boolean = CALLBACK_REF_REGEX.matches(value)

fun isSafeRebootReason(value: String): Boolean = value.isEmpty() || REBOOT_REASON_REGEX.matches(value)

fun isSafeEnvKey(value: String): Boolean = ENV_KEY_REGEX.matches(value)

fun containsUnsafeControlChars(value: String): Boolean = value.any { it.isISOControl() && it != '\n' && it != '\t' }
