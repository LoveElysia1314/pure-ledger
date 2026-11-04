package io.github.localledger.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteConstraintException
import java.io.ByteArrayOutputStream
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import io.github.localledger.core.migration.*
import io.github.localledger.app.data.LedgerSnapshot
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.data.SaveResult
import io.github.localledger.core.ConfirmedEntry
import io.github.localledger.core.TransactionCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

open class LedgerApplication : Application() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLanguages.wrap(base)) }
    override fun onCreate() { super.onCreate(); AppLanguages.initialize(this); AppStrings.initialize(AppLanguages.wrap(this)) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (android.os.Build.VERSION.SDK_INT >= 33) AppStrings.changed() else AppStrings.initialize(AppLanguages.wrap(this))
        graph.refreshLocalizedNotification()
    }
    val graph by lazy { LedgerGraph(this) }
}
val Context.ledgerGraph: LedgerGraph get() = (applicationContext as LedgerApplication).graph

data class PendingCandidate(val token: Long, val candidate: TransactionCandidate,
                            val saving: Boolean = false, val error: String? = null,
                            val preparation: CandidatePreparation? = null,
                            val requestId: String = java.util.UUID.randomUUID().toString())
sealed interface DataPreview {
    data class Legacy(val document: ImportDocument, val plan: ImportPlan) : DataPreview
    data class Exchange(val data: ExchangeData, val plan: ExchangePlan) : DataPreview
}

