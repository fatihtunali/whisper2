package com.whisper2.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Dialog to start a new chat with a WhisperID
 */
@Composable
fun NewChatDialog(
    onDismiss: () -> Unit,
    onStartChat: (String) -> Unit
) {
    var whisperId by remember { mutableStateOf("") }
    var isValid by remember { mutableStateOf(false) }

    // Basic WhisperID validation (WSP-XXXX-XXXX-XXXX format)
    fun validateWhisperId(id: String): Boolean {
        val pattern = Regex("^WSP-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
        return pattern.matches(id.uppercase())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Chat") },
        text = {
            Column {
                Text(
                    text = "Enter the recipient's WhisperID",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = whisperId,
                    onValueChange = { input ->
                        // Auto-format: uppercase and add dashes
                        val formatted = formatWhisperId(input)
                        whisperId = formatted
                        isValid = validateWhisperId(formatted)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("WhisperID") },
                    placeholder = { Text("WSP-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (isValid) {
                                onStartChat(whisperId.uppercase())
                            }
                        }
                    ),
                    isError = whisperId.isNotEmpty() && !isValid && whisperId.length >= 19,
                    supportingText = {
                        if (whisperId.isNotEmpty() && !isValid && whisperId.length >= 19) {
                            Text("Invalid WhisperID format")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStartChat(whisperId.uppercase()) },
                enabled = isValid
            ) {
                Text("Start Chat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Format input as WhisperID (WSP-XXXX-XXXX-XXXX)
 */
private fun formatWhisperId(input: String): String {
    // Remove all non-alphanumeric except dash
    val clean = input.uppercase().filter { it.isLetterOrDigit() || it == '-' }

    // If already has dashes, keep as is
    if (clean.contains("-")) return clean.take(19)

    // Auto-format without dashes
    val withoutPrefix = if (clean.startsWith("WSP")) clean.drop(3) else clean
    val digits = withoutPrefix.filter { it.isLetterOrDigit() }

    return buildString {
        append("WSP")
        if (digits.isNotEmpty()) {
            append("-")
            append(digits.take(4))
        }
        if (digits.length > 4) {
            append("-")
            append(digits.drop(4).take(4))
        }
        if (digits.length > 8) {
            append("-")
            append(digits.drop(8).take(4))
        }
    }
}
