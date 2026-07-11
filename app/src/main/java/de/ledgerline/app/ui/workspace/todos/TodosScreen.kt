package de.ledgerline.app.ui.workspace.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.TagChips
import de.ledgerline.app.ui.workspace.common.TrashBar
import de.ledgerline.app.ui.workspace.common.formatDue
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.TextInputDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val activeList by vm.activeList.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val linkChooser by vm.linkChooserEnabled.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    // Navigation-ish local state: which todo is open (detail), and the editor target.
    var openId by remember { mutableStateOf<String?>(null) }
    var editorFor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleteForeverTarget by remember { mutableStateOf<String?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // --- Editor (create / edit) takes over the whole screen ---
    editorFor?.let { target ->
        val existing = target.id?.let { vm.todoById(it) }
        TodoEditor(
            title = stringResource(if (target.id == null) R.string.todo_new else R.string.todo_edit),
            initial = existing ?: TodoItem(listId = activeList),
            lists = lists,
            onSave = { title, listId, priority, due, description, url, tags ->
                if (target.id == null) vm.addTodo(title, listId, priority, due, description, url, tags)
                else vm.editTodo(target.id, title, listId, priority, due, description, url, tags)
                editorFor = null
            },
            onCreateList = { vm.addList(it) },
            onBack = { editorFor = null },
            modifier = modifier,
        )
        return
    }

    // --- Detail takes over the whole screen ---
    val current = openId
    if (current != null) {
        val todo = vm.todoById(current)
        if (todo != null) {
            TodoDetailScreen(
                todo = todo,
                listName = lists.firstOrNull { it.id == todo.listId }?.name,
                linkChooser = linkChooser,
                onEdit = { editorFor = EditorTarget(todo.id) },
                onDelete = { vm.trashTodo(todo.id); openId = null },
                onBack = { openId = null },
                modifier = modifier,
            )
            return
        } else {
            openId = null
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!ui.loading && !ui.error && !showTrash) {
                FloatingActionButton(onClick = { editorFor = EditorTarget(null) }) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.todo_new))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> LoadingBox()
                ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() })
                else -> Column(Modifier.fillMaxSize()) {
                    // Fixed header above the list — the toolbar/empty-state can't be
                    // LazyColumn items (they'd be measured with infinite height).
                    if (showTrash) {
                        TrashBar(
                            onBack = { vm.setTrash(false) },
                            onEmptyTrash = { confirmEmptyTrash = true },
                            emptyEnabled = ui.items.isNotEmpty(),
                        )
                    } else {
                        TodosToolbar(
                            lists = lists,
                            activeList = activeList,
                            trashCount = trashCount,
                            onSelectList = { vm.setActiveList(it) },
                            onAddList = { vm.addList(it) },
                            onRenameList = { id, name -> vm.renameList(id, name) },
                            onDeleteList = { vm.deleteList(it) },
                            onOpenTrash = { vm.setTrash(true) },
                        )
                    }
                    val emptyText = if (showTrash) R.string.trash_empty_state else R.string.ws_empty_todos
                    PullToRefreshBox(ui.loading, { vm.refresh() }, Modifier.weight(1f)) {
                        if (ui.items.isEmpty()) {
                            RefreshableMessage(stringResource(emptyText))
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(ui.items, key = { it.id }) { todo ->
                                    if (showTrash) {
                                        TrashTodoRow(
                                            todo = todo,
                                            onRestore = { vm.restore(todo.id) },
                                            onDeleteForever = { deleteForeverTarget = todo.id },
                                        )
                                    } else {
                                        TodoRow(
                                            todo = todo,
                                            onToggleDone = { vm.toggleDone(todo.id) },
                                            onToggleMarked = { vm.toggleMarked(todo.id) },
                                            onOpen = { openId = todo.id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteForeverTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.delete_forever_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = { vm.deleteForever(id); deleteForeverTarget = null },
            onDismiss = { deleteForeverTarget = null },
        )
    }

    if (confirmEmptyTrash) {
        ConfirmDialog(
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.trash_empty),
            onConfirm = { vm.emptyTrash(); confirmEmptyTrash = false },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

/** Which todo the editor is editing; a null [id] means "create new". */
private data class EditorTarget(val id: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodosToolbar(
    lists: List<de.ledgerline.app.domain.model.TodoList>,
    activeList: String?,
    trashCount: Int,
    onSelectList: (String?) -> Unit,
    onAddList: (String) -> Unit,
    onRenameList: (String, String) -> Unit,
    onDeleteList: (String) -> Unit,
    onOpenTrash: () -> Unit,
) {
    var filterExpanded by remember { mutableStateOf(false) }
    var manageExpanded by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val activeName = lists.firstOrNull { it.id == activeList }?.name
        ?: stringResource(R.string.todo_all_lists)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // List filter
        Box {
            androidx.compose.material3.TextButton(onClick = { filterExpanded = true }) {
                Icon(Icons.Outlined.FilterList, null, Modifier.padding(end = 4.dp).size(18.dp))
                Text(activeName)
            }
            DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todo_all_lists)) },
                    onClick = { onSelectList(null); filterExpanded = false },
                )
                lists.forEach { l ->
                    DropdownMenuItem(text = { Text(l.name) }, onClick = { onSelectList(l.id); filterExpanded = false })
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        Box {
            IconButton(onClick = { manageExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.todo_manage_lists))
            }
            DropdownMenu(expanded = manageExpanded, onDismissRequest = { manageExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todo_new_list)) },
                    onClick = { manageExpanded = false; showNewList = true },
                )
                if (trashCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_open, trashCount)) },
                        onClick = { manageExpanded = false; onOpenTrash() },
                    )
                }
                lists.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.list_rename) + ": " + l.name) },
                        onClick = { manageExpanded = false; renameTarget = l.id to l.name },
                    )
                }
                lists.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.list_delete) + ": " + l.name) },
                        onClick = { manageExpanded = false; deleteTarget = l.id },
                    )
                }
            }
        }
    }

    if (showNewList) {
        TextInputDialog(
            title = stringResource(R.string.todo_new_list),
            label = stringResource(R.string.todo_list),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            onConfirm = { onAddList(it); showNewList = false },
            onDismiss = { showNewList = false },
        )
    }
    renameTarget?.let { (id, name) ->
        TextInputDialog(
            title = stringResource(R.string.list_rename),
            label = stringResource(R.string.todo_list),
            confirmLabel = stringResource(R.string.action_save),
            initial = name,
            onConfirm = { onRenameList(id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.list_delete),
            confirmLabel = stringResource(R.string.list_delete),
            onConfirm = { onDeleteList(id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun TodoRow(
    todo: TodoItem,
    onToggleDone: () -> Unit,
    onToggleMarked: () -> Unit,
    onOpen: () -> Unit,
) {
    val dueText = formatDue(todo.due)
    val showPriority = todo.priority.isNotBlank() && todo.priority != "normal"
    ListItem(
        headlineContent = {
            Text(
                todo.title,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            if (todo.description.isNotBlank() || dueText.isNotBlank() || showPriority || todo.tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (todo.description.isNotBlank()) {
                        Text(
                            todo.description,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dueText.isNotBlank() || showPriority) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (dueText.isNotBlank()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Event,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        dueText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (showPriority) {
                                Text(
                                    priorityLabel(todo.priority),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.tertiaryContainer,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    TagChips(todo.tags)
                }
            }
        },
        leadingContent = { Checkbox(checked = todo.done, onCheckedChange = { onToggleDone() }) },
        trailingContent = {
            IconButton(onClick = onToggleMarked) {
                if (todo.marked) {
                    Icon(Icons.Outlined.Star, stringResource(R.string.todo_edit), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.StarBorder, stringResource(R.string.todo_edit))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashTodoRow(
    todo: TodoItem,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(todo.title) },
        supportingContent = if (todo.tags.isNotEmpty()) {
            { TagChips(todo.tags) }
        } else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete_forever))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