/** Process-wide state joins the Activity and accessibility overlay; never holds either View. */
class LedgerGraph(context: Context, private val store: LedgerStore = LedgerStore(context.applicationContext),
                  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storageLock = Mutex()
    private val _snapshot = MutableStateFlow(LedgerSnapshot())
    val snapshot = _snapshot.asStateFlow()
    private val _choices = MutableStateFlow<List<MerchantChoice>>(emptyList())
    val choices = _choices.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _pending = MutableStateFlow<PendingCandidate?>(null)
    val pending = _pending.asStateFlow()
    private val _enabled = MutableStateFlow(prefs.getBoolean("automatic", false))
    val enabled = _enabled.asStateFlow()
    private val _paused = MutableStateFlow(prefs.getBoolean("paused", false))
    val paused = _paused.asStateFlow()
    private val _connection = MutableStateFlow(ServiceConnection.DISCONNECTED)
    private val _runtimePermissions = MutableStateFlow(readRuntimePermissions(appContext))
    val runtimePermissions = _runtimePermissions.asStateFlow()
    private val _notificationEnabled = MutableStateFlow(prefs.getBoolean("status_notification", false))
    val notificationEnabled = _notificationEnabled.asStateFlow()
    private val _successFeedback = MutableStateFlow(prefs.getBoolean("success_feedback", true))
    val successFeedback = _successFeedback.asStateFlow()
    val automaticStatus = combine(_enabled, _paused, _connection, _runtimePermissions, ::resolveAutomaticStatus)
        .stateIn(scope, SharingStarted.Eagerly, resolveAutomaticStatus(_enabled.value, _paused.value, _connection.value, _runtimePermissions.value))
    val recognizing get() = _enabled.value && !_paused.value
    private val notifications = LedgerNotifications(appContext)
    private val _quickEntry = MutableStateFlow(0L)
    val quickEntry = _quickEntry.asStateFlow()
    fun requestQuickEntry() { _quickEntry.value++ }
    fun consumeQuickEntry(blocked: Boolean = false, reason: String = appString(R.string.ui_save_or_cancel_the_current_edit_first)) { _quickEntry.value = 0; if (blocked) _notice.value = UiNotice(reason) }
    fun refreshRuntime() { _runtimePermissions.value = readRuntimePermissions(appContext) }
    fun onAppForeground() {
        refreshRuntime()
        refreshLocalizedNotification()
    }
    fun serviceConnection(value: ServiceConnection) { if (_connection.value != value) { _connection.value = value; refreshRuntime() } }
    fun pause(value: Boolean) {
        prefs.edit().putBoolean("paused", value).apply(); _paused.value = value
        if (value) { discard(); dismissOverlay?.invoke() }
    }
    fun setNotificationEnabled(value: Boolean) { prefs.edit().putBoolean("status_notification", value).apply(); _notificationEnabled.value = value; refreshRuntime() }
    fun setSuccessFeedback(value: Boolean) { prefs.edit().putBoolean("success_feedback", value).apply(); _successFeedback.value = value }
    private val _appearance = MutableStateFlow(Appearance.read(prefs.getString("appearance", null)))
    val appearance = _appearance.asStateFlow()
    private val _categoryDisplay=MutableStateFlow(CategoryDisplay.read(prefs.getString("category_layout",null)))
    val categoryDisplay=_categoryDisplay.asStateFlow()
    fun setCategoryDisplay(value: CategoryDisplay) { prefs.edit().putString("category_layout",value.name).apply();_categoryDisplay.value=value }
    fun setAppearance(value: Appearance) {
        prefs.edit().putString("appearance", value.name).apply()
        _appearance.value = value
    }
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()
    private val _preview = MutableStateFlow<DataPreview?>(null)
    val preview = _preview.asStateFlow()
    private val _query = MutableStateFlow(LedgerQuery().inMonth(java.time.YearMonth.now()))
    private var initialScopePending = true
    val query = _query.asStateFlow()
    private val _notice = MutableStateFlow<UiNotice?>(null)
    internal val notice = _notice.asStateFlow()
    private val _selected = MutableStateFlow<LedgerRow?>(null)
    val selected = _selected.asStateFlow()
    private var selectedId: Long? = null
    private var selectionSequence = 0L
    @Volatile private var bookChoiceSequence = 0L
    private var nextToken = 0L
    private var limit = 50
    private var refreshSequence = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    var activityVisible = false
    var dismissOverlay: (() -> Unit)? = null

    init {
        refresh()
        scope.launch {
            combine(_notificationEnabled, _enabled, _paused, _connection, _runtimePermissions) { show, enabled, paused, connection, permissions ->
                notifications.update(show, resolveAutomaticStatus(enabled, paused, connection, permissions), paused, permissions.notifications)
            }.collect { }
        }
    }
    fun enable(value: Boolean) {
        prefs.edit().putBoolean("automatic", value).apply()
        _enabled.value = value
        if (value) pause(false)
        refreshRuntime()
        if (!value) { discard(); dismissOverlay?.invoke() }
    }
    fun present(candidate: TransactionCandidate, error: String? = null, preparation: CandidatePreparation? = null): Long? {
        if (!recognizing || _pending.value?.saving == true || _busy.value) return null
        val token = ++nextToken
        _pending.value = PendingCandidate(token, candidate, error = error, preparation = preparation)
        if (preparation == null) loadPreparation(token, candidate)
        return token
    }
    private fun loadPreparation(token: Long, candidate: TransactionCandidate) {
        scope.launch {
            try {
                val preparation = prepare(candidate)
                val current = _pending.value
                if (current?.token == token && !current.saving) _pending.value = current.copy(preparation = preparation, error = null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                val current = _pending.value
                if (current?.token == token && !current.saving) _pending.value = current.copy(error = appString(R.string.ui_could_not_load_entry_options))
            }
        }
    }
    fun retryPreparation(token: Long) { _pending.value?.takeIf { it.token == token && !it.saving }?.let { loadPreparation(token, it.candidate) } }
    suspend fun prepare(candidate: TransactionCandidate): CandidatePreparation = withContext(ioDispatcher) { storageLock.withLock { store.prepareCandidate(candidate) } }
    fun discard(token: Long? = _pending.value?.token) {
        if (_pending.value?.token == token && _pending.value?.saving != true) _pending.value = null
    }
    suspend fun existing(candidate: TransactionCandidate): SaveResult? = withContext(ioDispatcher) {
        storageLock.withLock { store.existing(candidate) }
    }
    fun confirm(token: Long, entry: ConfirmedEntry) {
        val current = _pending.value ?: return
        if (current.token != token || current.saving || entry.source != current.candidate || _busy.value) return
        bookChoiceSequence++
        _pending.value = current.copy(saving = true, error = null)
        _busy.value = true
        scope.launch {
            try {
                withContext(ioDispatcher) { storageLock.withLock { store.saveCandidate(entry, current.requestId) } }
                _query.value = _query.value.copy(bookId = entry.bookId, categoryId = null, accountId = null)
                if (_successFeedback.value) android.widget.Toast.makeText(appContext, appString(R.string.ui_entry_saved), android.widget.Toast.LENGTH_SHORT).show()
                if (_pending.value?.token == token) {
                    _pending.value = null
                }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) {
                if (_pending.value?.token == token) _pending.value = current.copy(error = e.message?.let(::localizedMessage) ?: appString(R.string.ui_check_the_entered_values))
            } catch (_: Exception) {
                if (_pending.value?.token == token) _pending.value = current.copy(error = appString(R.string.ui_save_could_not_be_confirmed_try_again))
            } finally { _busy.value = false }
        }
    }
    fun clearActionError() { _actionError.value = null }
    fun systemSettingsUnavailable() { _notice.value=UiNotice(appString(R.string.ui_settings_unavailable),NoticeKind.WARNING) }
    fun updatePageUnavailable() { _notice.value=UiNotice(appString(R.string.ui_update_page_unavailable),NoticeKind.WARNING) }
    fun clearNotice(expectedId: Long? = null) { if (expectedId == null || _notice.value?.id == expectedId) _notice.value = null }
    fun selectEntry(id: Long?) {
        selectedId = id
        val request = ++selectionSequence
        if (id == null) { _selected.value = null; return }
        scope.launch {
            try {
                val row = withContext(ioDispatcher) { storageLock.withLock { store.entry(id) } }
                if (request == selectionSequence) _selected.value = row
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (request == selectionSequence) _actionError.value = appString(R.string.ui_could_not_load_entries) }
        }
    }
    fun cancelPreview() { if (!_busy.value) { _preview.value = null; _actionError.value = null } }
    fun setQuery(query: LedgerQuery) {
        initialScopePending = false
        _query.value = query; limit = 50
        val selectedBook = query.bookId
        if (selectedBook != null) {
            val request = ++bookChoiceSequence
            scope.launch {
                try {
                    withContext(ioDispatcher) { storageLock.withLock { if (request == bookChoiceSequence) store.setCurrentBook(selectedBook) } }
                    refresh()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _actionError.value = appString(R.string.ui_could_not_switch_the_entry_ledger) }
            }
        }
        refresh()
    }
    suspend fun categoryRows(name: String, type: io.github.localledger.core.TransactionType, query: LedgerQuery): List<LedgerRow> = withContext(ioDispatcher) {
        storageLock.withLock {
            val result = store.snapshot(Int.MAX_VALUE, query.copy(type = type))
            result.rows.filter { CatalogLabels.category(result.catalog, it.categoryId, it.type) == name }
        }
    }
    private fun <T> mutate(block: () -> T, success: (T) -> Unit) {
        if (_busy.value) return
        _busy.value = true; _actionError.value = null; _notice.value = null
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { storageLock.withLock { block() } }
                refresh(); success(result)
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _actionError.value = e.message?.let(::localizedMessage) ?: appString(R.string.ui_invalid_input) }
            catch (_: SQLiteConstraintException) { _actionError.value = appString(R.string.ui_duplicate_name_or_data_still_in_use) }
            catch (_: Exception) { _actionError.value = appString(R.string.ui_could_not_complete_the_action_try_again) }
            finally { _busy.value = false }
        }
    }
    fun saveDraft(draft: EntryDraft, id: Long?, revision: Long, uid: String, saved: (Long) -> Unit) {
        if (_busy.value) return
        if (id == null) bookChoiceSequence++
        mutate({ if (id == null) store.createEntry(draft, uid) else { store.updateEntry(id, revision, draft); id } }, {
            if (id == null) { _query.value = _query.value.copy(bookId = draft.bookId, categoryId = null, accountId = null); refresh() }
            saved(it)
        })
    }
    fun delete(row: LedgerRow, done: () -> Unit) = mutate({ store.deleteEntry(row.id, row.revision) }, { done() })
    fun saveBook(name: String, id: String?, done: () -> Unit, defaultBook: Boolean? = null) = mutate({ store.saveBook(name, id, defaultBook) }, { done() })
    fun setRememberedBook(book: String) {
        if (!_busy.value) setQuery(_query.value.copy(bookId = book))
    }
    fun setDefaultBook(book: String?) = mutate({ store.setDefaultBook(book) }, { })
    fun forgetChoice(choice: MerchantChoice) = mutate({ store.forgetMerchant(choice) }, { })
    fun saveCategory(type: TransactionType, name: String, id: String?, done: () -> Unit) = mutate({ store.saveCategory(type, name, id) }, { done() })
    fun saveCategoryStyle(type: TransactionType, name: String, id: String?, icon: String, hidden: Boolean, done: () -> Unit) = mutate({ store.saveCategory(type, name, id, icon, hidden, requireNew = id == null) }, { done() })
    fun hideCategories(type: TransactionType, ids: List<String>, hidden: Boolean, done: () -> Unit) = mutate({ store.setCategoriesHidden(type, ids, hidden) }, { done() })
    fun restoreCategories(type: TransactionType, done: () -> Unit) = mutate({ store.restoreCategoryDefaults(type) }, { _notice.value = UiNotice(appString(R.string.ui_restored_built_in_categories, it),NoticeKind.SUCCESS); done() })
    fun restoreAccount(id: String, done: () -> Unit) = mutate({ store.restoreAccount(id) }, { done() })
    fun hideCategory(id: String, hidden: Boolean) = mutate({ store.setCategoryHidden(id, hidden) }, { })
    fun orderCategories(type: TransactionType, ids: List<String>, done: () -> Unit) = mutate({ store.reorderCategories(type, ids) }, { done() })
    fun arrangeCategories(type: TransactionType, enabled: List<String>, hidden: List<String>, done: () -> Unit) = mutate({ store.arrangeCategories(type, enabled, hidden) }, { done() })
    fun saveAccount(name: String, balance: java.math.BigInteger?, id: String?, done: () -> Unit) = mutate({ store.saveAccount(name, balance, id) }, { done() })
    suspend fun previewBookMerge(source: String, target: String): BookMergePlan = withContext(ioDispatcher) {
        storageLock.withLock { store.previewBookMerge(source, target) }
    }
    fun mergeBooks(plan: BookMergePlan, done: () -> Unit) = mutate({ store.mergeBooks(plan) }, { target ->
        if (_query.value.bookId == plan.sourceId) _query.value = _query.value.copy(bookId = target, categoryId = null)
        refresh(); done()
    })
    fun deleteCatalog(kind: String, id: String, done: () -> Unit) = mutate({ store.deleteCatalog(kind, id) }, { done() })
    fun inspectFile(uri: Uri) = mutate({
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer); if (read < 0) break
                require(output.size() + read <= 20 * 1024 * 1024) { appString(R.string.ui_file_exceeds_20_mb) }; output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException(appString(R.string.ui_could_not_read_the_file))
        if (LedgerExchange.isExchange(bytes)) {
            val data = LedgerExchange.decode(bytes); DataPreview.Exchange(data, store.previewExchange(data))
        } else {
            val document = LegacyWorkbook.parse(bytes); DataPreview.Legacy(document, store.previewImport(document))
        }
    }, { _preview.value = it })
    fun commitPreview(affectBalances: Boolean, keepConflicts: Boolean) {
        val preview = _preview.value ?: return
        mutate({ when (preview) {
            is DataPreview.Legacy -> store.commitImport(preview.document, affectBalances, keepConflicts)
            is DataPreview.Exchange -> store.commitExchange(preview.data)
        } }, {
            _preview.value = null; _notice.value = UiNotice(appString(R.string.ui_imported_skipped, it.added, it.skipped),NoticeKind.SUCCESS)
        })
    }
    fun exportTo(uri: Uri) = mutate({
        val bytes = store.exportExchange()
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalArgumentException(appString(R.string.ui_could_not_write_the_file))
    }, { _notice.value = UiNotice(appString(R.string.ui_export_complete),NoticeKind.SUCCESS) })
    fun loadMore() { limit = (limit.toLong() + 50).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); refresh() }
    fun refresh() {
        val request = ++refreshSequence
        refreshJob?.cancel()
        val query = _query.value
        val selected = selectedId
        refreshJob = scope.launch {
            _loading.value = true
            try {
                val result = withContext(ioDispatcher) { storageLock.withLock {
                    store.initializeAccountCatalog()
                    var snapshot = store.snapshot(limit, query)
                    if (initialScopePending || query.bookId != null && snapshot.catalog.books.none { it.id == query.bookId }) {
                        val book = SelectionPolicy.resolve(snapshot.catalog, snapshot.selection, TransactionType.EXPENSE).bookId
                        snapshot = store.snapshot(limit, query.copy(bookId = book, categoryId = null, accountId = null))
                    }
                    Triple(snapshot, selected?.let(store::entry), store.merchantChoices())
                } }
                if (request == refreshSequence) {
                    _snapshot.value = result.first; _error.value = null
                    _query.value = result.first.query; initialScopePending = false
                    _choices.value = result.third
                    if (selectedId == selected) _selected.value = result.second
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (request == refreshSequence) _error.value = appString(R.string.ui_could_not_load_entries) }
            finally { if (request == refreshSequence) _loading.value = false }
        }
    }
    internal fun refreshLocalizedNotification() {
        notifications.update(_notificationEnabled.value,
            resolveAutomaticStatus(_enabled.value, _paused.value, _connection.value, _runtimePermissions.value),
            _paused.value, _runtimePermissions.value.notifications)
    }
    internal fun close() { notifications.cancel(); scope.cancel(); store.close() }
}
