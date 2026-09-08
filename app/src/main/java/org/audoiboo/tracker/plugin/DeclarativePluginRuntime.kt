package org.audoiboo.tracker.plugin

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface DeclarativeEntrypoint {
    data class SeriesLookup(val title:String,val description:String?=null,val remoteId:String?=null,val books:RepeatedFields?=null,val followLink:String?=null,val titleRegex:String?=null,val supplement:SeriesSupplement?=null):DeclarativeEntrypoint
    data class SeriesSearch(val searchUrl:String,val items:RepeatedFields,val maxResults:Int=10):DeclarativeEntrypoint
    data class BookLookup(val title:String,val author:String?=null,val remoteId:String?=null,val seriesTitle:String?=null,val seriesNumber:String?=null,val coverUrl:String?=null,val description:String?=null,val titleRegex:String?=null):DeclarativeEntrypoint
    data class DownloadResolution(val items:RepeatedFields,val type:DownloadType=DownloadType.ARCHIVE,val fileName:String?=null):DeclarativeEntrypoint
}
data class RepeatedFields(val item:String,val title:String?=null,val link:String,val author:String?=null,val remoteId:String?=null,val number:String?=null)
data class SeriesSupplement(val startLink:String,val items:RepeatedFields,val seriesTitle:String,val nextPage:String?=null,val maxPages:Int=1)
fun interface DeclarativeEntrypointDecoder { fun decode(json:String):DeclarativeEntrypoint }
object JsonDeclarativeEntrypointDecoder:DeclarativeEntrypointDecoder {
 override fun decode(json:String):DeclarativeEntrypoint { val root=JSONObject(json); return when(root.getString("operation")){
  "seriesLookup"->{val s=root.getJSONObject("series"); DeclarativeEntrypoint.SeriesLookup(s.getString("title"),s.optString("description").takeIf{it.isNotBlank()},s.optString("remoteId").takeIf{it.isNotBlank()},s.optJSONObject("books")?.toRepeatedFields(),s.optString("followLink").takeIf{it.isNotBlank()},s.optString("titleRegex").takeIf{it.isNotBlank()},s.optJSONObject("supplement")?.let{SeriesSupplement(it.getString("startLink"),it.getJSONObject("items").toRepeatedFields(),it.getString("seriesTitle"),it.optString("nextPage").takeIf(String::isNotBlank),it.optInt("maxPages",1).coerceIn(1,10))})}
  "seriesSearch"->DeclarativeEntrypoint.SeriesSearch(root.getString("searchUrl"),root.getJSONObject("items").toRepeatedFields(),root.optInt("maxResults",10).coerceIn(1,50))
  "bookLookup"->{val b=root.getJSONObject("book"); DeclarativeEntrypoint.BookLookup(b.getString("title"),b.optString("author").takeIf{it.isNotBlank()},b.optString("remoteId").takeIf{it.isNotBlank()},b.optString("seriesTitle").takeIf{it.isNotBlank()},b.optString("seriesNumber").takeIf{it.isNotBlank()},b.optString("coverUrl").takeIf{it.isNotBlank()},b.optString("description").takeIf{it.isNotBlank()},b.optString("titleRegex").takeIf{it.isNotBlank()})}
  "downloadResolution"->DeclarativeEntrypoint.DownloadResolution(root.getJSONObject("items").toRepeatedFields(),root.optString("type",DownloadType.ARCHIVE.name).let(DownloadType::valueOf),root.optString("fileName").takeIf{it.isNotBlank()})
  else->error("Unsupported declarative operation")}}
 private fun JSONObject.toRepeatedFields()=RepeatedFields(getString("item"),optString("title").takeIf{it.isNotBlank()},getString("link"),optString("author").takeIf{it.isNotBlank()},optString("remoteId").takeIf{it.isNotBlank()},optString("number").takeIf{it.isNotBlank()})
}

