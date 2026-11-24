package com.mixcom.distributor

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// === இங்கே உங்கள் Backend URL மற்றும் Public Key-ஐ மாற்றவும் ===
const val BACKEND_BASE_URL = "https://YOUR_PROJECT_ID.supabase.co"
const val BACKEND_PUBLIC_KEY = "YOUR_SUPABASE_ANON_KEY"
const val DEFAULT_DISTRIBUTOR_ID = "MXD-200001"

val RoyalBlue = Color(0xFF1D3587)
val DeepBlue = Color(0xFF0E236B)
val BrandRed = Color(0xFFD32F2F)
val SoftBg = Color(0xFFF5F6FA)
val VipGold = Color(0xFFD4AF37)
val SuccessGreen = Color(0xFF2E7D32)
val PendingAmber = Color(0xFFEF6C00)

fun hashSecret(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun generateAgentIdFromPhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    val tail = if (digits.length >= 4) digits.takeLast(4) else (1000..9999).random().toString()
    val rnd = (10..99).random()
    return "MXA-$tail$rnd"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DistributorAppRoot()
            }
        }
    }
}

data class OnboardedAgent(
    val agentId: String,
    val agentName: String,
    val shopName: String,
    val phone: String,
    val city: String,
    val kycStatus: String
)

data class CommissionEntry(
    val id: String,
    val agentId: String,
    val serviceName: String,
    val amount: Double,
    val dateText: String
)

data class DistributorProfile(
    val distributorId: String,
    val fullName: String,
    val phone: String,
    val city: String,
    val walletBalance: Double = 0.0,
    val totalCommission: Double = 0.0
)

object DistributorBackend {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun restReq(path: String): Request.Builder {
        return Request.Builder()
            .url("$BACKEND_BASE_URL/rest/v1/$path")
            .addHeader("apikey", BACKEND_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $BACKEND_PUBLIC_KEY")
            .addHeader("Content-Type", "application/json")
    }

    suspend fun ensureDistributorRow(
        distributorId: String,
        fullName: String,
        phone: String,
        city: String
    ): DistributorProfile = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("distributor_id", distributorId)
                .put("full_name", fullName)
                .put("phone", phone)
                .put("city", city)
                .toString()

            val postReq = restReq("distributors?on_conflict=distributor_id")
                .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(postReq).execute().close()

