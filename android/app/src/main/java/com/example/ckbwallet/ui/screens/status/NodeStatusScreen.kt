package com.example.ckbwallet.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeStatusScreen(
    navController: NavController,
    viewModel: NodeStatusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Status", "Logs")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Node Status") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> StatusTab(uiState, onCallRpc = { viewModel.callRpc(it) })
                1 -> LogsTab(uiState.logs)
            }
        }
    }
}

@Composable
fun StatusTab(uiState: NodeStatusUiState, onCallRpc: (String) -> Unit) {
    var rpcMethod by remember { mutableStateOf("get_peers") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        StatusSection(title = "Tip Header", content = uiState.tipHeaderJson)
        StatusSection(title = "Peers", content = uiState.peersJson)
        StatusSection(title = "Scripts", content = uiState.scriptsJson)
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Custom RPC", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rpcMethod,
                onValueChange = { rpcMethod = it },
                label = { Text("Method") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { onCallRpc(rpcMethod) }) {
                Text("Call")
            }
        }
        if (uiState.rpcResult.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = uiState.rpcResult,
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun StatusSection(title: String, content: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = content, 
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun LogsTab(logs: List<String>) {
    val listState = rememberLazyListState()
    
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        items(logs) { log ->
            Text(
                text = log,
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