class DeclarativePluginRuntime(private val sandbox:PluginSandbox,private val decoder:DeclarativeEntrypointDecoder=JsonDeclarativeEntrypointDecoder){
 fun resolveSeries(manifest:PluginPackageManifest,packageDir:File,url:String):SourceSeries?{requireCapability(manifest,SourceCapability.SERIES_LOOKUP);val spec=loadEntrypoint(manifest,packageDir,"seriesLookup") as? DeclarativeEntrypoint.SeriesLookup?:throw PluginSandboxViolation("seriesLookup entrypoint has wrong operation");val session=sandbox.open(manifest);var response=session.httpGet(url);if(response.statusCode !in 200..299)return null;var document=Jsoup.parse(response.body,response.finalUrl);spec.followLink?.let{selector->extract(document,selector)?.takeIf{it.isNotBlank()}?.let{follow->session.httpGet(resolveUrl(document,follow)).takeIf{it.statusCode in 200..299}?.let{response=it;document=Jsoup.parse(it.body,it.finalUrl)}}};var title=extract(document,spec.title)?.takeIf{it.isNotBlank()}?:return null;title=applyRegex(title,spec.titleRegex);val books=buildList{spec.books?.let{addAll(extractBookRefs(document,it))};spec.supplement?.let{addAll(loadSupplementRefs(session,document,title,it))}}.distinctBy{SourceKeys.normalizeUrl(it.url)};session.requireOutputSize(books.size);val authors=spec.books?.author?.let{sel->document.select(spec.books.item).mapNotNull{extract(it,sel)?.trim()?.takeIf(String::isNotEmpty)}.distinct().map(::SourceAuthor)}.orEmpty();return SourceSeries(manifest.id,spec.remoteId?.let{extract(document,it)}?.takeIf{it.isNotBlank()},response.finalUrl,title,spec.description?.let{extract(document,it)}?.takeIf{it.isNotBlank()},authors,books)}
 fun searchSeries(manifest:PluginPackageManifest,packageDir:File,query:SeriesSearchQuery):List<SeriesCandidate>{requireCapability(manifest,SourceCapability.SERIES_SEARCH);val spec=loadEntrypoint(manifest,packageDir,"seriesSearch") as? DeclarativeEntrypoint.SeriesSearch?:throw PluginSandboxViolation("seriesSearch entrypoint has wrong operation");val encoded=URLEncoder.encode(query.title.trim(),StandardCharsets.UTF_8.name());val searchUrl=spec.searchUrl.replace("{query}",encoded);if(searchUrl==spec.searchUrl)throw PluginSandboxViolation("seriesSearch searchUrl must contain {query}");val session=sandbox.open(manifest);val response=session.httpGet(searchUrl);if(response.statusCode !in 200..299)return emptyList();val document=Jsoup.parse(response.body,response.finalUrl);val results=document.select(spec.items.item).asSequence().mapNotNull{item->val link=extract(item,spec.items.link)?.takeIf{it.isNotBlank()}?:return@mapNotNull null;val title=spec.items.title?.let{extract(item,it)}?.takeIf{it.isNotBlank()}?:return@mapNotNull null;val author=spec.items.author?.let{extract(item,it)}?.takeIf{it.isNotBlank()};SeriesCandidate(SourceSeries(sourceId=manifest.id,remoteId=spec.items.remoteId?.let{extract(item,it)}?.takeIf{it.isNotBlank()},url=resolveUrl(item,link),title=title,authors=author?.let{listOf(SourceAuthor(it))}.orEmpty()))}.distinctBy{it.series.url}.take(spec.maxResults).toList();session.requireOutputSize(results.size);return results}
 fun discoverCanonicalSeries(manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput):List<SeriesCandidate>{requireCapability(manifest,SourceCapability.SERIES_DISCOVERY);return when(manifest.id){"baza-knig"->discoverBazaSeries(manifest,canonical);"lis10book"->discoverLis10BookSeries(manifest,canonical);"izib"->discoverIzibSeries(manifest,canonical);else->emptyList()}}

