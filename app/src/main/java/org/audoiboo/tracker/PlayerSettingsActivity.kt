package org.audoiboo.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PlayerSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { PlayerSettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsScreen(activity: ComponentActivity) {
    var seek by remember { mutableIntStateOf(PlayerPrefs.seekSeconds(activity)) }
    var rewind by remember { mutableIntStateOf(PlayerPrefs.autoRewindSeconds(activity)) }
    var resume by remember { mutableStateOf(PlayerPrefs.resumeAfterCall(activity)) }
    var notifications by remember { mutableStateOf(PlayerPrefs.pauseOnNotifications(activity)) }
    var other by remember { mutableStateOf(PlayerPrefs.stopOtherPlayers(activity)) }
    var hours by remember { mutableIntStateOf(PlayerPrefs.forceStopHours(activity)) }
    var speed by remember { mutableStateOf(PlayerPrefs.showSpeed(activity)) }
    var sleep by remember { mutableStateOf(PlayerPrefs.showSleep(activity)) }
    var bookmarks by remember { mutableStateOf(PlayerPrefs.showBookmarks(activity)) }
    var voiceBoost by remember { mutableStateOf(AudioEnhancementPrefs.voiceBoost(activity)) }
    var gain by remember { mutableIntStateOf(AudioEnhancementPrefs.gainMb(activity)) }

    fun save() = PlayerPrefs.save(activity, seek, rewind, resume, notifications, other, hours, speed, sleep, bookmarks)
    fun saveAudio() = AudioEnhancementPrefs.save(activity, voiceBoost, gain)

    Scaffold(topBar={ TopAppBar(title={Text("Налаштування плеєра")}, navigationIcon={IconButton(onClick={save();saveAudio();activity.finish()}){Icon(Icons.Filled.ArrowBack,"Назад")}}) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingChoice("Перемотка назад/вперед", "$seek секунд", listOf(5,10,15,30,60).map{it to "$it секунд"}) { seek=it;save() }
            SettingChoice("Автоматична перемотка назад", "$rewind секунд після паузи", listOf(0,3,5,10,15,30).map{it to if(it==0)"Вимкнено" else "$it секунд"}) { rewind=it;save() }
            SettingSwitch("Продовжити після дзвінка", "Продовжувати відтворення після закінчення дзвінка", resume) { resume=it;save() }
            SettingSwitch("Зупиняти під час сповіщень", "Ставити аудіокнигу на паузу під час інших аудіоподій", notifications) { notifications=it;save() }
            SettingSwitch("Зупиняти, коли грають інші", "Надавати аудіофокус плеєру та призупиняти інші медіаплеєри", other) { other=it;save() }
            SettingChoice("Примусове вимкнення", "$hours години", listOf(0 to "Ніколи",1 to "1 година",2 to "2 години",4 to "4 години",8 to "8 годин")) { hours=it;save() }
            HorizontalDivider()
            SettingSwitch("Voice boost", "Підсилити тиху мову через системний LoudnessEnhancer", voiceBoost) { voiceBoost=it;saveAudio() }
            if (voiceBoost) SettingChoice("Підсилення голосу", "+${gain / 100f} dB", listOf(300 to "+3 dB",600 to "+6 dB",900 to "+9 dB",1200 to "+12 dB")) { gain=it;saveAudio() }
            HorizontalDivider()
            SettingSwitch("Кнопка швидкості відтворення", "Показувати керування швидкістю на екрані плеєра", speed) { speed=it;save() }
            SettingSwitch("Кнопка таймера сну", "Показувати таймер сну на екрані плеєра", sleep) { sleep=it;save() }
            SettingSwitch("Кнопка закладок", "Дозволяє позначати цікаві моменти", bookmarks) { bookmarks=it;save() }
        }
    }
}

@Composable private fun SettingSwitch(title:String, subtitle:String, checked:Boolean, onChange:(Boolean)->Unit){ Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(checked,onChange)};HorizontalDivider() }

@Composable private fun SettingChoice(title:String, value:String, choices:List<Pair<Int,String>>, onChoose:(Int)->Unit){ var open by remember{mutableStateOf(false)}; Column(Modifier.fillMaxWidth().clickable{open=true}.padding(horizontal=20.dp,vertical=14.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};HorizontalDivider();if(open)AlertDialog(onDismissRequest={open=false},title={Text(title)},text={Column{choices.forEach{(v,label)->TextButton(onClick={onChoose(v);open=false},modifier=Modifier.fillMaxWidth()){Text(label,modifier=Modifier.fillMaxWidth())}}}},confirmButton={TextButton(onClick={open=false}){Text("Скасувати")}}) }