            val getReq = restReq("distributors?distributor_id=eq.$distributorId&select=*").get().build()
            client.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        return@withContext DistributorProfile(
                            distributorId = o.optString("distributor_id", distributorId),
                            fullName = o.optString("full_name", fullName),
                            phone = o.optString("phone", phone),
                            city = o.optString("city", city),
                            walletBalance = o.optDouble("wallet_balance", 0.0),
                            totalCommission = o.optDouble("total_commission", 0.0)
                        )
                    }
                }
            }
        } catch (_: Exception) { }
        DistributorProfile(distributorId, fullName, phone, city)
    }

    suspend fun fetchMyAgents(distributorId: String): List<OnboardedAgent> = withContext(Dispatchers.IO) {
        try {
            val req = restReq(
                "agent_security?distributor_id=eq.$distributorId&select=user_id,agent_name,shop_name,phone,city,kyc_status"
            ).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val out = mutableListOf<OnboardedAgent>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val aid = o.optString("user_id", "")
                    if (aid.isNotBlank()) {
                        out.add(
                            OnboardedAgent(
                                agentId = aid,
                                agentName = o.optString("agent_name", "Agent").ifBlank { "Agent" },
                                shopName = o.optString("shop_name", "MiXCOM Point").ifBlank { "MiXCOM Point" },
                                phone = o.optString("phone", "-").ifBlank { "-" },
                                city = o.optString("city", "Kumbakonam").ifBlank { "Kumbakonam" },
                                kycStatus = o.optString("kyc_status", "PENDING").ifBlank { "PENDING" }
                            )
                        )
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun onboardNewAgent(
        distributorId: String,
        agent: OnboardedAgent
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val secPayload = JSONObject()
                .put("user_id", agent.agentId)
                .put("distributor_id", distributorId)
                .put("agent_name", agent.agentName)
                .put("shop_name", agent.shopName)
                .put("phone", agent.phone)
                .put("city", agent.city)
                .put("kyc_status", agent.kycStatus)
                .toString()

            val secReq = restReq("agent_security?on_conflict=user_id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(secPayload.toRequestBody(jsonMediaType))
                .build()
            val secOk = client.newCall(secReq).execute().use { it.isSuccessful }

            val walletPayload = JSONObject()
                .put("user_id", agent.agentId)
                .put("balance", 0.0)
                .toString()

            val walletReq = restReq("wallets?on_conflict=user_id")
                .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(walletPayload.toRequestBody(jsonMediaType))
                .build()
            val walletOk = client.newCall(walletReq).execute().use { it.isSuccessful }

            secOk && walletOk
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchCommissions(distributorId: String): List<CommissionEntry> = withContext(Dispatchers.IO) {
        try {
            val req = restReq(
                "distributor_commissions?distributor_id=eq.$distributorId&order=created_at.desc&limit=30"
            ).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val list = mutableListOf<CommissionEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        CommissionEntry(
                            id = o.optLong("id", i.toLong()).toString(),
                            agentId = o.optString("agent_id", "-"),
                            serviceName = o.optString("service_name", "Service"),
                            amount = o.optDouble("commission_amount", 0.0),
                            dateText = o.optString("created_at", "Recent").take(16).replace("T", " | ")
                        )
                    )
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun recordCommissionAndUpdateTotals(
        distributorId: String,
        agentId: String,
        serviceName: String,
        commissionAmt: Double,
        newTotalComm: Double,
        newWalletBal: Double
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val commPayload = JSONObject()
                .put("distributor_id", distributorId)
                .put("agent_id", agentId)
                .put("service_name", serviceName)
                .put("commission_amount", commissionAmt)
                .put("status", "CREDITED")
                .toString()

            val insReq = restReq("distributor_commissions")
                .addHeader("Prefer", "return=minimal")
                .post(commPayload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(insReq).execute().close()

            val updPayload = JSONObject()
                .put("total_commission", newTotalComm)
                .put("wallet_balance", newWalletBal)
                .toString()

            val patchReq = restReq("distributors?distributor_id=eq.$distributorId")
                .patch(updPayload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(patchReq).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun DistributorAppRoot() {
    val context = LocalContext.current
    var isLoggedIn by remember { mutableStateOf(false) }
    var isUnlocked by remember { mutableStateOf(false) }

    var distributorId by remember { mutableStateOf(DEFAULT_DISTRIBUTOR_ID) }
    var distributorName by remember { mutableStateOf("MiXCOM Distributor") }
    var distributorPhone by remember { mutableStateOf("") }
    var distributorCity by remember { mutableStateOf("Kumbakonam") }
    var savedMpinHash by remember { mutableStateOf<String?>(null) }

    if (!isLoggedIn) {
        DistributorLoginScreen(
            initialId = distributorId,
            onLogin = { dId, dName, dPhone, dCity ->
                distributorId = dId
                distributorName = dName
                distributorPhone = dPhone
                distributorCity = dCity
                isUnlocked = false
                isLoggedIn = true
            }
        )
        return
    }

    if (!isUnlocked) {
        DistributorMpinGate(
            distributorId = distributorId,
            savedMpinHash = savedMpinHash,
            onSetMpin = { hash ->
                savedMpinHash = hash
                isUnlocked = true
                Toast.makeText(context, "MPIN set for $distributorId", Toast.LENGTH_SHORT).show()
            },
            onUnlocked = { isUnlocked = true }
        )
        return
    }

    DistributorHomeShell(
        profileSeed = DistributorProfile(distributorId, distributorName, distributorPhone, distributorCity),
        onLock = { isUnlocked = false },
        onLogout = {
            isUnlocked = false
            isLoggedIn = false
        }
    )
}

@Composable
fun DistributorLoginScreen(
    initialId: String,
    onLogin: (String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    var distId by remember { mutableStateOf(initialId) }
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("Kumbakonam") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = VipGold, shape = RoundedCornerShape(6.dp)) {
            Text(
                "MIXCOM DISTRIBUTOR PORTAL",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("MiXCOM Distributor", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Onboard agents, track KYC & manage commissions",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = distId,
                    onValueChange = { distId = it.uppercase().trim() },
                    label = { Text("Distributor ID (e.g. MXD-200001)") },
                    leadingIcon = { Icon(Icons.Outlined.Badge, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Distributor Name") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) phone = it },
                    label = { Text("Mobile Number") },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("Territory / City") },
                    leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val cleanId = distId.trim().ifBlank { DEFAULT_DISTRIBUTOR_ID }
                        val cleanName = fullName.trim().ifBlank { "Distributor ($cleanId)" }
                        if (phone.length < 10) {
                            Toast.makeText(context, "Enter 10-digit mobile number", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onLogin(cleanId, cleanName, phone.trim(), city.trim().ifBlank { "Kumbakonam" })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Continue to Distributor Portal", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun DistributorMpinGate(
    distributorId: String,
    savedMpinHash: String?,
    onSetMpin: (String) -> Unit,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    var mpin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val isSetting = savedMpinHash.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = VipGold, shape = RoundedCornerShape(6.dp)) {
            Text(
                "ID: $distributorId",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isSetting) "Create 6-Digit Distributor MPIN" else "Enter 6-Digit MPIN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = RoyalBlue
                )
                OutlinedTextField(
                    value = mpin,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) mpin = it },
                    label = { Text("6-Digit MPIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isSetting) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirm = it },
                        label = { Text("Confirm 6-Digit MPIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = {
                        if (mpin.length != 6) {
                            Toast.makeText(context, "Enter 6-digit MPIN", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isSetting) {
                            if (mpin != confirm) {
                                Toast.makeText(context, "MPIN does not match", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSetMpin(hashSecret(mpin))
                        } else {
                            if (hashSecret(mpin) == savedMpinHash) {
                                onUnlocked()
                            } else {
                                Toast.makeText(context, "Wrong MPIN", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (isSetting) "Save MPIN" else "Unlock", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistributorHomeShell(
    profileSeed: DistributorProfile,
    onLock: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf(profileSeed) }
    var isSyncing by remember { mutableStateOf(false) }
    var showOnboardSheet by remember { mutableStateOf(false) }

    val agents = remember { mutableStateListOf<OnboardedAgent>() }
    val commissions = remember { mutableStateListOf<CommissionEntry>() }

    fun refreshDistributorData(showToast: Boolean = false) {
        scope.launch {
            isSyncing = true
            val prof = DistributorBackend.ensureDistributorRow(
                profileSeed.distributorId,
                profileSeed.fullName,
                profileSeed.phone,
                profileSeed.city
            )
            val remoteAgents = DistributorBackend.fetchMyAgents(profileSeed.distributorId)
            val remoteComms = DistributorBackend.fetchCommissions(profileSeed.distributorId)

            profile = prof
            agents.clear()
            agents.addAll(remoteAgents)
            commissions.clear()
            commissions.addAll(remoteComms)
            isSyncing = false
            if (showToast) {
                Toast.makeText(context, "Distributor data synced", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(profileSeed.distributorId) {
        refreshDistributorData(showToast = false)
    }

    if (showOnboardSheet) {
        OnboardAgentDialog(
            onDismiss = { showOnboardSheet = false },
            onSubmit = { newAgent ->
                showOnboardSheet = false
                agents.add(0, newAgent)
                scope.launch {
                    val saved = DistributorBackend.onboardNewAgent(profile.distributorId, newAgent)
                    Toast.makeText(
                        context,
                        if (saved) "Onboarded ${newAgent.agentId} & synced to cloud"
                        else "Added ${newAgent.agentId} locally (check cloud)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    Scaffold(
        containerColor = SoftBg,
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showOnboardSheet = true },
                    containerColor = BrandRed,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Onboard Agent")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
                    label = { Text("Overview", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                    label = { Text("Agents (${agents.size})", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Outlined.Payments, contentDescription = null) },
                    label = { Text("Commission", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    label = { Text("Profile", fontSize = 11.sp) }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad)) {
            when (selectedTab) {
                0 -> DistributorOverviewTab(
                    profile = profile,
                    agents = agents,
                    isSyncing = isSyncing,
                    onRefresh = { refreshDistributorData(showToast = true) },
                    onOnboardClick = { showOnboardSheet = true },
                    onViewAgentsClick = { selectedTab = 1 }
                )
                1 -> AgentsDirectoryTab(
                    agents = agents,
                    onOnboardClick = { showOnboardSheet = true }
                )
                2 -> CommissionsTab(
                    profile = profile,
                    agents = agents,
                    commissions = commissions,
                    onLogCommission = { agentId, serviceName, commAmt ->
                        val updatedComm = profile.totalCommission + commAmt
                        val updatedBal = profile.walletBalance + commAmt
                        profile = profile.copy(
                            totalCommission = updatedComm,
                            walletBalance = updatedBal
                        )
                        commissions.add(
                            0,
                            CommissionEntry(
                                id = (10000..99999).random().toString(),
                                agentId = agentId,
                                serviceName = serviceName,
                                amount = commAmt,
                                dateText = "Just now"
                            )
                        )
                        scope.launch {
                            DistributorBackend.recordCommissionAndUpdateTotals(
                                distributorId = profile.distributorId,
                                agentId = agentId,
                                serviceName = serviceName,
                                commissionAmt = commAmt,
                                newTotalComm = updatedComm,
                                newWalletBal = updatedBal
                            )
                        }
                    }
                )
                3 -> DistributorProfileTab(
                    profile = profile,
                    onLock = onLock,
                    onLogout = onLogout
                )
            }
        }
    }
}

@Composable
fun DistributorOverviewTab(
    profile: DistributorProfile,
    agents: List<OnboardedAgent>,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onOnboardClick: () -> Unit,
    onViewAgentsClick: () -> Unit
) {
    val verifiedCount = agents.count { it.kycStatus.equals("VERIFIED", ignoreCase = true) }
    val pendingCount = agents.size - verifiedCount

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(profile.distributorId, color = VipGold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        Text(profile.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            if (isSyncing) "Syncing with cloud..." else "${profile.city} • ${profile.phone}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Distributor Wallet", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("₹ ${"%.2f".format(profile.walletBalance)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Total Commission", color = VipGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("₹ ${"%.2f".format(profile.totalCommission)}", color = VipGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile("Total Agents", agents.size.toString(), RoyalBlue, Modifier.weight(1f))
                MetricTile("KYC Verified", verifiedCount.toString(), SuccessGreen, Modifier.weight(1f))
                MetricTile("KYC Pending", pendingCount.toString(), PendingAmber, Modifier.weight(1f))
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quick Actions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RoyalBlue)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOnboardClick,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Icon(Icons.Outlined.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Onboard Agent", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onViewAgentsClick,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Text("View Agents", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = color)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun AgentsDirectoryTab(
    agents: List<OnboardedAgent>,
    onOnboardClick: () -> Unit
) {
    var filter by remember { mutableStateOf("ALL") }
    var search by remember { mutableStateOf("") }

    val filtered = agents.filter { a ->
        val matchesFilter = when (filter) {
            "VERIFIED" -> a.kycStatus.equals("VERIFIED", ignoreCase = true)
            "PENDING" -> !a.kycStatus.equals("VERIFIED", ignoreCase = true)
            else -> true
        }
        val q = search.trim().lowercase()
        val matchesQuery = q.isBlank() ||
            a.agentId.lowercase().contains(q) ||
            a.agentName.lowercase().contains(q) ||
            a.shopName.lowercase().contains(q) ||
            a.phone.contains(q)
        matchesFilter && matchesQuery
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalBlue)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("My Agents (${agents.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                TextButton(onClick = onOnboardClick) {
                    Text("+ Add Agent", color = VipGold, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search Agent ID, name, shop, mobile...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "VERIFIED", "PENDING").forEach { status ->
                    FilterChip(
                        selected = filter == status,
                        onClick = { filter = status },
                        label = { Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No agents found. Tap + Add Agent to onboard a new shop agent.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered) { ag ->
                    val isVerified = ag.kycStatus.equals("VERIFIED", ignoreCase = true)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ag.agentId, color = BrandRed, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(ag.agentName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${ag.shopName} • ${ag.city}", color = Color.Gray, fontSize = 12.sp)
                                Text("Mob: ${ag.phone}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Surface(
                                color = if (isVerified) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (isVerified) "VERIFIED" else "KYC PENDING",
                                    color = if (isVerified) SuccessGreen else PendingAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommissionsTab(
    profile: DistributorProfile,
    agents: List<OnboardedAgent>,
    commissions: List<CommissionEntry>,
    onLogCommission: (agentId: String, serviceName: String, commissionAmt: Double) -> Unit
) {
    val context = LocalContext.current
    var selectedAgentId by remember { mutableStateOf(agents.firstOrNull()?.agentId ?: "MXA-100001") }
    var serviceName by remember { mutableStateOf("AEPS / BBPS") }
    var commInput by remember { mutableStateOf("25.00") }

    LaunchedEffect(agents) {
        if (agents.isNotEmpty() && agents.none { it.agentId == selectedAgentId }) {
            selectedAgentId = agents.first().agentId
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Record Commission Entry", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RoyalBlue)
                    Text("Current Wallet: ₹${"%.2f".format(profile.walletBalance)}", fontSize = 12.sp, color = Color.Gray)

                    OutlinedTextField(
                        value = selectedAgentId,
                        onValueChange = { selectedAgentId = it.uppercase().trim() },
                        label = { Text("Agent ID (e.g. MXA-...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = serviceName,
                        onValueChange = { serviceName = it },
                        label = { Text("Service (AEPS / DMT / BBPS / GOLD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = commInput,
                        onValueChange = { commInput = it },
                        label = { Text("Commission Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val amt = commInput.toDoubleOrNull() ?: 0.0
                            if (selectedAgentId.isBlank() || amt <= 0.0) {
                                Toast.makeText(context, "Enter Agent ID and valid amount", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onLogCommission(selectedAgentId, serviceName.ifBlank { "Service" }, amt)
                            Toast.makeText(context, "Commission ₹$amt credited", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Credit Commission", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(commissions) { c ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("${c.serviceName} • ${c.agentId}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(c.dateText, color = Color.Gray, fontSize = 11.sp)
                    }
                    Text(
                        "+ ₹${"%.2f".format(c.amount)}",
                        color = SuccessGreen,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DistributorProfileTab(
    profile: DistributorProfile,
    onLock: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(color = RoyalBlue, shape = RoundedCornerShape(10.dp)) {
            Text(
                "MiXCOM Distributor",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Distributor ID: ${profile.distributorId}", fontWeight = FontWeight.ExtraBold, color = BrandRed)
                Text("Name: ${profile.fullName}", fontSize = 14.sp)
                Text("Phone: ${profile.phone}", fontSize = 13.sp, color = Color.Gray)
                Text("City: ${profile.city}", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Button(
            onClick = onLock,
            colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Lock App")
        }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = BrandRed)
            Spacer(Modifier.width(8.dp))
            Text("Sign Out", color = BrandRed, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun OnboardAgentDialog(
    onDismiss: () -> Unit,
    onSubmit: (OnboardedAgent) -> Unit
) {
    val context = LocalContext.current
    var agentName by remember { mutableStateOf("") }
    var shopName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("Kumbakonam") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Onboard New Agent", fontWeight = FontWeight.Bold, color = RoyalBlue) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = agentName,
                    onValueChange = { agentName = it },
                    label = { Text("Agent Full Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = shopName,
                    onValueChange = { shopName = it },
                    label = { Text("Shop / e-Sevai Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) phone = it },
                    label = { Text("Mobile Number (10 digits)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("City / Area") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (agentName.isBlank() || phone.length != 10) {
                        Toast.makeText(context, "Enter Agent Name and 10-digit Mobile", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val newId = generateAgentIdFromPhone(phone)
                    onSubmit(
                        OnboardedAgent(
                            agentId = newId,
                            agentName = agentName.trim(),
                            shopName = shopName.trim().ifBlank { "MiXCOM Shop" },
                            phone = phone.trim(),
                            city = city.trim().ifBlank { "Kumbakonam" },
                            kycStatus = "PENDING"
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
            ) {
                Text("Generate ID & Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}