 fun discoverIzibSeries(manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput,maxAuthorPages:Int=28):List<SeriesCandidate>{
  requireCapability(manifest,SourceCapability.SERIES_DISCOVERY);if(manifest.id!="izib")return emptyList()
  val author=canonical.authors.firstOrNull()?.trim()?.takeIf{it.isNotBlank()}?:return emptyList()
  val expectedSeries=SourceIdentityMatcher.normalizeTitle(canonical.title)
  val session=sandbox.open(manifest)
  val initial=authorTokens(author).lastOrNull()?.firstOrNull()?:return emptyList()
  val encoded=URLEncoder.encode(initial.uppercaseChar().toString(),StandardCharsets.UTF_8.name())
  val pageLimit=maxAuthorPages.coerceIn(1,28)
  fun pageUrl(page:Int)="https://izib.uk/authors?l=$encoded"+(if(page==1)"" else "&p=$page")
  val firstResponse=session.httpGet(pageUrl(1));if(firstResponse.statusCode !in 200..299)return emptyList()
  val firstDocument=Jsoup.parse(firstResponse.body,firstResponse.finalUrl)
  var authorUrl=exactAuthorUrl(firstDocument,author)
  if(authorUrl==null&&pageLimit>1){
   val advertisedMax=firstDocument.select("a[href]").mapNotNull{Regex("[?&]p=(\\d+)").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()}.maxOrNull()?.coerceAtMost(pageLimit)?:1
   if(advertisedMax>1){
    var low=2;var high=advertisedMax;val target=authorSortKey(author);val visited=mutableSetOf(1);var probes=0
    while(authorUrl==null&&low<=high&&probes++<8){
     val page=(low+high)/2;if(!visited.add(page))break
     val response=session.httpGet(pageUrl(page));if(response.statusCode !in 200..299){low=page+1;continue}
     val doc=Jsoup.parse(response.body,response.finalUrl);authorUrl=exactAuthorUrl(doc,author);if(authorUrl!=null)break
     val keys=doc.select("a[href*='/author']").map{authorSortKey(it.text())}.filter{it.isNotBlank()}
     val first=keys.minOrNull();val last=keys.maxOrNull()
     when{first==null||last==null->low=page+1;target<first->high=page-1;target>last->low=page+1;else->{listOf(page-1,page+1).filter{it in 2..advertisedMax&&!visited.contains(it)}.forEach{neighbor->if(authorUrl==null){visited+=neighbor;val nearby=session.httpGet(pageUrl(neighbor));if(nearby.statusCode in 200..299)authorUrl=exactAuthorUrl(Jsoup.parse(nearby.body,nearby.finalUrl),author)}};break}}
    }
   }else{
    for(page in 2..pageLimit){val response=session.httpGet(pageUrl(page));if(response.statusCode !in 200..299)continue;authorUrl=exactAuthorUrl(Jsoup.parse(response.body,response.finalUrl),author);if(authorUrl!=null)break}
   }
  }
  val resolvedAuthorUrl=authorUrl?:return emptyList();val authorResponse=session.httpGet(resolvedAuthorUrl);if(authorResponse.statusCode !in 200..299)return emptyList();val authorDocument=Jsoup.parse(authorResponse.body,authorResponse.finalUrl)
  val exactSeries=authorDocument.select("a[href*='/serie']").asSequence().mapNotNull{link->val title=link.text().trim().takeIf{it.isNotBlank()}?:return@mapNotNull null;if(SourceIdentityMatcher.normalizeTitle(title)!=expectedSeries)return@mapNotNull null;val href=link.attr("href").takeIf{it.isNotBlank()}?:return@mapNotNull null;SeriesCandidate(SourceSeries(sourceId=manifest.id,url=resolveUrl(link,href),title=title,authors=listOf(SourceAuthor(author,resolvedAuthorUrl))))}.distinctBy{SourceKeys.normalizeUrl(it.series.url)}.take(5).toList();if(exactSeries.isNotEmpty()){session.requireOutputSize(exactSeries.size);return exactSeries}
  val refs=mutableListOf<SourceBookRef>();collectCanonicalBookRefs(authorDocument,"a[href*='/art']",manifest,canonical,author,refs);val result=syntheticSeriesCandidate(manifest.id,canonical,author,resolvedAuthorUrl,refs);session.requireOutputSize(result.size);return result
 }

