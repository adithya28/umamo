package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.SelectionTarget
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.MenuItem

/** The command Select Hierarchy dispatches, so the palette and a future binding share the edit. */
private const val SELECT_HIERARCHY_COMMAND = "outliner.selectHierarchy"

/**
 * Builds the context-menu entries for a real outliner row, by entity kind: parts and drawables get a
 * visibility toggle (deformers have none); every kind gets selectability, rename, and delete.  A part's
 * "Delete" ungroups (keeps the contents, splicing them into its parent) and a second "Delete Hierarchy"
 * cascades (removes the whole subtree); a drawable or deformer has the single delete.  Selectability
 * gates only viewport picking, so every entry stays enabled on an unselectable row.
 *
 * A plain function over the resolved labels: the entries are new objects on every build either way, so
 * nothing here needs composition, and commonTest pins the entries directly.
 *
 * @param SelectionTarget target The row's entity.
 * @param OutlinerLabels labels The outliner's localized chrome.
 * @param CommandRegistry commands The registry Select Hierarchy dispatches through, with the row as its argument.
 * @param Function onToggleVisibility Flips the entity's visibility.
 * @param Function onToggleSelectable Flips the entity's selectability.
 * @param Function onStartRename Opens the inline rename.
 * @param Function onRequestDelete Requests a delete (true = cascade / the sole delete, false = ungroup).
 * @return List The menu entries.
 */
internal fun outlinerRowMenuItems(
	target: SelectionTarget,
	labels: OutlinerLabels,
	commands: CommandRegistry,
	onToggleVisibility: () -> Unit,
	onToggleSelectable: () -> Unit,
	onStartRename: () -> Unit,
	onRequestDelete: (cascade: Boolean) -> Unit,
): List<MenuItem> =
	buildList {
		add(MenuItem.Action(labels.selectHierarchy, onSelect = { commands.invoke(SELECT_HIERARCHY_COMMAND, target) }))
		add(MenuItem.Separator)

		if (target is SelectionTarget.Part || target is SelectionTarget.Drawable) {
			add(MenuItem.Action(labels.visibility, onSelect = onToggleVisibility))
		}

		add(MenuItem.Action(labels.selectable, onSelect = onToggleSelectable))
		add(MenuItem.Action(labels.rename, onSelect = onStartRename))
		add(MenuItem.Separator)
		// "Delete" removes just this item (a part keeps its contents, which rise to the parent); "Delete
		// Hierarchy" removes a part's whole subtree.  A drawable / deformer has no hierarchy, so just "Delete".
		add(MenuItem.Action(labels.delete, onSelect = { onRequestDelete(false) }))

		if (target is SelectionTarget.Part) {
			add(MenuItem.Action(labels.deleteHierarchy, onSelect = { onRequestDelete(true) }))
		}
	}