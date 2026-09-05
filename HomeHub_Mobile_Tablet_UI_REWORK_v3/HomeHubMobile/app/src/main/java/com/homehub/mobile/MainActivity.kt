package com.homehub.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

private val Bg=Color(0xFFF3F4F6)
private val Panel=Color(0xFFFFFFFF)
private val Panel2=Color(0xFFF8FAFC)
private val Accent=Color(0xFF2563EB)
private val TextMain=Color(0xFF111827)
private val Muted=Color(0xFF6B7280)

private const val PREFS="homehub_local"
private const val DB_KEY="db"
private const val SYNC_KEY="sync_url"

class MainActivity: ComponentActivity(){
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContent { HomeHubApp() }
    }
}

fun newDb():String = JSONObject().apply {
    put("app", JSONObject().apply { put("version","7.0.0"); put("family_code",""); put("owner_id",""); put("current_user_id",""); put("settings",JSONObject().put("quiet_hours",false)) })
    arrayOf("profiles","rooms","tasks","shopping","events","pets","packages","meals","inventory","announcements","polls","notifications","activity","trips","goals").forEach { put(it, JSONArray()) }
    put("emergency",JSONObject().apply { put("contacts",JSONArray()); put("info","") })
}.toString()

fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
fun normalizeDb(raw:String):String{
    val root=runCatching{JSONObject(raw)}.getOrElse{return newDb()}
    val shopping=root.optJSONArray("shopping") ?: JSONArray()
    if(shopping.length()>0 && shopping.optJSONObject(0)?.has("items")!=true){
        val list=JSONObject().apply{put("id",id());put("name","Bevásárlólista");put("created_by",profile(raw)?.optString("name").orEmpty());put("created","");put("items",shopping)}
        root.put("shopping",JSONArray().put(list))
    }
    return root.toString()
}
fun loadDb(c:Context):String=normalizeDb(prefs(c).getString(DB_KEY,null) ?: newDb())
fun saveDb(c:Context,db:String){prefs(c).edit().putString(DB_KEY,normalizeDb(db)).apply()}
fun syncUrl(c:Context)=prefs(c).getString(SYNC_KEY,"") ?: ""
fun saveSyncUrl(c:Context,v:String){prefs(c).edit().putString(SYNC_KEY,v.trim().removeSuffix("/")).apply()}
fun id()=UUID.randomUUID().toString().replace("-","").take(8).uppercase(Locale.getDefault())
fun familyCode()=(1..6).map{"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random()}.joinToString("")

fun arr(db:String,key:String)=runCatching{JSONObject(db).optJSONArray(key) ?: JSONArray()}.getOrElse{JSONArray()}
fun count(db:String,key:String)=arr(db,key).length
fun profile(db:String):JSONObject?{
    val o=JSONObject(db); val a=o.optJSONArray("profiles") ?: return null; val cid=o.optJSONObject("app")?.optString("current_user_id")
    for(i in 0 until a.length()) if(a.optJSONObject(i)?.optString("id")==cid) return a.optJSONObject(i)
    return a.optJSONObject(0)
}
fun listNames(db:String,key:String,nameKey:String="name"):List<String>{
    val a=arr(db,key); return (0 until a.length()).mapNotNull{a.optJSONObject(it)?.optString(nameKey)?.takeIf{v->v.isNotBlank()}}
}

suspend fun httpState(base:String,code:String,postBody:String?=null):Result<String> = withContext(Dispatchers.IO){
    try{
        val clean=base.trim().removeSuffix("/")
        val url=URL("$clean/state")
        val con=url.openConnection() as HttpURLConnection
        con.connectTimeout=3500; con.readTimeout=5000
        con.setRequestProperty("X-Family-Code",code)
        con.setRequestProperty("Accept","application/json")
        if(postBody!=null){
            con.requestMethod="POST"; con.doOutput=true; con.setRequestProperty("Content-Type","application/json; charset=utf-8")
            con.outputStream.use{it.write(postBody.toByteArray(Charsets.UTF_8))}
        }
        val codeNum=con.responseCode
        val stream=if(codeNum in 200..299) con.inputStream else con.errorStream
        val body=stream?.bufferedReader()?.use{it.readText()} ?: ""
        con.disconnect()
        if(codeNum !in 200..299) Result.failure(Exception("HTTP $codeNum: $body"))
        else {
            val root=JSONObject(body)
            Result.success(root.optJSONObject("state")?.toString() ?: body)
        }
    }catch(e:Exception){Result.failure(e)}
}

@Composable fun HomeHubApp(){
    val context=LocalContext.current
    var db by remember { mutableStateOf(loadDb(context)) }
    var page by remember { mutableStateOf("Kezdőlap") }
    val hasFamily=remember(db){arr(db,"profiles").length>0 && JSONObject(db).optJSONObject("app")?.optString("family_code").orEmpty().isNotBlank()}
    if(!hasFamily){
        Onboarding(onDone={newDbString->saveDb(context,newDbString);db=newDbString})
    }else{
        MainShell(db,page,{page=it},{newDb->saveDb(context,newDb);db=newDb})
    }
}

@Composable fun Onboarding(onDone:(String)->Unit){
    var name by remember{mutableStateOf("")}; var birth by remember{mutableStateOf("")}; var code by remember{mutableStateOf("")}; var join by remember{mutableStateOf(false)}
    Box(Modifier.fillMaxSize().background(Bg),contentAlignment=Alignment.Center){
        Card(colors=CardDefaults.cardColors(Panel),shape=RoundedCornerShape(22.dp),modifier=Modifier.padding(20.dp).widthIn(max=540.dp)){
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("HOMEHUB",color=Accent,fontSize=13.sp,fontWeight=FontWeight.Bold)
                Text("Az otthonod központja.",color=TextMain,fontSize=27.sp,fontWeight=FontWeight.Bold)
                Text("Offline-first. A telefon és tablet önállóan is működik, a PC-vel pedig helyi Wi-Fi-n szinkronizálható.",color=Muted)
                OutlinedTextField(name,{name=it},label={Text("Név")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(birth,{birth=it},label={Text("Születési dátum • ÉÉÉÉ-HH-NN")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(join) OutlinedTextField(code,{code=it},label={Text("Családi kód")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(enabled=name.isNotBlank() && (!join||code.isNotBlank()),onClick={
                    val pid=id(); val c=if(join) code.trim().uppercase() else familyCode()
                    val root=JSONObject(newDb()); root.getJSONObject("app").apply{put("family_code",c);put("current_user_id",pid);if(!join)put("owner_id",pid)}
                    root.getJSONArray("profiles").put(JSONObject().apply{put("id",pid);put("name",name.trim());put("birth",birth.trim());put("role",if(join)"MEMBER" else "OWNER");put("avatar",if(join)"🙂" else "👑");put("color",if(join)"#22d3ee" else "#7c3aed")})
                    onDone(root.toString())
                },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Accent)){
                    Text(if(join)"CSATLAKOZÁS" else "ÚJ CSALÁD LÉTREHOZÁSA")
                }
                TextButton(onClick={join=!join},modifier=Modifier.fillMaxWidth()){Text(if(join)"Új családot hozok létre" else "Már van családi kódom")}
                Text(if(join)"A meglévő családi adatok betöltéséhez a Beállításokban add meg a PC helyi címét, majd nyomj SZINKRONIZÁLÁS-t." else "A családi kódot később a Család oldalon is megtalálod.",color=Muted,fontSize=11.sp)
            }
        }
    }
}

@Composable fun MainShell(db:String,page:String,onPage:(String)->Unit,onDb:(String)->Unit){
    val cfg=LocalConfiguration.current; val tablet=cfg.screenWidthDp>=700
    val scope=rememberCoroutineScope(); val drawerState=rememberDrawerState(DrawerValue.Closed)
    val tabs=listOf("Kezdőlap","Bevásárlás","Feladatok","Naptár","Kisállatok","Csomagok","Ételek","Hol van?","Család","Utazás","Vészinfó","HomeHub AI","Beállítások")
    if(!tablet){
        ModalNavigationDrawer(drawerState=drawerState,drawerContent={
            ModalDrawerSheet(drawerContainerColor=Panel){
                Text("⌂ HOMEHUB",color=TextMain,fontSize=21.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(20.dp))
                tabs.forEach{t->NavRow(t,page==t){onPage(t);scope.launch{drawerState.close()}}}
                Spacer(Modifier.height(12.dp));Text("● OFFLINE • WI-FI SYNC",color=Color(0xFF54E6A8),fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(20.dp))
            }
        }){
            Scaffold(containerColor=Bg,topBar={TopAppBar(title={Text(page,color=TextMain,fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick={scope.launch{drawerState.open()}}){Text("☰",color=TextMain,fontSize=24.sp)}} ,colors=TopAppBarDefaults.topAppBarColors(containerColor=Bg))}){pad->Box(Modifier.fillMaxSize().padding(pad)){Page(page,db,onDb)}}
        }
    }else{
        Row(Modifier.fillMaxSize().background(Bg)){
            Column(Modifier.width(220.dp).fillMaxHeight().background(Panel).padding(12.dp)){
                Text("⌂ HOMEHUB",color=TextMain,fontSize=21.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(8.dp))
                profile(db)?.let{Text("${it.optString("name")} • ${it.optString("role")}",color=Muted,fontSize=11.sp,modifier=Modifier.padding(8.dp))}
                tabs.forEach{t->NavRow(t,page==t){onPage(t)}}
                Spacer(Modifier.weight(1f));Text("● OFFLINE • WI-FI SYNC",color=Color(0xFF54E6A8),fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(8.dp))
            }
            Box(Modifier.fillMaxSize().weight(1f)){Page(page,db,onDb)}
        }
    }
}

@Composable fun NavRow(t:String,sel:Boolean,on:()->Unit){
    Text("${iconFor(t)}  $t",color=if(sel)TextMain else Muted,fontWeight=if(sel)FontWeight.Bold else FontWeight.Normal,modifier=Modifier.fillMaxWidth().clickable{on()}.background(if(sel)Color(0xFF202A42) else Color.Transparent,RoundedCornerShape(9.dp)).padding(10.dp))
}
fun iconFor(s:String)=when(s){"Kezdőlap"->"⌂";"Bevásárlás"->"🛒";"Feladatok"->"✓";"Naptár"->"▦";"Kisállatok"->"🐾";"Csomagok"->"📦";"Ételek"->"🍕";"Hol van?"->"⌖";"Család"->"👥";"Utazás"->"✈";"Vészinfó"->"⚠";"HomeHub AI"->"✦";else->"⚙"}

@Composable fun Page(page:String,db:String,onDb:(String)->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("${iconFor(page)}  $page",color=TextMain,fontSize=28.sp,fontWeight=FontWeight.Bold);Text(if(page=="HomeHub AI")"Helyi AI, internet nélkül." else "HomeHub • helyi adatok",color=Muted)}
        when(page){
            "Kezdőlap"->HomePage(db)
            "Bevásárlás"->ShoppingPage(db,onDb)
            "Család"->FamilyPage(db)
            "HomeHub AI"->AIView(db,onDb)
            "Beállítások"->SettingsPage(db,onDb)
            "Vészinfó"->ModulePage("emergency",db,"Vészhelyzeti adatok","")
            else->ModulePage(pageKey(page),db,page,description(page))
        }
    }
}

@Composable fun HomePage(db:String){
    itemStatRow(db)
    CardBox("Mai áttekintés","${count(db,"tasks")} feladat • ${count(db,"shopping")} bevásárlási tétel • ${count(db,"events")} esemény • ${count(db,"packages")} csomag. Minden helyben tárolva.")
    val acts=listNames(db,"activity","text"); if(acts.isNotEmpty()) CardBox("Legutóbbi tevékenység",acts.take(5).joinToString("\n"))
}
@Composable fun itemStatRow(db:String){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Stat("Család",count(db,"profiles"));Stat("Bevásárlás",count(db,"shopping"));Stat("Feladat",count(db,"tasks"));Stat("Esemény",count(db,"events"));Stat("Csomag",count(db,"packages"))
    }
}
@Composable fun Stat(a:String,b:Int){CardBox(a,b.toString(),Modifier.width(130.dp))}
@Composable fun CardBox(title:String,body:String,modifier:Modifier=Modifier){Card(colors=CardDefaults.cardColors(Panel),shape=RoundedCornerShape(17.dp),modifier=modifier.fillMaxWidth()){Column(Modifier.padding(17.dp)){Text(title,color=TextMain,fontWeight=FontWeight.Bold,fontSize=15.sp);Text(body,color=Muted,modifier=Modifier.padding(top=6.dp))}}}

fun pageKey(page:String)=when(page){"Bevásárlás"->"shopping";"Feladatok"->"tasks";"Naptár"->"events";"Kisállatok"->"pets";"Csomagok"->"packages";"Ételek"->"meals";"Hol van?"->"inventory";"Utazás"->"trips";else->"announcements"}
fun description(p:String)=when(p){"Bevásárlás"->"Nyitott bevásárlási tételek.";"Feladatok"->"Feladatok és határidők.";"Naptár"->"Események és születésnapok.";"Kisállatok"->"Etetés, séta, oltás, állatorvos.";"Csomagok"->"Csomagok és státuszok.";"Ételek"->"Heti menü és hozzávalók.";"Hol van?"->"Háztartási tárgyak helye.";"Utazás"->"Utazási tervek és csomaglista.";else->"Helyi HomeHub adatok."}

@Composable fun ShoppingPage(db:String,onDb:(String)->Unit){
    val root=JSONObject(db); val lists=root.optJSONArray("shopping") ?: JSONArray()
    var createOpen by remember{mutableStateOf(false)}; var newName by remember{mutableStateOf("")}
    var itemTarget by remember{mutableStateOf(-1)}; var itemName by remember{mutableStateOf("")}; var itemQty by remember{mutableStateOf("1")}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Button(onClick={createOpen=true},colors=ButtonDefaults.buttonColors(containerColor=Accent),modifier=Modifier.fillMaxWidth()){Text("＋  BEVÁSÁRLÓLISTA LÉTREHOZÁSA")}
        if(lists.length()==0) CardBox("Nincs lista","Hozz létre egy bevásárlólistát, aztán mehet bele bármi.")
        for(i in 0 until lists.length()){
            val list=lists.optJSONObject(i) ?: continue; val items=list.optJSONArray("items") ?: JSONArray()
            Card(colors=CardDefaults.cardColors(Panel),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.weight(1f)){Text(list.optString("name","Bevásárlólista"),color=TextMain,fontSize=18.sp,fontWeight=FontWeight.Bold);Text("${items.length()} termék • bárki hozzáadhat",color=Muted,fontSize=11.sp)}
                        val canDelete=items.length()==0 || (0 until items.length()).all{items.optJSONObject(it)?.optBoolean("bought",false)==true}
                        if(canDelete) TextButton(onClick={root.getJSONArray("shopping").remove(i);onDb(root.toString())}){Text("Lista törlése",color=Color(0xFFB91C1C))}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth().padding(top=8.dp)){
                        Button(onClick={itemTarget=i},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFE0E7FF))){Text("＋ Termék",color=Color(0xFF3730A3))}
                    }
                    for(j in 0 until items.length()){
                        val it=items.optJSONObject(j) ?: continue; val bought=it.optBoolean("bought",false)
                        Row(Modifier.fillMaxWidth().padding(top=6.dp).background(Color(0xFFF8FAFC),RoundedCornerShape(8.dp)).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(if(bought)"☑" else "☐",color=if(bought)Color(0xFF16A34A) else TextMain,fontSize=17.sp)
                            Text("${it.optString("name")}  ×${it.optString("qty","1")}",color=if(bought)Muted else TextMain,modifier=Modifier.weight(1f).padding(start=8.dp))
                            TextButton(onClick={it.put("bought",!bought);onDb(root.toString())}){Text(if(bought)"Vissza" else "Megvan")}
                            TextButton(onClick={items.remove(j);onDb(root.toString())}){Text("×",color=Color(0xFFB91C1C),fontSize=18.sp)}
                        }
                    }
                }
            }
        }
    }
    if(createOpen) AlertDialog(onDismissRequest={createOpen=false},title={Text("Új bevásárlólista")},text={OutlinedTextField(newName,{newName=it},label={Text("Lista neve")},singleLine=true)},confirmButton={TextButton(onClick={if(newName.isNotBlank()){lists.put(JSONObject().apply{put("id",id());put("name",newName.trim());put("created_by",profile(db)?.optString("name").orEmpty());put("created","");put("items",JSONArray())});onDb(root.toString());newName="";createOpen=false}}){Text("Létrehozás")}},dismissButton={TextButton(onClick={createOpen=false}){Text("Mégse")}})
    if(itemTarget>=0) AlertDialog(onDismissRequest={itemTarget=-1},title={Text("Termék hozzáadása")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(itemName,{itemName=it},label={Text("Termék")},singleLine=true);OutlinedTextField(itemQty,{itemQty=it},label={Text("Mennyiség")},singleLine=true)}},confirmButton={TextButton(onClick={if(itemName.isNotBlank()){val l=lists.optJSONObject(itemTarget)!!;l.optJSONArray("items")!!.put(JSONObject().apply{put("id",id());put("name",itemName.trim());put("qty",itemQty.ifBlank{"1"});put("bought",false);put("added_by",profile(db)?.optString("name").orEmpty())});onDb(root.toString());itemName="";itemQty="1";itemTarget=-1}}){Text("Hozzáadás")}},dismissButton={TextButton(onClick={itemTarget=-1}){Text("Mégse")}})
}