 private fun discoverBazaSeries(manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput):List<SeriesCandidate>{
  val author=canonical.authors.firstOrNull()?.trim()?.takeIf{it.isNotBlank()}?:return emptyList()
  val session=sandbox.open(manifest)
  val tokens=authorTokens(author)
  val surname=tokens.lastOrNull()?:return emptyList()
  val refs=mutableListOf<SourceBookRef>()
  var resolvedAuthorUrl:String?=null
  val query=URLEncoder.encode(author,StandardCharsets.UTF_8.name())
  val search=session.httpGet("https://baza-knig.info/index.php?do=search&subaction=search&story=$query")
  if(search.statusCode in 200..299){
   val doc=Jsoup.parse(search.body,search.finalUrl)
   doc.select("article.abook-item").forEach{card->
    val authorLink=card.selectFirst("a.author-title[href*='/avtor-'], a[href*='/avtor-']")?:return@forEach
    if(!sameAuthor(authorLink.text(),author))return@forEach
    resolvedAuthorUrl=resolveUrl(authorLink,authorLink.attr("href"))
    collectCanonicalBookRefs(card,"a.book-title[href*='/audio-'], h2.abook-title a[href*='/audio-']",manifest,canonical,author,refs)
   }
  }
  if(resolvedAuthorUrl==null){
   val initial=surname.first()
   val encoded=URLEncoder.encode(initial.uppercaseChar().toString(),StandardCharsets.UTF_8.name())
   fun pageUrl(page:Int)="https://baza-knig.info/authors/let-$encoded"+(if(page==1)"" else "?page=$page")
   val first=session.httpGet(pageUrl(1))
   if(first.statusCode in 200..299){
    val firstDoc=Jsoup.parse(first.body,first.finalUrl)
    resolvedAuthorUrl=exactAuthorUrl(firstDoc,author,"a[href*='/avtor-']")
    val maxPage=firstDoc.select("a[href]").mapNotNull{Regex("[?&](?:page|p)=(\\d+)").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()}.maxOrNull()?.coerceAtMost(28)?:1
    if(resolvedAuthorUrl==null&&maxPage>1){
     var low=2
     var high=maxPage
     val target=authorSortKey(author)
     var probes=0
     while(resolvedAuthorUrl==null&&low<=high&&probes++<8){
      val page=(low+high)/2
      val response=session.httpGet(pageUrl(page))
      if(response.statusCode !in 200..299){low=page+1;continue}
      val doc=Jsoup.parse(response.body,response.finalUrl)
      resolvedAuthorUrl=exactAuthorUrl(doc,author,"a[href*='/avtor-']")
      if(resolvedAuthorUrl!=null)break
      val keys=doc.select("a[href*='/avtor-']").map{authorSortKey(it.text())}.filter{it.isNotBlank()}
      val firstKey=keys.minOrNull()
      val lastKey=keys.maxOrNull()
      when{
       firstKey==null||lastKey==null->low=page+1
       target<firstKey->high=page-1
       target>lastKey->low=page+1
       else->break
      }
     }
    }
   }
  }
  val authorUrl=resolvedAuthorUrl?:return emptyList()
  for(page in 1..3){
   val pageUrl=if(page==1)authorUrl else appendQueryParameter(authorUrl,"page",page)
   val response=session.httpGet(pageUrl)
   if(response.statusCode !in 200..299)continue
   val doc=Jsoup.parse(response.body,response.finalUrl)
   collectCanonicalBookRefs(doc,"article.abook-item a.book-title[href*='/audio-'], article.abook-item h2.abook-title a[href*='/audio-']",manifest,canonical,author,refs)
  }
  val result=syntheticSeriesCandidate(manifest.id,canonical,author,authorUrl,refs)
  session.requireOutputSize(result.size)
  return result
 }

