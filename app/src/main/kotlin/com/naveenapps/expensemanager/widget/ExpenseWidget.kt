package com.naveenapps.expensemanager.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.naveenapps.expensemanager.MainActivity
import com.naveenapps.expensemanager.commerce.CommerceRegistry
import com.naveenapps.expensemanager.commerce.Money
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpenseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val locked = withContext(Dispatchers.IO) { runCatching { GlobalContext.get().get<SettingsRepository>().isAppLockEnabled().first() }.getOrDefault(true) }
        val s = if(locked) null else withContext(Dispatchers.IO) { CommerceRegistry.get(context).snapshot() }
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF123D36)).padding(16.dp).clickable(actionStartActivity(android.content.Intent(context, MainActivity::class.java)))) {
                Text(s?.settings?.get("business") ?: "ميزان الأعمال", style = TextStyle(color = ColorProvider(Color.White), fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(12.dp))
                if(s == null) Text("التطبيق مقفل؛ افتحه لعرض الأرصدة", style = TextStyle(color = ColorProvider(Color.White)))
                else listOf("المبيعات" to s.sales(), "المقبوضات الفعلية" to s.cash(), "الذمم" to s.debt).forEach { (label, value) ->
                    Text("$label: ${Money.format(value)} ${s.currency}", style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp))
                    Spacer(GlanceModifier.height(8.dp))
                }
            }
        }
    }
}
class ExpenseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ExpenseWidget() }