@Composable fun ModulePage(key:String,db:String,title:String,desc:String){
    CardBox(title,desc)
    val a=arr(db,key)
    if(a.length==0) CardBox("Üres","Még nincs adat ebben a modulban.")
    else for(i in 0 until a.length()){
        val o=a.optJSONObject(i) ?: continue
        val text=o.optString("name").ifBlank{o.optString("title")}.ifBlank{o.optString("item")}.ifBlank{o.optString("meal")}.ifBlank{"Elem ${i+1}"}
        val details=o.keys().asSequence().filter{it!="id" && it!="name" && it!="title" && it!="item" && it!="meal"}.mapNotNull{key2->o.optString(key2).takeIf{v->v.isNotBlank() && v!="null"}?.let{"$key2: $it"}}.take(3).joinToString(" • ")
        CardBox(text,details)
    }
}

@Composable fun FamilyPage(db:String){
    val root=JSONObject(db); val app=root.optJSONObject("app")!!; val p=profile(db)
    CardBox("Családi kód",app.optString("family_code"))
    CardBox("Te","${p?.optString("avatar")} ${p?.optString("name")} • ${p?.optString("role")}\nSzületési dátum: ${p?.optString("birth").orEmpty().ifBlank{"nincs megadva"}}")
    val ps=arr(db,"profiles"); for(i in 0 until ps.length()){val x=ps.optJSONObject(i)?:continue;CardBox("${x.optString("avatar")} ${x.optString("name")}","${x.optString("role")} • ${x.optString("birth").ifBlank{"nincs születési dátum"})")}
}

