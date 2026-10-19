package com.lambdawalker.invitation

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private lateinit var vault: Vault
    private lateinit var local: LocalState
    private var families by mutableStateOf<List<Family>>(emptyList())
    private var connected by mutableStateOf(false)
    private var message by mutableStateOf("Conecta el teléfono desde el panel web.")
    private var running by mutableStateOf(false)
    private var batchActive by mutableStateOf(false)
    private var reconcileDialog by mutableStateOf(false)
    private var batchJob: Job? = null
    private var busy by mutableStateOf(false)
    private var pendingCount by mutableIntStateOf(0)
    private var sims by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    private var selectedSim by mutableIntStateOf(-1)
    private var query by mutableStateOf("")
    private var confirmBatch by mutableStateOf(false)
    private var whatsappFamily by mutableStateOf<Family?>(null)
    private var manualFamily by mutableStateOf<Family?>(null)
    private var online by mutableStateOf(false)
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadSims()
            message = "Permisos actualizados. Revisa la SIM antes de enviar."
        }

    private fun api() = Api(vault.base(), vault.token())

    private fun myId() = tokenHash(vault.token())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Vault(this)
        local = LocalState(this)
        local.recover()
        connected = vault.token().isNotBlank()
        try {
            val cached = JSONArray(local.cached())
            families = (0 until cached.length()).map { Family.from(cached.getJSONObject(it)) }
        } catch (_: Exception) {}
        pendingCount = local.pending().size
        loadSims()
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF183E32),
                        secondary = Color(0xFFA47D41),
                        background = Color(0xFFF5F1E6),
                        surface = Color(0xFFFFFDF6),
                    )
            ) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
        if (connected) task { refresh() }
    }

    override fun onPause() {
        super.onPause()
        if (running) {
            running = false
            message = "Envío pausado al salir de la app. Vuelve para continuar."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::local.isInitialized) {
            pendingCount = local.pending().size
            if (connected && !busy && !batchActive) task { refresh() }
        }
    }

    private fun task(block: suspend () -> Unit) {
        if (busy || batchActive) return
        busy = true
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                online = false
                message = e.message ?: "No se pudo completar la operación"
            } finally {
                busy = false
                pendingCount = local.pending().size
            }
        }
    }

    private suspend fun sync() {
        for (e in local.pending()) {
            val id = e.getString("id")
            val remote = api().families().find { it.id == id }
            check(remote != null) { "Familia no encontrada" }
            if (remote.attemptId != e.getString("attemptId")) {
                message =
                    "Cambio local pendiente de revisión: ${remote.name}. No se reenviará automáticamente."
                continue
            }
            if (SendPolicy.superseded(remote.status, e.getString("status"))) {
                local.acknowledge(e.getString("eventId"))
                continue
            }
            api()
                .action(
                    id,
                    "result",
                    attemptId = e.getString("attemptId"),
                    status = e.getString("status"),
                    detail = e.optString("detail"),
                )
            local.acknowledge(e.getString("eventId"))
        }
        pendingCount = local.pending().size
    }

    private suspend fun refresh() {
        sync()
        families = api().families()
        local.cache(JSONArray(families.map { it.toJson() }).toString())
        online = true
        message = "${families.size} familias · información actualizada"
    }

    private fun loadSims() {
        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        try {
            sims =
                getSystemService(SubscriptionManager::class.java)
                    .activeSubscriptionInfoList
                    .orEmpty()
                    .map { it.subscriptionId to "${it.displayName} · SIM ${it.simSlotIndex+1}" }
            if (sims.size == 1) selectedSim = sims.first().first
        } catch (e: Exception) {
            message = "No se pudo leer la SIM: ${e.message}"
        }
    }

    private fun smsManager(): SmsManager {
        check(selectedSim >= 0) { "Selecciona una SIM" }
        return if (android.os.Build.VERSION.SDK_INT >= 31)
            getSystemService(SmsManager::class.java).createForSubscriptionId(selectedSim)
        else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(selectedSim)
    }

    private fun shortMessage(f: Family) =
        "Abi cumple 2! Te esperamos con tu familia el 24/oct a las 4 pm. ${vault.base()}/?i=${f.id}"

    private suspend fun claim(f: Family) {
        api().action(f.id, "claim")
        refresh()
    }

    private suspend fun openWhatsApp(f: Family) {
        check(online) { "Actualiza primero la lista" }
        check(f.assignedTo == myId()) { "Asigna esta familia a tu teléfono primero" }
        val attempt = UUID.randomUUID().toString()
        local.prepare(f.id, attempt, "whatsapp")
        api().action(f.id, "begin", "whatsapp", attempt)
        local.handoff(f.id)
        families = api().families()
        whatsappFamily = f
        val url =
            "https://wa.me/${f.phone.filter{it.isDigit()}}?text=${Uri.encode(invitationMessage(f,vault.base()))}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            message = "No se pudo abrir WhatsApp. Instálalo o usa SMS."
        }
    }

    private suspend fun confirmWhatsApp(f: Family) {
        val attempt = local.attempt(f.id).ifBlank { f.attemptId }
        if (local.attempt(f.id).isBlank()) local.prepare(f.id, attempt, "whatsapp")
        local.record(f.id, attempt, "manual_sent")
        sync()
        refresh()
        whatsappFamily = null
    }

    private fun callback(f: Family, attempt: String, delivered: Boolean): PendingIntent {
        val intent =
            Intent(this, SmsResultReceiver::class.java)
                .setAction(
                    if (delivered) "com.lambdawalker.invitation.DELIVERED"
                    else "com.lambdawalker.invitation.SENT"
                )
                .setData(Uri.parse("abi://sms/$attempt/${if(delivered)"delivery" else "sent"}"))
                .putExtra("family", f.id)
                .putExtra("attempt", attempt)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun sendOne(f: Family) {
        check(
            checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            "Autoriza el permiso SMS"
        }
        val manager = smsManager()
        val text = shortMessage(f)
        check(manager.divideMessage(text).size == 1) {
            "El mensaje supera un SMS. Usa WhatsApp para esta familia."
        }
        val attempt = UUID.randomUUID().toString()
        local.prepare(f.id, attempt, "sms")
        try {
            api().action(f.id, "begin", "sms", attempt)
        } catch (e: Exception) {
            message = "No se confirmó el inicio. Actualiza y revisa el intento antes de enviar."
            throw e
        }
        if (
            !SendPolicy.mayDispatch(
                running,
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),
            )
        ) {
            local.record(f.id, attempt, "failed", "Pausado antes de transmitir; no se envió SMS")
            sync()
            return
        }
        local.dispatching(f.id)
        try {
            manager.sendTextMessage(
                f.phone,
                null,
                text,
                callback(f, attempt, false),
                callback(f, attempt, true),
            )
        } catch (e: Exception) {
            local.record(
                f.id,
                attempt,
                "uncertain",
                e.message?.take(180) ?: "No se confirmó el envío",
            )
            throw e
        }
        repeat(30) {
            if (local.state(f.id) !in listOf("dispatching", "prepared")) return@repeat
            delay(1000)
        }
        if (local.state(f.id) in listOf("dispatching", "prepared"))
            local.record(f.id, attempt, "uncertain", "Sin confirmación de Android en 30 segundos")
        sync()
        check(local.state(f.id) in listOf("sent", "delivered")) {
            "Envío detenido: revisa el resultado de ${f.name}"
        }
    }

    private fun startBatch() {
        if (busy || running || batchJob?.isActive == true) return
        running = true
        batchActive = true
        batchJob =
            lifecycleScope.launch {
                try {
                    refresh()
                    val queue =
                        families.filter {
                            SendPolicy.canSend(
                                it.status,
                                it.assignedTo,
                                myId(),
                                local.blocked(it.id),
                            )
                        }
                    for ((i, f) in queue.withIndex()) {
                        if (!running) break
                        message = "Enviando ${i+1}/${queue.size}: ${f.name}"
                        sendOne(f)
                        families = api().families()
                        if (i < queue.lastIndex) delay(10000)
                    }
                    if (running) message = "Lote completado"
                } catch (e: Exception) {
                    message = e.message ?: "Envío pausado"
                } finally {
                    running = false
                    batchActive = false
                    pendingCount = local.pending().size
                }
            }
    }

    @Composable
    private fun Screen() {
        if (!connected) {
            var base by remember { mutableStateOf("https://xitlaliabigail.com") }
            var token by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("ABI · 2 AÑOS", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(20.dp))
                Text(
                    "Tu teléfono,\nparte de la aldea.",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(25.dp))
                Text(
                    "En el sitio abre Envío de invitaciones → Conectar teléfono. Copia aquí la credencial."
                )
                OutlinedTextField(
                    base,
                    { base = it },
                    label = { Text("Servidor HTTPS") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Credencial del dispositivo") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        task {
                            val normalized = base.trim().trimEnd('/')
                            val clean = token.trim()
                            val list = Api(normalized, clean).families()
                            local.clear()
                            vault.save(normalized, clean)
                            families = list
                            connected = true
                            online = true
                            message = "Teléfono conectado"
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Conectar")
                }
                Text(message, Modifier.padding(top = 16.dp))
            }
            return
        }
        LazyColumn(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 35.dp, bottom = 40.dp),
        ) {
            item {
                Text("ABI · INVITACIONES", style = MaterialTheme.typography.labelLarge)
                Text("La lista de la aldea", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "${families.count{it.assignedTo==myId()}} asignadas a este teléfono · $pendingCount cambios por sincronizar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Card { Text(message, Modifier.padding(14.dp)) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { task { refresh() } },
                        enabled = !busy && !batchActive,
                    ) {
                        Text("Actualizar")
                    }
                    OutlinedButton(
                        onClick = {
                            permissions.launch(
                                arrayOf(
                                    Manifest.permission.SEND_SMS,
                                    Manifest.permission.READ_PHONE_STATE,
                                )
                            )
                        },
                        enabled = !batchActive,
                    ) {
                        Text("Permisos SMS")
                    }
                }
            }
            item {
                sims.forEach { sim ->
                    Row {
                        RadioButton(
                            selected = sim.first == selectedSim,
                            onClick = { if (!batchActive) selectedSim = sim.first },
                        )
                        Text(sim.second, Modifier.padding(top = 13.dp))
                    }
                }
            }
            item {
                Text(
                    "Mantén la app abierta durante el lote. SMS: un mensaje corto por familia, con pausa de 10 segundos. Las respuestas llegan a tu app habitual de mensajes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (batchActive) running = false else confirmBatch = true },
                        enabled = !busy,
                    ) {
                        Text(if (batchActive) "Pausar" else "Enviar SMS pendientes")
                    }
                    OutlinedButton(
                        onClick = {
                            task {
                                for (f in
                                    families.filter {
                                        it.assignedTo.isBlank() && it.status == "pending"
                                    }) api().action(f.id, "claim")
                                refresh()
                            }
                        },
                        enabled = !busy && !batchActive,
                    ) {
                        Text("Asignarme pendientes")
                    }
                }
            }
            item {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Buscar familia") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(
                families.filter { it.name.contains(query, true) || it.phone.contains(query) },
                key = { it.id },
            ) { f ->
                FamilyCard(f)
            }
            if (pendingCount > 0)
                item {
                    OutlinedButton(
                        onClick = { reconcileDialog = true },
                        enabled = !busy && !batchActive,
                    ) {
                        Text("Revisar cambios pendientes")
                    }
                }
            item {
                OutlinedButton(
                    onClick = {
                        if (local.pending().isNotEmpty()) {
                            message = "Sincroniza los cambios antes de desconectar"
                        } else {
                            vault.clear()
                            local.clear()
                            connected = false
                            families = emptyList()
                        }
                    },
                    enabled = !batchActive && !busy,
                ) {
                    Text("Desconectar este teléfono")
                }
            }
        }
        if (reconcileDialog)
            AlertDialog(
                onDismissRequest = { reconcileDialog = false },
                title = { Text("Reconciliar cambios antiguos") },
                text = {
                    Text(
                        "Primero compara los mensajes en tu teléfono con el reporte web. Se archivarán solo eventos cuyo intento ya no coincide con el servidor. No se reenviará nada ni se eliminará el historial local."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            task {
                                val remote = api().families()
                                var count = 0
                                for (event in local.pending()) {
                                    val f = remote.find { it.id == event.getString("id") }
                                    if (
                                        f != null &&
                                            SendPolicy.canDiscard(
                                                event.getString("attemptId"),
                                                f.attemptId,
                                            )
                                    ) {
                                        local.archiveStale(event.getString("eventId"))
                                        count++
                                    }
                                }
                                reconcileDialog = false
                                refresh()
                                message =
                                    "$count cambios antiguos archivados; revisa antes de aceptar un reinicio."
                            }
                        }
                    ) {
                        Text("Ya revisé; reconciliar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { reconcileDialog = false }) { Text("Cancelar") }
                },
            )
        if (confirmBatch)
            AlertDialog(
                onDismissRequest = { confirmBatch = false },
                title = { Text("¿Enviar invitaciones por SMS?") },
                text = {
                    Text(
                        "Se enviarán las familias pendientes asignadas a este teléfono usando la SIM seleccionada. Pueden aplicarse cargos de tu operador. Primero prueba con una familia. Ejemplo:\n\n${families.firstOrNull()?.let{shortMessage(it)}?:"Sin familias"}"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmBatch = false
                            startBatch()
                        }
                    ) {
                        Text("Comenzar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBatch = false }) { Text("Cancelar") }
                },
            )
        whatsappFamily?.let { f ->
            AlertDialog(
                onDismissRequest = { whatsappFamily = null },
                title = { Text("¿Enviaste la invitación?") },
                text = {
                    Text(
                        "Abrir WhatsApp no confirma el envío a ${f.name}. Márcalo solo si tocaste Enviar."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { task { confirmWhatsApp(f) } }) { Text("Ya lo envié") }
                },
                dismissButton = {
                    TextButton(onClick = { whatsappFamily = null }) { Text("Dejar pendiente") }
                },
            )
        }
        manualFamily?.let { f ->
            AlertDialog(
                onDismissRequest = { manualFamily = null },
                title = { Text("Verificar SMS") },
                text = {
                    Text(
                        "Revisa tu app de mensajes. Si aparece enviado, confirma. Si no puedes comprobarlo, conserva el estado por verificar. Para reintentar usa Preparar reenvío en la web."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            task {
                                if (local.attempt(f.id).isBlank())
                                    local.prepare(f.id, f.attemptId, "sms")
                                local.record(
                                    f.id,
                                    f.attemptId,
                                    "manual_sent",
                                    "Confirmado manualmente en el teléfono",
                                )
                                sync()
                                manualFamily = null
                                refresh()
                            }
                        }
                    ) {
                        Text("Confirmo que se envió")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { manualFamily = null }) { Text("Conservar pendiente") }
                },
            )
        }
    }

    @Composable
    private fun FamilyCard(f: Family) {
        val mine = f.assignedTo == myId()
        val enabled = !batchActive && !busy
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.name, fontWeight = FontWeight.Bold)
                Text(f.phone)
                Text(
                    "Envío: ${label(f.status)} · Asistencia: ${label(f.rsvp)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Asignación: ${f.assignedName.ifBlank{"Sin asignar"}}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (f.assignedTo.isBlank())
                        TextButton(onClick = { task { claim(f) } }, enabled = enabled) {
                            Text("Asignarme")
                        }
                    if (mine) {
                        TextButton(
                            onClick = {
                                task {
                                    api().action(f.id, "release")
                                    refresh()
                                }
                            },
                            enabled = enabled && f.status != "sending",
                        ) {
                            Text("Liberar")
                        }
                        if (mine && f.status == "pending")
                            TextButton(
                                onClick = {
                                    task {
                                        if (local.blocked(f.id)) {
                                            check(
                                                !local.pending().any { it.getString("id") == f.id }
                                            ) {
                                                "Sincroniza antes"
                                            }
                                            local.allowReset(f.id)
                                        }
                                        openWhatsApp(f)
                                    }
                                },
                                enabled = enabled,
                            ) {
                                Text("WhatsApp")
                            }
                    }
                }
                if (mine && f.status == "whatsapp_opened")
                    TextButton(onClick = { whatsappFamily = f }, enabled = enabled) {
                        Text("Confirmar envío WhatsApp")
                    }
                if (mine && f.status in listOf("uncertain", "sending"))
                    TextButton(onClick = { manualFamily = f }, enabled = enabled) {
                        Text("Verificar SMS")
                    }
                if (mine && f.status == "pending" && local.blocked(f.id))
                    TextButton(
                        onClick = {
                            if (local.pending().none { it.getString("id") == f.id }) {
                                local.allowReset(f.id)
                                message = "Reenvío habilitado tras el reinicio realizado en la web"
                            }
                        },
                        enabled = enabled,
                    ) {
                        Text("Aceptar reinicio de la web")
                    }
            }
        }
    }

    private fun label(s: String) =
        mapOf(
            "pending" to "Pendiente",
            "sending" to "En curso",
            "sent" to "Enviado",
            "delivered" to "Entregado",
            "manual_sent" to "Enviado (manual)",
            "whatsapp_opened" to "WhatsApp abierto",
            "uncertain" to "Por verificar",
            "failed" to "Falló",
            "yes" to "Sí asistirán",
            "no" to "No asistirán",
        )[s] ?: s
}
