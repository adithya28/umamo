package org.umamo.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.TopLevelMenu
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Help menu carries Open Log Folder and Check for Updates exactly where the host registered them: a row whose
 * command was never registered would do nothing when chosen, which on a platform without a file manager to hand the
 * folder to, or without a network request to make, is every time.
 */
@OptIn(ExperimentalTestApi::class)
class HelpMenuTest {
	/**
	 * The command ids of the Help menu's rows, in order, read by choosing each one.
	 *
	 * @param Boolean canOpenLogFolder   Whether the host registered Open Log Folder.
	 * @param Boolean canCheckForUpdates Whether the host registered Check for Updates.
	 * @return List The rows' command ids.
	 */
	private fun helpMenuCommandIds(
		canOpenLogFolder: Boolean,
		canCheckForUpdates: Boolean,
	): List<String> {
		val dispatched = ArrayList<String>()
		var menu: TopLevelMenu? = null
		runComposeUiTest {
			setContent {
				menu = helpMenu(Keymap(emptyMap()), { commandId, _ -> dispatched += commandId }, canOpenLogFolder, canCheckForUpdates)
			}
			waitForIdle()
		}

		for (item in checkNotNull(menu).items) {
			if (item is MenuItem.Action) {
				item.onSelect()
			}
		}

		return dispatched
	}

	@Test
	fun theRowsAreThereWhenTheHostSupportsThem() {
		assertEquals(
			listOf(
				"help.sourceCode",
				"help.webSite",
				"help.documentation",
				"help.quickSetup",
				"help.checkForUpdates",
				"help.openLogFolder",
				"help.credits",
				"help.about",
			),
			helpMenuCommandIds(canOpenLogFolder = true, canCheckForUpdates = true),
		)
	}

	@Test
	fun theRowsAreLeftOutWhereTheHostCannot() {
		assertEquals(
			listOf("help.sourceCode", "help.webSite", "help.documentation", "help.quickSetup", "help.credits", "help.about"),
			helpMenuCommandIds(canOpenLogFolder = false, canCheckForUpdates = false),
		)
	}
}