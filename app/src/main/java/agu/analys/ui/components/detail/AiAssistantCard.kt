package agu.analys.ui.components.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary

@Composable
fun AiAssistantCard(
    auditText: String?,
    auditLoading: Boolean,
    geminiText: String?,
    geminiLoading: Boolean,
    onGroq: () -> Unit,
    onGemini: () -> Unit,
    onClearGroq: () -> Unit,
    onClearGemini: () -> Unit
) {
    val context = LocalContext.current
    AnalysisCard {
        SectionTitle("ANALISA AI", Icons.Default.AutoAwesome)
        Spacer(Modifier.height(5.dp))
        Text(
            "Minta AI menjelaskan kondisi market dengan bahasa sederhana. Angka Entry/TP/SL tetap berasal dari engine aplikasi.",
            fontSize = 13.sp, color = TvTextSecondary, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGroq, enabled = !auditLoading && !geminiLoading, modifier = Modifier.weight(1f)) {
                Text(if (auditLoading) "Groq..." else "Groq")
            }
            Button(onClick = onGemini, enabled = !auditLoading && !geminiLoading, modifier = Modifier.weight(1f)) {
                Text(if (geminiLoading) "Gemini..." else "Gemini")
            }
        }
        Spacer(Modifier.height(9.dp))
        Button(
            onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("id.co.bitcoin")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else Toast.makeText(context, "Aplikasi Indodax belum terpasang di HP ini.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("detail_open_indodax_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FF5)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            Icon(Icons.Default.OpenInNew, null, Modifier.size(19.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Buka Indodax", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        if (auditText != null) {
            Spacer(Modifier.height(10.dp))
            Text("GROQ • ANALISA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvGreen)
            Spacer(Modifier.height(4.dp))
            Text(auditText, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp)
            Text("Hapus", fontSize = 11.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 4.dp).clickable { onClearGroq() })
        }
        if (geminiText != null) {
            Spacer(Modifier.height(10.dp))
            Text("GEMINI • ANALISA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6FB8FF))
            Spacer(Modifier.height(4.dp))
            Text(geminiText, fontSize = 13.sp, color = TvTextPrimary, lineHeight = 19.sp)
            Text("Hapus", fontSize = 11.sp, color = TvTextSecondary, modifier = Modifier.padding(top = 4.dp).clickable { onClearGemini() })
        }
    }
}
