// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.prettierjs

import com.intellij.ide.actionsOnSave.*
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackageField
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import com.intellij.util.text.SemVer
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.nio.file.FileSystems
import java.util.regex.PatternSyntaxException
import javax.swing.JCheckBox
import javax.swing.text.JTextComponent

private const val CONFIGURABLE_ID = "settings.javascript.prettier"

class PrettierConfigurable(private val project: Project) : BoundSearchableConfigurable(
  PrettierBundle.message("configurable.PrettierConfigurable.display.name"), "reference.settings.prettier", CONFIGURABLE_ID) {

  private lateinit var packageField: NodePackageField
  private lateinit var runForFilesField: JBTextField
  private lateinit var runOnSaveCheckBox: JCheckBox

  override fun createPanel(): DialogPanel {
    val prettierConfiguration = PrettierConfiguration.getInstance(project)

    return panel {
      packageField = NodePackageField(project, PrettierUtil.PACKAGE_NAME) { NodeJsInterpreterManager.getInstance(project).interpreter }

      row(PrettierBundle.message("prettier.package.label")) {
        cell(packageField)
          .horizontalAlign(HorizontalAlign.FILL)
          .bind({ it.selectedRef }, { nodePackageField, nodePackageRef -> nodePackageField.selectedRef = nodePackageRef },
                MutableProperty({ prettierConfiguration.nodePackageRef }, { prettierConfiguration.withLinterPackage(it) })
          )
      }

      row(PrettierBundle.message("run.for.files.label")) {
        runForFilesField = textField()
          .comment(PrettierBundle.message("files.pattern.comment"))
          .horizontalAlign(HorizontalAlign.FILL)
          .bind({ textField -> textField.text.trim() },
                JTextComponent::setText,
                MutableProperty({ prettierConfiguration.filesPattern }, { prettierConfiguration.filesPattern = it }))
          .validationOnInput {
            try {
              FileSystems.getDefault().getPathMatcher("glob:" + it.text)
              null
            }
            catch (e: PatternSyntaxException) {
              @NlsSafe val firstLine = e.localizedMessage?.lines()?.firstOrNull()
              ValidationInfo(firstLine ?: PrettierBundle.message("invalid.pattern"), it)
            }
          }
          .component
      }

      row("") {
        checkBox(PrettierBundle.message("on.code.reformat.label"))
          .bindSelected(
            { prettierConfiguration.isRunOnReformat },
            { prettierConfiguration.isRunOnReformat = it })

        val shortcut = ActionManager.getInstance().getKeyboardShortcut(IdeActions.ACTION_EDITOR_REFORMAT)
        shortcut?.let { comment(KeymapUtil.getShortcutText(it)) }
      }

      row("") {
        runOnSaveCheckBox = checkBox(PrettierBundle.message("on.save.label"))
          .bindSelected(
            { prettierConfiguration.isRunOnSave },
            { prettierConfiguration.isRunOnSave = it })
          .component

        val link = ActionsOnSaveConfigurable.createGoToActionsOnSavePageLink()
        cell(link)
      }
    }
  }


  class PrettierOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext):
      List<ActionOnSaveInfo> = listOf(PrettierOnSaveActionInfo(context))

    override fun getSearchableOptions(): Collection<String> {
      return listOf(PrettierBundle.message("run.on.save.checkbox.on.actions.on.save.page"))
    }
  }


  private class PrettierOnSaveActionInfo(actionOnSaveContext: ActionOnSaveContext)
    : ActionOnSaveBackedByOwnConfigurable<PrettierConfigurable>(actionOnSaveContext, CONFIGURABLE_ID, PrettierConfigurable::class.java) {

    override fun getActionOnSaveName() = PrettierBundle.message("run.on.save.checkbox.on.actions.on.save.page")

    override fun getCommentAccordingToStoredState() =
      PrettierConfiguration.getInstance(project).let { getComment(it.`package`.getVersion(project), it.filesPattern) }

    override fun getCommentAccordingToUiState(configurable: PrettierConfigurable) =
      getComment(configurable.packageField.selectedRef.constantPackage?.getVersion(project),
                 configurable.runForFilesField.text.trim())

    private fun getComment(prettierVersion: @Nullable SemVer?, filesPattern: @NotNull String): ActionOnSaveComment? {
      if (prettierVersion == null) {
        val message = PrettierBundle.message("run.on.save.prettier.package.not.specified.warning")
        // no need to show warning if Prettier is not enabled in this project
        return if (isActionOnSaveEnabled) ActionOnSaveComment.warning(message) else ActionOnSaveComment.info(message)
      }

      return ActionOnSaveComment.info(PrettierBundle.message("run.on.save.prettier.version.and.files.pattern",
                                                             shorten(prettierVersion.rawVersion, 15),
                                                             shorten(filesPattern, 40)))
    }

    override fun isActionOnSaveEnabledAccordingToStoredState() = PrettierConfiguration.getInstance(project).isRunOnSave

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: PrettierConfigurable) = configurable.runOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: PrettierConfigurable, enabled: Boolean) {
      configurable.runOnSaveCheckBox.isSelected = enabled
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(CONFIGURABLE_ID))

    private fun shorten(s: String, max: Int) = StringUtil.shortenTextWithEllipsis(s, max, 0, true)
  }
}