@Composable fun AIView(db:String,onDb:(String)->Unit){
    var q by remember{mutableStateOf("")}; var answer by remember{mutableStateOf("Szia ${profile(db)?.optString("name").orEmpty()}! A helyi HomeHub adataiból dolgozom. Kérdezhetsz vagy adhatsz egyszerű parancsot.")}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        CardBox("✦ HomeHub AI",answer)
        OutlinedTextField(q,{q=it},label={Text("Írj a HomeHub AI-nak…")},modifier=Modifier.fillMaxWidth())
        Button(enabled=q.isNotBlank(),onClick={val r=aiProcess(q,db);answer=r.first;onDb(r.second);q=""},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Accent)){Text("KÜLDÉS")}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Mi újság?","Mi van a bevásárláson?","Milyen feladatok vannak?","Adj hozzá tejet a bevásárlólistához").forEach{AssistChip(onClick={q=it},label={Text(it)})}}
    }
}

fun aiProcess(q:String,db:String):Pair<String,String>{
    val l=q.lowercase(Locale.getDefault()).trim(); var root=JSONObject(db); var changed=false
    fun add(key:String,obj:JSONObject){root.optJSONArray(key)?.put(obj);changed=true}
    if(l.isBlank()) return "Írj valamit, a gondolatolvasás még nincs bekötve." to db
    if(l.contains("bevás") && !l.contains("adj hozzá") && !l.contains("add hozzá")){
        val names=mutableListOf<String>()
        val lists=arr(db,"shopping")
        for(i in 0 until lists.length()){
            val items=lists.optJSONObject(i)?.optJSONArray("items") ?: continue
            for(j in 0 until items.length()){
                val it=items.optJSONObject(j) ?: continue
                if(!it.optBoolean("bought",false) && it.optString("name").isNotBlank()) names.add(it.optString("name"))
            }
        }
        return "A nyitott bevásárlás: "+(names.joinToString(", ").ifBlank{"jelenleg üres."}) to db
    }
    if(l.contains("feladat")||l.contains("teendő")) return "Nyitott feladatok: "+listNames(db,"tasks").joinToString(", ").ifBlank{"nincs."}) to db
    if(l.contains("család") && (l.contains("hány")||l.contains("kik")||l.contains("tag"))) return "Jelenleg ${count(db,"profiles")} családtag van a helyi adatokban." to db
    if(l.contains("csomag")) return "Jelenleg ${count(db,"packages")} csomag van a rendszerben." to db
    if(l.contains("kisáll")||l.contains("állat")) return "Kisállatok: "+listNames(db,"pets").joinToString(", ").ifBlank{"még nincs felvéve."} to db
    if(l.contains("mi újság")||l.contains("összeg")) return "HomeHub összkép: ${count(db,"profiles")} családtag, ${count(db,"shopping")} bevásárlás, ${count(db,"tasks")} feladat, ${count(db,"events")} esemény, ${count(db,"pets")} kisállat, ${count(db,"packages")} csomag." to db
    if(l.startsWith("adj hozzá") || l.startsWith("add hozzá") || l.startsWith("add ")){
        val regex=Regex("(?:adj hozzá|add hozzá|add)\\s+(.+?)(?:\\s+a bevásárlólistához|\\s+bevásárlólistához)?$",RegexOption.IGNORE_CASE)
        val m=regex.find(q)
        if(m!=null){
            val item=m.groupValues[1].trim(); val p=profile(db)
            val lists=root.optJSONArray("shopping") ?: JSONArray().also{root.put("shopping",it)}
            if(lists.length()==0) lists.put(JSONObject().apply{put("id",id());put("name","Bevásárlólista");put("created_by",p?.optString("name").orEmpty());put("created","");put("items",JSONArray())})
            lists.optJSONObject(0)!!.optJSONArray("items")!!.put(JSONObject().apply{put("id",id());put("name",item);put("qty","1");put("bought",false);put("added_by",p?.optString("name").orEmpty())})
            return "Hozzáadtam a(z) ${lists.optJSONObject(0)?.optString("name","Bevásárlólista")} listához: $item." to root.toString()
        }
    }
    if(l.contains("mit tudsz")||l.contains("segíts")) return "Tudok helyi HomeHub-adatokból összefoglalni, listákat lekérdezni és egyszerű műveleteket végrehajtani. Például: „Adj hozzá tejet a bevásárlólistához”." to db
    return "Ezt még nem tudom biztosan értelmezni. Próbáld: „Mi újság?”, „Mi van a bevásárláson?” vagy „Adj hozzá kenyeret a bevásárlólistához”." to db
}

