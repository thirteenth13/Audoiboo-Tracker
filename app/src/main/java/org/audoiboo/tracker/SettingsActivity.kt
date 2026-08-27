package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppPrefs {
    private const val FILE="app_settings"
    private const val BASE="base_folder"; private const val AUTHOR="author_folder"; private const val DEV="dev_tools"; private const val DARK="dark_theme"
    private const val ASK="ask_path"; private const val WIFI="wifi_only"; private const val UNPACK="unpack"; private const val UPDATE_WIFI="update_wifi"
    private fun p(c:Context)=c.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    fun baseFolder(c:Context)=p(c).getString(BASE,"Audoiboo")?.trim()?.ifBlank{"Audoiboo"}?:"Audoiboo"
    fun useAuthorFolder(c:Context)=p(c).getBoolean(AUTHOR,true)
    fun devTools(c:Context)=p(c).getBoolean(DEV,false)
    fun darkTheme(c:Context)=p(c).getBoolean(DARK,false)
    fun askPath(c:Context)=p(c).getBoolean(ASK,false)
    fun wifiOnly(c:Context)=p(c).getBoolean(WIFI,false)
    fun unpack(c:Context)=p(c).getBoolean(UNPACK,false)
    fun updateWifiOnly(c:Context)=p(c).getBoolean(UPDATE_WIFI,true)
    fun save(c:Context,base:String,author:Boolean,dev:Boolean,dark:Boolean,ask:Boolean,wifi:Boolean,unpack:Boolean,updateWifi:Boolean){p(c).edit().putString(BASE,base.trim().trim('/')).putBoolean(AUTHOR,author).putBoolean(DEV,dev).putBoolean(DARK,dark).putBoolean(ASK,ask).putBoolean(WIFI,wifi).putBoolean(UNPACK,unpack).putBoolean(UPDATE_WIFI,updateWifi).apply()}
}

@Composable fun AudoibooTheme(context:Context,content:@Composable()->Unit){MaterialTheme(colorScheme=if(AppPrefs.darkTheme(context)) darkColorScheme() else lightColorScheme(),content=content)}

class SettingsActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{SettingsScreen(this)}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(a:ComponentActivity){
 var base by remember{mutableStateOf(AppPrefs.baseFolder(a))};var author by remember{mutableStateOf(AppPrefs.useAuthorFolder(a))};var dev by remember{mutableStateOf(AppPrefs.devTools(a))};var dark by remember{mutableStateOf(AppPrefs.darkTheme(a))};var ask by remember{mutableStateOf(AppPrefs.askPath(a))};var wifi by remember{mutableStateOf(AppPrefs.wifiOnly(a))};var unpack by remember{mutableStateOf(AppPrefs.unpack(a))};var updateWifi by remember{mutableStateOf(AppPrefs.updateWifiOnly(a))}
 fun save(){AppPrefs.save(a,base,author,dev,dark,ask,wifi,unpack,updateWifi)}
 MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()){
  Scaffold(topBar={TopAppBar(title={Text("Налаштування")},navigationIcon={TextButton(onClick={a.finish()}){Text("←")}})}){pad->
   Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    SectionTitle("Зовнішній вигляд")
    SettingCard("Тема інтерфейсу",if(dark)"Темна (Material You)" else "Світла (Material 3)"){Switch(dark,{dark=it;save()})}
    SectionTitle("Завантаження")
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     OutlinedTextField(base,{base=it},label={Text("Базова папка")},supportingText={Text("/storage/emulated/0/Download/${base.ifBlank{"Audoiboo"}")")},modifier=Modifier.fillMaxWidth(),singleLine=true)
     Text("Структура папок",style=MaterialTheme.typography.titleSmall);Text(if(author)"Автор → Серія → Книга" else "Серія → Книга");Text("${base.ifBlank{"Audoiboo"}}/${if(author)"Автор/" else ""}Серія/Книга.zip",style=MaterialTheme.typography.bodySmall)
     SettingRow("Папка автора","Автор першим рівнем"){Switch(author,{author=it;save()})}
     SettingRow("Запитувати шлях завантаження","Перед кожним завантаженням"){Switch(ask,{ask=it;save()})}
     SettingRow("Завантажувати тільки по Wi‑Fi","Економія мобільного трафіку"){Switch(wifi,{wifi=it;save()})}
     SettingRow("Розпаковувати архіви","Після завантаження (dev)"){Switch(unpack,{unpack=it;save()})}
     Button(onClick={save()}){Text("Зберегти")}
    }}
    SectionTitle("Інше")
    SettingCard("Перевіряти оновлення",if(updateWifi)"Лише по Wi‑Fi" else "Будь-яка мережа"){Switch(updateWifi,{updateWifi=it;save()})}
    Card(Modifier.fillMaxWidth().clickable{}){Column(Modifier.padding(16.dp)){Text("Про додаток");Text("Audoiboo Tracker 0.3.1-dev",style=MaterialTheme.typography.bodySmall)}}
    SectionTitle("Розробка")
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){SettingRow("Інструменти налагодження","DOM-діагностика"){Switch(dev,{dev=it;save()})};if(dev)OutlinedButton(onClick={a.startActivity(Intent(a,DiagnosticActivity::class.java))}){Text("Відкрити DOM-діагностику")}}}
   }
  }
 }
}
@Composable private fun SectionTitle(t:String){Text(t,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)}
@Composable private fun SettingCard(t:String,s:String,end:@Composable()->Unit){Card(Modifier.fillMaxWidth()){SettingRow(t,s,end)}}
@Composable private fun SettingRow(t:String,s:String,end:@Composable()->Unit){Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(t);Text(s,style=MaterialTheme.typography.bodySmall)};end()}}
