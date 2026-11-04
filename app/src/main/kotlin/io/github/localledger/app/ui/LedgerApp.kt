package io.github.localledger.app.ui
import io.github.localledger.app.displayName
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localledger.app.data.LedgerRow
import io.github.localledger.app.data.LedgerSnapshot
import io.github.localledger.core.TransactionType
import io.github.localledger.core.Money
import io.github.localledger.core.*
import java.util.UUID
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val tabs get() = listOf(appString(R.string.ui_entries_4bf3c), appString(R.string.ui_statistics), appString(R.string.ui_settings))
private fun yuan(minor: BigInteger) = BigDecimal(minor, 2).toPlainString()
private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val dayFormat get() = DateTimeFormatter.ofPattern("yyyy-MM-dd E", io.github.localledger.app.AppStrings.locale)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val listDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun time(row: LedgerRow) = Instant.ofEpochMilli(row.occurredAtMs).atZone(ZoneId.systemDefault())
private fun signedAmount(type: TransactionType, amount: Money) = "${when(type) { TransactionType.EXPENSE -> "−"; TransactionType.INCOME -> "+"; else -> "↔" }} ¥ ${amount.decimal().toPlainString()}"
private fun signedAmount(row: LedgerRow) = signedAmount(row.type, row.amount)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LedgerApp(model: LedgerViewModel, onAccessibility: () -> Unit, onOverlay: () -> Unit,
              onImport: () -> Unit, onExport: () -> Unit,
              onNotifications: (Boolean) -> Unit = model.graph::setNotificationEnabled,
              onNotificationSettings: () -> Unit = {}, onLanguageSettings: () -> Unit = {}, onBatterySettings:()->Unit={}) {
    val state by model.state.collectAsStateWithLifecycle()
    val permissions by model.permissions.collectAsStateWithLifecycle()
    val automatic by model.graph.enabled.collectAsStateWithLifecycle()
    val pending by model.graph.pending.collectAsStateWithLifecycle()
    val busy by model.graph.busy.collectAsStateWithLifecycle()
    val actionError by model.graph.actionError.collectAsStateWithLifecycle()
    val selected by model.graph.selected.collectAsStateWithLifecycle()
    val preview by model.graph.preview.collectAsStateWithLifecycle()
    val query by model.graph.query.collectAsStateWithLifecycle()
    val notice by model.graph.notice.collectAsStateWithLifecycle()
    val update by model.updates.state.collectAsStateWithLifecycle()
    val quickEntry by model.graph.quickEntry.collectAsStateWithLifecycle()
    val appearance by model.graph.appearance.collectAsStateWithLifecycle()
    var appearanceChooser by rememberSaveable { mutableStateOf(false) }
    var categoryDisplayChooser by rememberSaveable { mutableStateOf(false) }
    var monthChooser by rememberSaveable { mutableStateOf(false) }
    var rangeChooser by rememberSaveable { mutableStateOf(false) }
    var bookChooser by rememberSaveable { mutableStateOf(false) }
    var categoryFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFocusType by rememberSaveable { mutableStateOf(TransactionType.EXPENSE.name) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editCandidate by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var about by rememberSaveable { mutableStateOf(false) }
    var manualMode by rememberSaveable { mutableStateOf<String?>(null) }
    var formUid by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var continuation by rememberSaveable(stateSaver = EntryContinuationSaver) { mutableStateOf<EntryDraft?>(null) }
    var editRevision by rememberSaveable { mutableLongStateOf(0) }
    var manager by rememberSaveable { mutableStateOf<String?>(null) }
    var managerHasDraft by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val catalog = state.ledger.catalog
    val automaticStatus by model.graph.automaticStatus.collectAsStateWithLifecycle()
    val runtimePermissions by model.graph.runtimePermissions.collectAsStateWithLifecycle()
    val daily = remember(state.ledger.days) { state.ledger.days.associateBy { it.key } }
    var statisticKind by rememberSaveable(query) { mutableStateOf<String?>(null) }
    var statisticsTrend by rememberSaveable { mutableStateOf(false) }
    var statisticsList by rememberSaveable { mutableStateOf(false) }
    val statisticType = query.type?.takeIf { it!=TransactionType.TRANSFER } ?: statisticKind?.let(TransactionType::valueOf)
        ?: if (state.ledger.expense == BigInteger.ZERO && state.ledger.income > BigInteger.ZERO) TransactionType.INCOME else TransactionType.EXPENSE
    val ledgerScroll = rememberLazyListState()
    val statsScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val categoryScroll = rememberLazyListState()
    LaunchedEffect(categoryFocus, categoryFocusType) { if (categoryFocus != null) categoryScroll.scrollToItem(0) }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val editing = editCandidate && pending != null
    val detail = selected?.takeIf { it.id == selectedId } ?: state.ledger.rows.firstOrNull { it.id == selectedId }
    val context = LocalContext.current
    LaunchedEffect(quickEntry, state.loading, busy) {
        if (quickEntry != 0L && !state.loading && !busy) {
            val blocked = managerHasDraft || filters || preview != null || editCandidate
            if (!blocked && manualMode == null) {
                manager = null; managerHasDraft = false
                appearanceChooser = false; categoryDisplayChooser=false;monthChooser = false; bookChooser = false; about = false
                formUid = UUID.randomUUID().toString(); selectedId = null; continuation = null; manualMode = "new"; model.graph.clearActionError()
            }
            model.graph.consumeQuickEntry(blocked, when {
                preview != null -> appString(R.string.ui_finish_or_close_the_import_preview_first)
                filters -> appString(R.string.ui_apply_the_filters_or_return_to_entries_first)
                editCandidate -> appString(R.string.ui_save_or_cancel_the_pending_entry_first)
                else -> appString(R.string.ui_save_or_cancel_the_current_edit_first)
            })
        }
    }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val billPanes = ledgerShowsTwoPanes() && tab==0 && !editing && manualMode==null && manager==null && !filters && preview==null && categoryFocus==null
    val subpage = editing || (selectedId != null && !billPanes) || manualMode != null || manager != null || filters || preview != null || categoryFocus != null
    val goBack = back@{
        if (busy || pending?.saving == true) return@back
        when {
            preview != null -> model.graph.cancelPreview()
            manualMode != null -> { manualMode = null; model.graph.clearActionError() }
            manager != null -> { manager = null; model.graph.clearActionError() }
            filters -> filters = false
            editing -> editCandidate = false
            categoryFocus != null && selectedId == null -> categoryFocus = null
            else -> { selectedId = null; model.graph.selectEntry(null) }
        }
    }
    BackHandler(subpage || selectedId!=null, onBack = goBack)
    LaunchedEffect(selectedId) { model.graph.selectEntry(selectedId) }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(LedgerNoticeVisuals(it)); model.graph.clearNotice(it.id) } }
    LaunchedEffect(update.event?.id) {
        update.event?.let { event ->
            val message = when (event.message) {
                io.github.localledger.app.update.UpdateMessage.AVAILABLE -> appString(R.string.ui_update_available, update.releases.first().version.toString())
                io.github.localledger.app.update.UpdateMessage.CURRENT -> appString(R.string.ui_no_updates)
                io.github.localledger.app.update.UpdateMessage.FAILED -> appString(R.string.ui_update_failed)
            }
            val action = if (event.message == io.github.localledger.app.update.UpdateMessage.AVAILABLE) appString(R.string.ui_view_update) else null
            if (snackbar.showSnackbar(message, actionLabel = action) == SnackbarResult.ActionPerformed) about = true
            model.updates.consumeEvent(event.id)
        }
    }
    LedgerNavigationFrame(tab, !subpage, { tab = it;selectedId=null;model.graph.selectEntry(null) }) {
    Scaffold(
        topBar = { if (manualMode != null) { /* Entry page owns its compact header. */ }
        else if (!subpage && tab in 0..1) BrowseToolbar(
            catalog.books.find{it.id==query.bookId}?.name ?: appString(R.string.ui_all_ledgers),query,!busy,
            {bookChooser=true},{monthChooser=true},model.graph::setQuery,
            if(tab==0)({continuation=null;manualMode="new";formUid=UUID.randomUUID().toString();model.graph.clearActionError()}) else null,
            {filters=true}) else CenterAlignedTopAppBar(
            title = { if (!subpage && tab in 0..1) TextButton({ monthChooser = true }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = appString(R.string.ui_choose_month) }) {
                    Text(query.month()?.toString() ?: appString(R.string.ui_all_time), style = MaterialTheme.typography.titleMedium)
                } else Text(when {
                preview != null -> appString(R.string.ui_import_preview); manualMode == "new" -> appString(R.string.ui_add_entry); manualMode == "edit" -> appString(R.string.ui_edit_entry)
                manager != null -> catalogTitle(manager!!); filters -> appString(R.string.ui_filter); editing -> appString(R.string.ui_confirm_entry)
                selectedId != null -> appString(R.string.ui_entry_details_e4c70); categoryFocus != null -> appString(R.string.ui_category_entries); else -> tabs[tab]
            },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { if (subpage) IconButton({ backDispatcher?.onBackPressed() ?: goBack() }, enabled = !busy && pending?.saving != true) {
                Icon(LedgerIcons.Back, appString(R.string.ui_back))
            } else if (tab in 0..1) TextButton({ bookChooser = true }, enabled = !busy,
                modifier = Modifier.widthIn(max = 100.dp).semantics { contentDescription = appString(R.string.ui_choose_ledger) }) {
                    Text(catalog.books.find { it.id == query.bookId }?.name ?: appString(R.string.ui_all_ledgers), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
            colors=TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),
            actions = {
                if (!subpage && tab == 0) IconButton({ continuation = null; manualMode = "new"; formUid = UUID.randomUUID().toString(); model.graph.clearActionError() }, enabled = !busy) { Icon(LedgerIcons.Add, appString(R.string.ui_add_entry)) }
                if (!subpage && tab in 0..1) IconButton({ filters = true }, enabled = !busy) { Icon(LedgerIcons.Filter, appString(R.string.ui_filter)) }
                if (selectedId != null && detail != null && manualMode == null && preview == null) TextButton({
                    manualMode = "edit"; formUid = UUID.randomUUID().toString(); editRevision = detail.revision; model.graph.clearActionError()
                }, enabled = !busy) { Text(appString(R.string.ui_edit)) }
            },
        ) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (subpage) 72.dp else 0.dp)) { LedgerSnackbar(it) } },

    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            val candidate = pending
            val dataPreview = preview
            val browse: @Composable ()->Unit = {
                val dayByKey=remember(state.ledger.rows) { buildMap<Any,java.time.LocalDate> {
                    state.ledger.rows.forEach { row->val day=time(row).toLocalDate();put("day:$day",day);put("entry:${row.id}",day) }
                } }
                val pinnedDay by remember(dayByKey,ledgerScroll) { derivedStateOf {
                    val layout=ledgerScroll.layoutInfo
                    val leading=layout.visibleItemsInfo.firstOrNull { it.offset+it.size>layout.viewportStartOffset }
                    leading?.takeIf { it.offset<=layout.viewportStartOffset }?.let { dayByKey[it.key] }
                } }
                var pinnedHeight by remember { mutableIntStateOf(0) }
                Box(Modifier.widthIn(max=if(tab==1)1200.dp else 720.dp).fillMaxWidth()) {
                LazyColumn(
                state = when (tab) { 0 -> ledgerScroll; 1 -> statsScroll; else -> settingsScroll },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (tab in 0..1 && query.hasConditions) item { ActiveFilters(query, catalog, !busy, { filters = true }, { model.graph.setQuery(query.clearConditions()) }) }
                state.error?.let { error -> item { Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(localizedMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(model.graph::refresh) { Text(appString(R.string.ui_retry)) }
                } } }
                actionError?.let { error -> item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedMessage(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(model.graph::clearActionError) { Text(appString(R.string.ui_close)) }
                } } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (candidate != null) item { Section(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { editCandidate = true }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appString(R.string.ui_pending, signedAmount(candidate.candidate.type, candidate.candidate.amount)), style = MaterialTheme.typography.titleSmall)
                        Text(candidate.candidate.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                when (tab) {
                    0 -> {
                        if (state.ledger.count > 0 || query.month() != null) item { PeriodOverview(state.ledger,Modifier.testTag("bills-summary")) }
                        if (state.loading && state.ledger.count == 0) item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else if (state.ledger.count == 0) item { LedgerEmptyState(query, !automatic || !permissions.ready,
                            { continuation = null; manualMode = "new"; formUid = UUID.randomUUID().toString(); model.graph.clearActionError() },
                            { model.graph.setQuery(query.clearConditions()) }, { model.graph.setQuery(query.clearConditions().withMonth(null)) }, { tab = 2 }) }
                        val groups = state.ledger.rows.groupBy { time(it).toLocalDate() }
                        groups.forEach { (day, rows) ->
                            item(key = "day:$day", contentType = "date") {
                                val covered by remember(ledgerScroll,day,pinnedHeight) { derivedStateOf {
                                    val offset=ledgerScroll.layoutInfo.visibleItemsInfo.firstOrNull{it.key=="day:$day"}?.offset
                                    offset!=null && offset>0 && offset<pinnedHeight
                                } }
                                Surface(Modifier.alpha(if(covered)0f else 1f)) { DayStatement(day.format(dayFormat), daily[day.toString()]?.totals) }
                            }
                            itemsIndexed(rows, key = { _,row -> "entry:${row.id}" }, contentType = { _,_ -> "entry" }) { index,row ->
                                TransactionLine(row, largeText, catalog,dayGroup=true,dayStart=index==0,dayEnd=index==rows.lastIndex) { selectedId = row.id; model.graph.selectEntry(row.id) }
                            }
                        }
                        if (state.ledger.rows.size < state.ledger.count) item {
                            TextButton(model.graph::loadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.ui_load_more)) }
                        }
                    }
                    1 -> { item { Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PeriodOverview(state.ledger,Modifier.testTag("statistics-summary"))
                        StatisticsControls(statisticsTrend || query.type==TransactionType.TRANSFER,statisticsList,query.type!=TransactionType.TRANSFER,
                            {statisticsTrend=it},{statisticsList=it})
                    } }
                        if(statisticsTrend || query.type==TransactionType.TRANSFER) item { StatisticsTrend(state.ledger,statisticsList) { first,last ->
                            model.graph.setQuery(query.inMonthRange(java.time.YearMonth.from(first),java.time.YearMonth.from(last)));tab=0
                        } } else {
                        val changeType:(()->Unit)?=if(query.type==null)({statisticKind=(if(statisticType==TransactionType.EXPENSE)TransactionType.INCOME else TransactionType.EXPENSE).name}) else null
                        val categoryTotal = if (statisticType == TransactionType.EXPENSE) state.ledger.expense else state.ledger.income
                        if(!statisticsList)item {
                            val slices=compositionSlices(state.ledger.categories,statisticType)
                            CompositionOverview(slices,statisticType,catalog,onTypeChange=changeType){slice->
                                if(slice.name==null)statisticsList=true else {categoryFocus=slice.name;categoryFocusType=statisticType.name}
                            }
                        } else {
                            item { Row(Modifier.fillMaxWidth().padding(horizontal=LedgerSpacing.page),horizontalArrangement=Arrangement.End) {
                                TextButton(onClick={changeType?.invoke()},enabled=changeType!=null,modifier=Modifier.heightIn(min=LedgerSpacing.touch).testTag("statistics-type-toggle")) {
                                    Text(typeTitle(statisticType));if(changeType!=null){Spacer(Modifier.width(4.dp));Icon(LedgerIcons.Swap,null,Modifier.size(16.dp))}
                                }
                            } }
                            items(state.ledger.categories.filter { it.type == statisticType }) { item -> CategoryStatistic(item.name, item.amount, categoryTotal, item.count,
                            catalog.categories.filter { it.displayName == item.name && it.type == item.type }.map { it.icon }.distinct().singleOrNull()) {
                            categoryFocus = item.name; categoryFocusType = item.type.name
                        } }
                        }
                        }
                    }
                    2 -> item { SettingsHome(automaticStatus.title, appearance.title, busy,
                        { manager = it; model.graph.clearActionError() }, { appearanceChooser = true }, onImport, onExport, { about = true }, onLanguageSettings,{categoryDisplayChooser=true}) }

                }
            }
                if(tab==0 && pinnedDay!=null)Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .testTag("pinned-day").onSizeChanged{pinnedHeight=it.height},color=MaterialTheme.colorScheme.surface) {
                    DayStatement(pinnedDay!!.format(dayFormat),daily[pinnedDay.toString()]?.totals)
                }
                }
            }
            if (dataPreview != null) ImportPreviewScreen(dataPreview, model.graph)
            else if (manager == "licenses") LicenseScreen()
            else if (manager == "automatic") Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Section(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    AutomaticControls(model.graph, permissions.ready, model::enable, onNotifications, onNotificationSettings)
                }
                Section(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SettingLine(appString(R.string.ui_accessibility), if (permissions.accessibility) appString(R.string.ui_enabled) else appString(R.string.ui_disabled), onAccessibility)
                    SettingLine(appString(R.string.ui_display_over_other_apps), if (permissions.overlay) appString(R.string.ui_allowed) else appString(R.string.ui_not_allowed), onOverlay)
                    SettingsDivider()
                    SettingLine(appString(R.string.ui_background_battery), appString(if(runtimePermissions.batteryUnrestricted)R.string.ui_battery_unrestricted else R.string.ui_battery_managed),onBatterySettings)
                }
            }
            else if (manager != null) CatalogManager(manager!!, catalog, model.graph, onDraftOpen = { managerHasDraft = it })
            else if (filters) LedgerFilters(query, catalog) { model.graph.setQuery(it); filters = false }
            else if (manualMode != null) {
                if (manualMode == "edit" && detail == null) CircularProgressIndicator()
                else {
                    val initial = remember(formUid) { if (manualMode == "edit") detail!!.draft() else continuation ?: run {
                        val choice = SelectionPolicy.resolve(catalog, state.ledger.selection, TransactionType.EXPENSE, query.bookId)
                        EntryDraft(TransactionType.EXPENSE, Money(1), "", System.currentTimeMillis(), bookId = choice.bookId,
                            categoryId = choice.categoryId, accountId = choice.accountId, affectsBalance = choice.affectsBalance)
                    } }
                    Box(Modifier.widthIn(max = 1080.dp).fillMaxSize().imePadding()) {
                        EntryForm(initial, formUid, catalog, busy, actionError, newRecord = manualMode == "new", memory = state.ledger.selection,
                            onSaveAnother = if (manualMode == "new") { draft ->
                                model.graph.saveDraft(draft, null, 0, formUid) {
                                    continuation = draft.continuation(); formUid = UUID.randomUUID().toString()
                                }
                            } else null,
                            pageLayout = true, onSave = { draft ->
                            val editingId = if (manualMode == "edit") selectedId else null
                            model.graph.saveDraft(draft, editingId, editRevision, formUid) { id ->
                                manualMode = null; selectedId = id; model.graph.selectEntry(id)
                            }
                        }, onCancel = { manualMode = null; model.graph.clearActionError() })
                    }
                }
            } else if (editing && candidate != null) Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().imePadding()) {
                CandidateEditor(candidate, { model.graph.confirm(candidate.token, it) }, {
                    model.graph.discard(candidate.token); editCandidate = false
                }, showTitle = false, catalog = catalog, memory = state.ledger.selection, onRetry = { model.graph.retryPreparation(candidate.token) })
            } else if (billPanes) LedgerBillPanes(browse) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                    if(detail!=null)Column {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(appString(R.string.ui_entry_details_e4c70),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            TextButton({manualMode="edit";formUid=UUID.randomUUID().toString();editRevision=detail.revision;model.graph.clearActionError()},enabled=!busy){Text(appString(R.string.ui_edit))}
                            IconButton({selectedId=null;model.graph.selectEntry(null)}){Icon(LedgerIcons.Close,appString(R.string.ui_close))}
                        }
                        TransactionDetails(detail,catalog,busy,actionError){deleteConfirm=true}
                    } else Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=8.dp)) {
                        Text(appString(R.string.ui_category_share),Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.titleMedium)
                        CompositionOverview(compositionSlices(state.ledger.categories,statisticType),statisticType,catalog){slice->
                            if(slice.name==null){tab=1;statisticsList=true}else{categoryFocus=slice.name;categoryFocusType=statisticType.name}
                        }
                    }
                }
            } else if (selectedId != null) {
                if (detail != null) TransactionDetails(detail, catalog, busy, actionError) { deleteConfirm = true }
                else Column(Modifier.padding(24.dp)) {
                    if (state.loading) CircularProgressIndicator() else Text(appString(R.string.ui_entry_unavailable))
                    actionError?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            else if (categoryFocus != null) CategoryBills(categoryFocus!!, TransactionType.valueOf(categoryFocusType), query, catalog, model.graph, categoryScroll,
                { id -> selectedId = id; model.graph.selectEntry(id) })
            else browse()
        }
    }
    }
    LaunchedEffect(pending?.token) { if (pending == null) editCandidate = false }
    if (appearanceChooser) AppearanceChooser(appearance, { model.graph.setAppearance(it); appearanceChooser = false }, { appearanceChooser = false })
    if(categoryDisplayChooser)CategoryDisplayChooser(LocalCategoryDisplay.current,{model.graph.setCategoryDisplay(it);categoryDisplayChooser=false},{categoryDisplayChooser=false})
    if(monthChooser)MonthChooser(query,{model.graph.setQuery(it);monthChooser=false},{monthChooser=false},onCustom={monthChooser=false;rangeChooser=true})
    if (rangeChooser) PeriodRangeEditor(query,{model.graph.setQuery(it);rangeChooser=false},{rangeChooser=false})
    if (bookChooser) BookChooser(catalog, query.bookId, {
        model.graph.setQuery(query.copy(bookId = it)); bookChooser = false
    }, { bookChooser = false })
    if (about && manager != "licenses") AboutDialog(version, { manager = "licenses" }, { about = false },
        update, { model.updates.check(manual = true) }, model.updates::setAutomatic, { release ->
            if (io.github.localledger.app.update.isReleasePage(release)) {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(release.page))) }
                catch (_: android.content.ActivityNotFoundException) { model.graph.updatePageUnavailable() }
            }
        })
    if (deleteConfirm && detail != null) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text(appString(R.string.ui_delete_this_entry)) },
        text = { Text("${entryTitle(detail, catalog)}\n${signedAmount(detail)}") },
        confirmButton = { TextButton({
            deleteConfirm = false; model.graph.delete(detail) { selectedId = null; model.graph.selectEntry(null) }
        }, enabled = !busy) { Text(appString(R.string.ui_delete)) } }, dismissButton = { TextButton({ deleteConfirm = false }) { Text(appString(R.string.ui_cancel)) } })
}

