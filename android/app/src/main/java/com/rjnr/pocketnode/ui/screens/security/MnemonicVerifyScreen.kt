package com.rjnr.pocketnode.ui.screens.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

data class QuizQuestion(val wordIndex: Int, val correctWord: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicVerifyScreen(
    mnemonicWords: List<String>,
    onVerified: () -> Unit,
    onBack: () -> Unit
) {
    val questions = remember(mnemonicWords) {
        mnemonicWords.indices.shuffled().take(3).sorted().map { idx ->
            QuizQuestion(wordIndex = idx, correctWord = mnemonicWords[idx])
        }
    }

    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val currentQuestion = questions.getOrNull(currentQuestionIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Recovery Phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    horizontal = com.rjnr.pocketnode.ui.util.responsiveDp(16.dp, 24.dp, 32.dp),
                    vertical = 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinearProgressIndicator(
                progress = { currentQuestionIndex / 3f },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Question ${currentQuestionIndex + 1} of 3",
                style = MaterialTheme.typography.titleMedium
            )

            if (currentQuestion != null) {
                Text(
                    text = "What is word #${currentQuestion.wordIndex + 1}?",
                    style = MaterialTheme.typography.headlineSmall
                )

                OutlinedTextField(
                    value = answer,
                    onValueChange = {
                        answer = it.lowercase().trim()
                        error = null
                    },
                    label = { Text("Enter word") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (answer.equals(currentQuestion.correctWord, ignoreCase = true)) {
                            answer = ""
                            error = null
                            if (currentQuestionIndex < 2) {
                                currentQuestionIndex++
                            } else {
                                onVerified()
                            }
                        } else {
                            error = "Incorrect. Try again."
                        }
                    },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (currentQuestionIndex < 2) "Next" else "Verify")
                }
            }
        }
    }
}