@Composable fun SettingsPage(db:String,onDb:(String)->Unit){
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var url by remember{mutableStateOf(syncUrl(context))}; var status by remember{mutableStateOf("")};
    CardBox("Offline mód","Az adatok ezen az eszközön maradnak. Internet nélkül is használható a HomeHub.")
    Card(colors=CardDefaults.cardColors(Panel),shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("PC helyi szinkron",color=TextMain,fontWeight=FontWeight.Bold)
            Text("A PC HomeHub indításakor a helyi szerver a 8765-ös porton indul. Ugyanazon Wi-Fi-n add meg például: http://192.168.1.20:8765",color=Muted,fontSize=12.sp)
            OutlinedTextField(url,{url=it;saveSyncUrl(context,it)},label={Text("PC cím")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={
                status="Szinkronizálás…"
                scope.launch{
                    val code=JSONObject(db).optJSONObject("app")?.optString("family_code").orEmpty()
                    val result=httpState(url,code,db)
                    result.onSuccess{remote->onDb(remote);status="Szinkron kész. A PC és a mobil ugyanazt az adatot használja."}.onFailure{e->status="Nem sikerült: ${e.message}"}
                }
            },enabled=url.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Accent)){Text("SZINKRONIZÁLÁS")}
            Text(status,color=if(status.startsWith("Nem"))Color(0xFFFF7A7A) else Color(0xFF63E6BE),fontSize=12.sp)
        }
    }
    val code=JSONObject(db).optJSONObject("app")?.optString("family_code").orEmpty(); CardBox("Családi kód",code)
}