@Composable
private fun PeriodOverview(ledger:LedgerSnapshot,modifier:Modifier=Modifier) {
    Section(modifier.padding(horizontal=12.dp,vertical=8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(appString(R.string.ui_total_entries,ledger.count),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            StatementMetrics(ledger.cashFlow.metrics(ledger.query.type))
            if (ledger.query.type == null && ledger.cashFlow.transferCount > 0) {
                Text(appString(R.string.ui_transferred_net_fees, statementAmount(ledger.cashFlow.transfer), statementAmount(ledger.cashFlow.transferCost)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CategoryStatistic(name: String, amount: BigInteger, total: BigInteger, count: Int, icon: String? = null,
                               color: Color?=null,aggregate: Boolean=false,compact:Boolean=false,onClick: () -> Unit) {
    val ratio = if (total.signum() <= 0) java.math.BigDecimal.ZERO else BigDecimal(amount).divide(BigDecimal(total), 8, java.math.RoundingMode.HALF_UP)
    val accent=color ?: categoryTint(icon ?: CategoryPresets.symbol(name),LocalLedgerDark.current)
    Surface(Modifier.padding(horizontal=12.dp,vertical=4.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.small) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = appString(if(aggregate)R.string.ui_all_categories else R.string.ui_view_category_entries), onClick = onClick)
        .heightIn(min = if(compact)56.dp else 68.dp).padding(horizontal = 12.dp, vertical = if(compact)6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(if(aggregate)LedgerIcons.Statements else CategoryGlyphs[icon ?: CategoryPresets.symbol(name)],null,Modifier.size(if(compact)20.dp else 24.dp),tint=accent)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if(compact)2.dp else 4.dp)) {
        val stacked = LocalDensity.current.fontScale > 1.3f || yuan(amount).length > 16
        if (stacked) { Text(name); Text("¥ ${yuan(amount)}", style = MaterialTheme.typography.titleMedium) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, Modifier.weight(1f).alignByBaseline(),style=if(compact)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge); Text("¥ ${yuan(amount)}", Modifier.alignByBaseline(),style = if(compact)MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(ratio.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)))
        }
        Text(appString(R.string.ui_count_83eda, ratio.movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString(), count),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    }
}

@Composable
internal fun TransactionLine(row: LedgerRow, largeText: Boolean, catalog: LedgerCatalog, showDate: Boolean = false,
                             dayGroup: Boolean=false,dayStart: Boolean=false,dayEnd: Boolean=false,onClick: () -> Unit) {
    val amount = signedAmount(row)
    val stacked = largeText || amount.length > 18
    val shape=if(dayGroup)RoundedCornerShape(topStart=if(dayStart)16.dp else 0.dp,topEnd=if(dayStart)16.dp else 0.dp,
        bottomStart=if(dayEnd)16.dp else 0.dp,bottomEnd=if(dayEnd)16.dp else 0.dp) else androidx.compose.ui.graphics.RectangleShape
    Surface(Modifier.padding(horizontal=if(dayGroup)12.dp else 0.dp).padding(bottom=if(dayEnd)8.dp else 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,shape=shape) {
      Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = appString(R.string.ui_view_entries), onClick = onClick)
            .heightIn(min = LedgerSpacing.transaction).padding(horizontal = if(dayGroup)12.dp else LedgerSpacing.page, vertical = LedgerSpacing.related),
            horizontalArrangement = Arrangement.spacedBy(LedgerSpacing.group), verticalAlignment = Alignment.CenterVertically) {
          val category = if (row.type == TransactionType.TRANSFER) null else io.github.localledger.app.CatalogLabels.category(catalog, row.categoryId, row.type)
          if(row.type==TransactionType.TRANSFER)Icon(LedgerIcons.Statements,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
          else CategoryBadge(category.orEmpty(),Modifier.size(32.dp).testTag("entry-category-${row.id}"),icon=catalog.categories.find{it.id==row.categoryId}?.icon)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (stacked) {
                Text(entryTitle(row, catalog), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AmountText(row, amount)
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entryTitle(row, catalog), Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                AmountText(row, amount,Modifier.alignByBaseline())
            }
            val title = entryTitle(row, catalog)
            Text(listOfNotNull(row.merchant.takeIf { it.isNotBlank() && it != title }, time(row).format(if (showDate) listDateFormat else timeFormat),
                catalog.accounts.find { it.id == row.accountId }?.name?.takeUnless { it == title || row.type == TransactionType.TRANSFER && row.merchant.isBlank() },category?.takeUnless { it == title }).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
        if(dayGroup && !dayEnd)HorizontalDivider(Modifier.padding(start=56.dp,end=12.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant)
      }
    }
}
@Composable
private fun AmountText(row: LedgerRow, value: String, modifier: Modifier=Modifier) {
    Text(value,modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = financialColors(LocalLedgerDark.current).let { when(row.type) {TransactionType.INCOME->it.income;TransactionType.EXPENSE->it.expense;TransactionType.TRANSFER->it.transfer} })
}
@Composable
private fun StatLine(label: String, value: String, prominent: Boolean = false) {
    val stacked = LocalDensity.current.fontScale > 1.3f || value.length > 16
    val modifier = Modifier.fillMaxWidth().padding(16.dp)
    val amount: @Composable () -> Unit = { Text("¥ $value", style = MaterialTheme.typography.titleMedium,
        fontWeight = if (prominent) FontWeight.Bold else FontWeight.Medium) }
    if (stacked) Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label); amount() }
    else Row(modifier, verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); amount() }
}
@Composable
private fun TransactionDetails(row: LedgerRow, catalog: LedgerCatalog, busy: Boolean, error: String?, onDelete: () -> Unit) {
    var original by rememberSaveable(row.uid) { mutableStateOf(false) }
    val raw = sourceDetails(row.source, row.rawSource)
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        item { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entryTitle(row,catalog),style=MaterialTheme.typography.titleLarge)
                Text(typeTitle(row.type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(signedAmount(row), style = MaterialTheme.typography.headlineSmall)
            }
        } }
        if (row.merchant.isNotBlank() && row.merchant!=entryTitle(row,catalog)) item { DetailField(appString(R.string.ui_counterparty), row.merchant) }
        item { DetailField(appString(R.string.ui_ledger), catalog.books.find { it.id == row.bookId }?.name.orEmpty()) }
        row.categoryId?.let { id -> item { DetailField(appString(R.string.ui_category), catalog.categories.find { it.id == id }?.displayName.orEmpty()) } }
        item { DetailField(appString(R.string.ui_time), time(row).format(fullDate)) }
        item { DetailField(if (row.type == TransactionType.TRANSFER) appString(R.string.ui_from_account) else appString(R.string.ui_account), catalog.accounts.find { it.id == row.accountId }?.name ?: appString(R.string.ui_unassigned)) }
        if (row.type == TransactionType.TRANSFER) {
            item { DetailField(appString(R.string.ui_to_account), catalog.accounts.find { it.id == row.toAccountId }?.name.orEmpty()) }
            item { DetailField(appString(R.string.ui_fee), "¥ ${row.fee.decimal().toPlainString()}") }
            item { DetailField(appString(R.string.ui_discount), "¥ ${row.discount.decimal().toPlainString()}") }
            item { DetailField(appString(R.string.ui_received_f6895), "¥ ${BigDecimal(row.draft().transferReceived, 2).toPlainString()}") }
        }
        if (row.accountId != null) item { DetailField(appString(R.string.ui_update_balance), if (row.affectsBalance) appString(R.string.ui_yes) else appString(R.string.ui_no)) }
        if (raw.isNotEmpty()) item { TextButton({ original = !original }) { Text(if (original) appString(R.string.ui_hide_original_fields) else appString(R.string.ui_original_fields)) } }
        if (original) items(raw) { DetailField(it.first, it.second) }
        item { error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onDelete, enabled = !busy) { Text(appString(R.string.ui_delete_entry)) } }
    }
}
@Composable
private fun DetailField(label: String, value: String) {
    if (LocalDensity.current.fontScale > 1.3f || label.length > 8 || value.length > 28) Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun entryTitle(row: LedgerRow, catalog: LedgerCatalog) = row.note.ifBlank { row.merchant.ifBlank {
    if (row.type == TransactionType.TRANSFER) "${catalog.accounts.find { it.id == row.accountId }?.name.orEmpty()} → ${catalog.accounts.find { it.id == row.toAccountId }?.name.orEmpty()}"
    else typeTitle(row.type)
} }
private fun scopeTitle(query: LedgerQuery, catalog: LedgerCatalog = LedgerCatalog()): String = listOfNotNull(
    if (query.fromMs == null && query.untilMs == null) appString(R.string.ui_total) else query.periodTitle(),
    catalog.books.find { it.id == query.bookId }?.name,
).distinct().joinToString(" · ")
