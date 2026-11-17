package app.dnsflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dnsflow.core.NativeCore
import app.dnsflow.core.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Etapa 2: one-shot probe against the configured server. Inputs are not persisted yet. */
@Composable
fun ProbeScreen() {
    var server by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("53") }
    var tcp by rememberSaveable { mutableStateOf(false) }
    var domain by rememberSaveable { mutableStateOf("example.com") }
    var timeout by rememberSaveable { mutableStateOf("2000") }
    // Not saveable: the probe coroutine does not survive a configuration change.
    var running by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val timeoutMs = timeout.toIntOrNull()?.takeIf { it in 100..30000 }
    val canRun = !running && server.isNotBlank() && domain.isNotBlank() && portNum != null && timeoutMs != null

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("DnsFlow", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(),
                label = { Text("Servidor (IP o hostname)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                port, { port = it }, Modifier.fillMaxWidth(),
                label = { Text("Puerto") }, singleLine = true, isError = portNum == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!tcp, { tcp = false }, { Text("UDP") })
                FilterChip(tcp, { tcp = true }, { Text("TCP") })
            }
            OutlinedTextField(
                domain, { domain = it }, Modifier.fillMaxWidth(),
                label = { Text("Dominio de prueba") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                timeout, { timeout = it }, Modifier.fillMaxWidth(),
                label = { Text("Timeout (ms, 100–30000)") }, singleLine = true, isError = timeoutMs == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            NativeCore.probe(server, portNum!!, tcp, domain, timeoutMs!!)
                        }
                        result = describe(r)
                        running = false
                    }
                },
                enabled = canRun,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "Probando…" else "Probar servidor") }
            result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

private fun describe(r: ProbeResult): String = when (r) {
    is ProbeResult.Ok -> {
        val code = when (r.rcode) { 0 -> "NOERROR"; 3 -> "NXDOMAIN"; else -> "rcode ${r.rcode}" }
        "Responde: %.1f ms (%s)".format(r.rttMs, code)
    }
    is ProbeResult.Failed -> "No responde: ${r.reason}"
}
