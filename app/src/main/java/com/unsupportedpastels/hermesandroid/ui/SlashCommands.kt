package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy

/**
 * Slash-command predicates and completion application are decided in the
 * core. These wrappers
 * keep the existing call-site signatures.
 */
fun isModelPickerCommand(text: String): Boolean = SlashCommandPolicy.isModelPickerCommand(text)

fun isSteerCommand(text: String): Boolean = SlashCommandPolicy.isSteerCommand(text)

fun reasoningEffortCommand(text: String): String? = SlashCommandPolicy.reasoningEffortCommand(text)

fun isSlashCommandContext(text: String): Boolean = SlashCommandPolicy.isSlashCommandContext(text)

fun applySlashCompletion(
    current: String,
    item: SlashCompletionItem,
    replaceFrom: Int,
): String = SlashCommandPolicy.applySlashCompletion(current, item.text, replaceFrom)