 private fun discoverLis10BookSeries(manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput):List<SeriesCandidate>{val author=canonical.authors.firstOrNull()?.trim()?.takeIf{it.isNotBlank()}?:return emptyList();val session=sandbox.open(manifest);val directSeriesUrl="https://lis10book.com/serie/${slugifyRussian(canonical.title)}/";val directResponse=session.httpGet(directSeriesUrl);if(directResponse.statusCode in 200..299){val doc=Jsoup.parse(directResponse.body,directResponse.finalUrl);val refs=mutableListOf<SourceBookRef>();collectLis10BookCards(doc,manifest,canonical,author,refs);if(refs.isNotEmpty())return listOf(SeriesCandidate(SourceSeries(sourceId=manifest.id,url=directResponse.finalUrl,title=canonical.title,authors=listOf(SourceAuthor(author)),books=refs.distinctBy{SourceKeys.normalizeUrl(it.url)})))};return emptyList()}
 private fun collectLis10BookCards(document:Element,manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput,author:String,output:MutableList<SourceBookRef>){document.select("a.mcard[href*='/audio/']").forEach{card->val cardAuthor=card.selectFirst(".mcard-a")?.text()?.trim().orEmpty();if(cardAuthor.isNotBlank()&&!sameAuthor(cardAuthor,author))return@forEach;val href=card.attr("href").takeIf{it.isNotBlank()}?:return@forEach;val bookUrl=canonicalPluginBookUrl(manifest.id,resolveUrl(card,href))?:return@forEach;val title=card.selectFirst(".mcard-t")?.text()?.trim()?.takeIf{it.isNotBlank()}?:return@forEach;val match=SourceIdentityMatcher.bestBookMatch(SourceBook(sourceId=manifest.id,url=bookUrl,title=title,authors=listOf(SourceAuthor(author)),seriesTitle=canonical.title),canonical.books)?.takeIf{it.disposition==MatchDisposition.AUTO_ACCEPT}?:return@forEach;output+=SourceBookRef(url=bookUrl,title=title,number=match.value.number)}}
 private fun collectCanonicalBookRefs(document:Element,selector:String,manifest:PluginPackageManifest,canonical:CanonicalSeriesMatchInput,author:String,output:MutableList<SourceBookRef>){document.select(selector).forEach{link->val href=link.attr("href").takeIf{it.isNotBlank()}?:return@forEach;val bookUrl=canonicalPluginBookUrl(manifest.id,resolveUrl(link,href))?:return@forEach;val rawTitle=link.text().trim().trimStart('★','☆').trim().takeIf{it.isNotBlank()}?:return@forEach;val title=stripAuthorSuffix(rawTitle,author);val match=SourceIdentityMatcher.bestBookMatch(SourceBook(sourceId=manifest.id,url=bookUrl,title=title,authors=listOf(SourceAuthor(author)),seriesTitle=canonical.title),canonical.books)?.takeIf{it.disposition==MatchDisposition.AUTO_ACCEPT}?:return@forEach;output+=SourceBookRef(url=bookUrl,title=title,number=match.value.number)}}
 private fun syntheticSeriesCandidate(sourceId:String,canonical:CanonicalSeriesMatchInput,author:String,authorUrl:String,refs:List<SourceBookRef>):List<SeriesCandidate>{val books=refs.distinctBy{SourceKeys.normalizeUrl(it.url)}.sortedWith(compareBy<SourceBookRef>{it.number?:Double.MAX_VALUE}.thenBy{it.title.orEmpty()});if(books.isEmpty())return emptyList();return listOf(SeriesCandidate(SourceSeries(sourceId=sourceId,url=books.first().url,title=canonical.title,authors=listOf(SourceAuthor(author,authorUrl)),books=books)))}
 private fun exactAuthorUrl(document:Element,author:String,selector:String="a[href*='/author']")=document.select(selector).firstOrNull{sameAuthor(it.text(),author)}?.let{resolveUrl(it,it.attr("href"))}
 private fun sameAuthor(left:String,right:String):Boolean{val candidate=authorTokens(left).toSet();val expected=authorTokens(right).toSet();return candidate.isNotEmpty()&&expected.isNotEmpty()&&expected.all(candidate::contains)}
 private fun authorSortKey(value:String):String{val tokens=authorTokens(value);if(tokens.isEmpty())return "";return (listOf(tokens.last())+tokens.dropLast(1)).joinToString(" ")}
 private fun appendQueryParameter(url:String,name:String,value:Int)=url+(if('?' in url)'&' else '?')+"$name=$value"
 private fun authorTokens(value:String)=SourceIdentityMatcher.normalizeTitle(value).split(Regex("[^\\p{L}\\p{N}]+")).filter{it.isNotBlank()&&it !in setOf("автор","author")}
 private fun stripAuthorSuffix(title:String,author:String):String{var result=title.trim();listOf(author,authorTokens(author).reversed().joinToString(" ")).filter{it.isNotBlank()}.distinct().forEach{result=result.replace(Regex("\\s+${Regex.escape(it)}$",RegexOption.IGNORE_CASE),"").trim()};return result}
 private fun slugifyRussian(value:String):String{val map=mapOf('а' to "a",'б' to "b",'в' to "v",'г' to "g",'д' to "d",'е' to "e",'ё' to "e",'ж' to "zh",'з' to "z",'и' to "i",'й' to "y",'к' to "k",'л' to "l",'м' to "m",'н' to "n",'о' to "o",'п' to "p",'р' to "r",'с' to "s",'т' to "t",'у' to "u",'ф' to "f",'х' to "h",'ц' to "c",'ч' to "ch",'ш' to "sh",'щ' to "shh",'ъ' to "",'ы' to "y",'ь' to "",'э' to "e",'ю' to "yu",'я' to "ya");val out=StringBuilder();value.lowercase().forEach{ch->when{ch in map->out.append(map.getValue(ch));ch.isLetterOrDigit()->out.append(ch);out.isNotEmpty()&&out.last()!='-'->out.append('-')}};return out.toString().trim('-').replace(Regex("-+"),"-")}
 fun resolveBook(manifest:PluginPackageManifest,packageDir:File,url:String):SourceBook?{requireCapability(manifest,SourceCapability.BOOK_LOOKUP);val spec=loadEntrypoint(manifest,packageDir,"bookLookup") as? DeclarativeEntrypoint.BookLookup?:throw PluginSandboxViolation("bookLookup entrypoint has wrong operation");val session=sandbox.open(manifest);val response=session.httpGet(url);if(response.statusCode !in 200..299)return null;val document=Jsoup.parse(response.body,response.finalUrl);var title=extract(document,spec.title)?.takeIf{it.isNotBlank()}?:return null;title=applyRegex(title,spec.titleRegex);return SourceBook(sourceId=manifest.id,remoteId=spec.remoteId?.let{extract(document,it)}?.takeIf{it.isNotBlank()},url=response.finalUrl,title=title,authors=spec.author?.let{extract(document,it)}?.takeIf{it.isNotBlank()}?.let{listOf(SourceAuthor(it))}.orEmpty(),seriesTitle=spec.seriesTitle?.let{extract(document,it)}?.takeIf{it.isNotBlank()},seriesNumber=spec.seriesNumber?.let{extract(document,it)}?.let(::parseNumber),coverUrl=spec.coverUrl?.let{extract(document,it)}?.takeIf{it.isNotBlank()}?.let{resolveUrl(document,it)},description=spec.description?.let{extract(document,it)}?.takeIf{it.isNotBlank()})}
 fun resolveDownloads(manifest:PluginPackageManifest,packageDir:File,url:String):List<DownloadCandidate>{requireCapability(manifest,SourceCapability.DOWNLOAD_RESOLUTION);val spec=loadEntrypoint(manifest,packageDir,"downloadResolution") as? DeclarativeEntrypoint.DownloadResolution?:throw PluginSandboxViolation("downloadResolution entrypoint has wrong operation");val session=sandbox.open(manifest);val response=session.httpGet(url);if(response.statusCode !in 200..299)return emptyList();val document=Jsoup.parse(response.body,response.finalUrl);val results=document.select(spec.items.item).mapNotNull{item->val raw=extract(item,spec.items.link)?.takeIf{it.isNotBlank()}?:return@mapNotNull null;DownloadCandidate(spec.type,resolveUrl(item,raw),spec.fileName)}.distinctBy{it.url};session.requireOutputSize(results.size);return results}
 private fun loadSupplementRefs(session:PluginSandboxSession,seriesDocument:Element,expectedTitle:String,supplement:SeriesSupplement):List<SourceBookRef>{val start=extract(seriesDocument,supplement.startLink)?.takeIf{it.isNotBlank()}?:return emptyList();var nextUrl:String?=resolveUrl(seriesDocument,start);val expected=SourceIdentityMatcher.normalizeTitle(expectedTitle);val results=mutableListOf<SourceBookRef>();val visited=hashSetOf<String>();repeat(supplement.maxPages){val current=nextUrl?:return@repeat;if(!visited.add(current))return@repeat;val response=session.httpGet(current);if(response.statusCode !in 200..299)return@repeat;val document=Jsoup.parse(response.body,response.finalUrl);document.select(supplement.items.item).forEach{item->val itemSeries=extract(item,supplement.seriesTitle)?.takeIf{it.isNotBlank()}?.let(SourceIdentityMatcher::normalizeTitle);if(itemSeries!=expected)return@forEach;val link=extract(item,supplement.items.link)?.takeIf{it.isNotBlank()}?:return@forEach;results+=SourceBookRef(remoteId=supplement.items.remoteId?.let{extract(item,it)}?.takeIf{it.isNotBlank()},url=resolveUrl(item,link),title=supplement.items.title?.let{extract(item,it)}?.takeIf{it.isNotBlank()},number=supplement.items.number?.let{extract(item,it)}?.let(::parseNumber))};nextUrl=supplement.nextPage?.let{extract(document,it)}?.takeIf{it.isNotBlank()}?.let{resolveUrl(document,it)}};session.requireOutputSize(results.size);return results}
 private fun extractBookRefs(document:Element,fields:RepeatedFields)=document.select(fields.item).mapNotNull{item->val link=extract(item,fields.link)?.takeIf{it.isNotBlank()}?:return@mapNotNull null;SourceBookRef(remoteId=fields.remoteId?.let{extract(item,it)}?.takeIf{it.isNotBlank()},url=resolveUrl(item,link),title=fields.title?.let{extract(item,it)}?.takeIf{it.isNotBlank()},number=fields.number?.let{extract(item,it)}?.let(::parseNumber))}
 private fun loadEntrypoint(manifest:PluginPackageManifest,packageDir:File,name:String):DeclarativeEntrypoint{val relative=manifest.entrypoints[name]?:throw PluginSandboxViolation("Missing $name entrypoint");if(!PluginPackagePolicy.isSafeRelativePath(relative))throw PluginSandboxViolation("Unsafe entrypoint path");val root=packageDir.canonicalFile.toPath();val file=File(packageDir,relative).canonicalFile;if(!file.toPath().startsWith(root))throw PluginSandboxViolation("Entrypoint escapes package directory");if(!file.isFile)throw PluginSandboxViolation("Entrypoint file is missing");if(file.length()>MAX_PLUGIN_ENTRYPOINT_BYTES)throw PluginSandboxViolation("Entrypoint file exceeds size limit");return decoder.decode(file.readText())}
 private fun requireCapability(manifest:PluginPackageManifest,capability:SourceCapability){if(capability !in manifest.capabilities)throw PluginSandboxViolation("Plugin did not declare $capability")}
 private fun extract(element:Element,expression:String):String?{expression.split("||").map{it.trim()}.filter{it.isNotBlank()}.forEach{alternative->val(selector,attribute)=splitSelectorAttribute(alternative);val target=if(selector.isBlank())element else element.selectFirst(selector)?:return@forEach;val value=when{attribute==null->target.text();attribute.equals("text",true)->target.text();else->target.attr(attribute)}.trim();if(value.isNotBlank())return value};return null}
 private fun splitSelectorAttribute(expression:String):Pair<String,String?>{val marker=expression.lastIndexOf('@');return if(marker<0)expression to null else expression.substring(0,marker).trim() to expression.substring(marker+1).trim()}
 private fun resolveUrl(element:Element,raw:String)=element.baseUri().let{base->runCatching{java.net.URI(base).resolve(raw).toString()}.getOrDefault(raw)}
 private fun applyRegex(value:String,pattern:String?):String{if(pattern.isNullOrBlank())return value.trim();val match=Regex(pattern).find(value)?:return value.trim();return match.groupValues.getOrNull(1)?.takeIf{it.isNotBlank()}?.trim()?:match.value.trim()}
 private fun parseNumber(value:String)=Regex("-?[0-9]+(?:[.,][0-9]+)?").find(value)?.value?.replace(',','.')?.toDoubleOrNull()
}
