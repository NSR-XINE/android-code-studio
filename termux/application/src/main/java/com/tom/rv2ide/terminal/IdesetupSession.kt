/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.tom.rv2ide.app.configuration.CpuArch
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.managers.ToolsManager
import java.io.File
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] implementation that is used to run the `idesetup` script during automatic
 * installation.
 *
 * @author Akash Yadav
 */
class IdesetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(IdesetupSession::class.java)

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): IdesetupSession? {
      return session?.let { IdesetupSession(it, script) }
    }

    @JvmStatic
    fun createScript(context: Context): File? {
      // Create temp file with proper executable name
      val tempDir = File(context.filesDir, "temp")
      if (!tempDir.exists()) {
        tempDir.mkdirs()
      }
      val script = File(tempDir, "idesetup")

      // write script contents
      if (!writeIdesetupScript(context, script)) {
        return null
      }

      // make it readable and executable
      FileUtils.setFilePermissions("idesetupScript", script.absolutePath, "rwx")

      return script
    }

    private fun writeIdesetupScript(context: Context, script: File): Boolean {
      return try {
        val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
        val folderName =
            when (cpuArch) {
              com.tom.rv2ide.app.configuration.CpuArch.AARCH64 -> "arm64"
              com.tom.rv2ide.app.configuration.CpuArch.ARM -> "arm"
              com.tom.rv2ide.app.configuration.CpuArch.X86_64 -> "x86_64"
              com.tom.rv2ide.app.configuration.CpuArch.X86 -> "x86"
            }
        
        // Write the original binary to script_real
        val scriptReal = File(script.absolutePath + "_real")
        context.assets.open(ToolsManager.getCommonAsset("${folderName}/idesetup")).use { inputStream ->
          FileOutputStream(scriptReal).use { outputStream -> inputStream.copyTo(outputStream) }
        }
        FileUtils.setFilePermissions("idesetupRealScript", scriptReal.absolutePath, "rwx")

        // Write the wrapper script to script
        val wrapperScriptContent = """
            #!/usr/bin/env bash
            # Run the real idesetup first
            "${script.absolutePath}_real" "${'$'}@"
            RET=${'$'}?

            # If real idesetup failed, we must fail
            if [ ${'$'}RET -ne 0 ]; then
              exit ${'$'}RET
            fi

            # Try installing Antigravity CLI
            (
              ARCH=${'$'}(uname -m)
              if [ "${'$'}ARCH" = "aarch64" ]; then
                echo "[..] Auto-installing Antigravity CLI..."
                export PATH="${'$'}{PREFIX}/bin:/system/bin:${'$'}PATH"
                export LD_LIBRARY_PATH="${'$'}{PREFIX}/lib"
                export DEBIAN_FRONTEND=noninteractive
                
                echo "[..] Updating packages and installing glibc & ca-certificates..."
                apt-get update -qy && \
                apt-get install -yqy glibc-repo && \
                apt-get update -qy && \
                apt-get install -yqy glibc ca-certificates curl tar || {
                  echo "[WRN] Failed to install dependencies (glibc, ca-certificates, curl, tar)"
                  exit 0
                }
                
                echo "[..] Fetching Antigravity CLI installer..."
                curl -fsSL https://raw.githubusercontent.com/wallentx/antigravity-cli-termux/dev/install.sh -o "${'$'}{PREFIX}/tmp/agy_install.sh" || {
                  echo "[WRN] Failed to download Antigravity CLI installer"
                  exit 0
                }
                
                # Strip the exec auto-launch from the installer
                sed -i 's|exec "${'$'}INSTALL_BIN_DIR/agy"|# exec "${'$'}INSTALL_BIN_DIR/agy"|g' "${'$'}{PREFIX}/tmp/agy_install.sh"
                
                echo "[..] Running Antigravity CLI installer..."
                bash "${'$'}{PREFIX}/tmp/agy_install.sh" || {
                  echo "[WRN] Antigravity CLI installer failed"
                  exit 0
                }
                
                echo "[OK] Antigravity CLI installed successfully!"
              fi
            ) || echo "[WRN] Antigravity CLI installation failed but continuing setup..."

            exit 0
        """.trimIndent()

        FileOutputStream(script).use { outputStream ->
          outputStream.write(wrapperScriptContent.toByteArray())
        }
        true
      } catch (e: Exception) {
        log.error("Failed to write idesetup script: {}", e.message, e)
        false
      }
    }
  }

  private constructor(
      src: TermuxSession,
      script: File,
  ) : this(
      src.terminalSession,
      src.executionCommand,
      src.termuxSessionClient,
      src.isSetStdoutOnExit,
      script,
  )

  override fun finish() {
    super.finish()
    // Delete the temporary script file once the session is finished
    var error = FileUtils.deleteFile("idesetupScript", script.absolutePath, true)
    if (error != null) {
      log.error(error.errorLogString)
    }
    val scriptReal = File(script.absolutePath + "_real")
    if (scriptReal.exists()) {
      error = FileUtils.deleteFile("idesetupRealScript", scriptReal.absolutePath, true)
      if (error != null) {
        log.error(error.errorLogString)
      }
    }
  }
}
