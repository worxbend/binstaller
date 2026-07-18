package binstaller.cli

import binstaller.core.SudoCredentialError
import binstaller.core.SudoCredentialProvider
import binstaller.core.SudoCredentialRequest
import binstaller.core.SudoPassword

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private[cli] final class TerminalSudoCredentialProvider(err: PrintWriter)
    extends SudoCredentialProvider:

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] = Option(System.console()) match
    case None          => requestFromDevTty(request.operation)
    case Some(console) =>
      err.println(s"sudo password required for ${request.operation}")
      err.flush()
      TerminalSudoCredentialProvider.passwordFromChars(
        Option(console.readPassword("sudo password: "))
      )

  private def requestFromDevTty(operation: String): Either[SudoCredentialError, SudoPassword] =
    val tty = Path.of("/dev/tty")
    if !Files.isReadable(tty) || !Files.isWritable(tty) then
      Left(SudoCredentialError.Unavailable(
        "sudo credentials required, but no interactive terminal is available"
      ))
    else
      try
        val input = FileInputStream(tty.toFile)
        try
          val output = FileOutputStream(tty.toFile)
          try readPasswordFromDevTty(operation, input, output)
          finally output.close()
        finally input.close()
      catch
        case _: Exception => Left(SudoCredentialError.Unavailable(
            "sudo credentials required, but terminal password input is unavailable"
          ))

  private def readPasswordFromDevTty(
      operation: String,
      input: FileInputStream,
      output: FileOutputStream
  ): Either[SudoCredentialError, SudoPassword] =
    if !setDevTtyEcho(enabled = false) then
      Left(SudoCredentialError.Unavailable(
        "sudo credentials required, but terminal password input is unavailable"
      ))
    else
      val restoreHook = Thread(
        () => { val _ = setDevTtyEcho(enabled = true) },
        "restore-tty-echo"
      )
      try
        // Register the hook inside the try so a shutdown-in-progress IllegalStateException cannot
        // skip the finally that re-enables echo and leaves the terminal wedged.
        try Runtime.getRuntime.addShutdownHook(restoreHook)
        catch case _: IllegalStateException => ()
        output.write(
          s"sudo password required for $operation\nsudo password: ".getBytes(StandardCharsets.UTF_8)
        )
        output.flush()
        // Caller-owned fixed buffer, zeroed unconditionally, so no password bytes linger on the
        // heap (unlike a growable stream whose reallocated backing arrays are never cleared).
        val buffer = Array.ofDim[Byte](TerminalSudoCredentialProvider.maxPasswordBytes)
        try
          readPasswordBytes(input, buffer) match
            case Left(error) => Left(error)
            case Right(length) =>
              output.write('\n')
              output.flush()
              passwordFromBytes(buffer, length)
        finally java.util.Arrays.fill(buffer, 0.toByte)
      finally
        val _ = setDevTtyEcho(enabled = true)
        try
          val _ = Runtime.getRuntime.removeShutdownHook(restoreHook)
        catch case _: IllegalStateException => ()

  private def readPasswordBytes(
      input: FileInputStream,
      buffer: Array[Byte]
  ): Either[SudoCredentialError, Int] =
    var length   = 0
    var done     = false
    var overflow = false
    while !done do
      input.read() match
        case -1          => done = true
        case '\n' | '\r' => done = true
        case value if value >= 0 =>
          if length >= buffer.length then
            overflow = true
            done = true
          else
            buffer(length) = value.toByte
            length += 1
        case _ => done = true
    if overflow then
      Left(SudoCredentialError.Unavailable(
        "sudo credentials required, but the entered password exceeds the supported length"
      ))
    else Right(length)

  private def passwordFromBytes(
      bytes: Array[Byte],
      length: Int
  ): Either[SudoCredentialError, SudoPassword] =
    // REPLACE actions so malformed UTF-8 cannot throw across this boundary and leak via a stack trace.
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    val charBuffer = decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
    val chars      = Array.ofDim[Char](charBuffer.remaining())
    val _          = charBuffer.get(chars)
    if charBuffer.hasArray then java.util.Arrays.fill(charBuffer.array(), ' ')
    TerminalSudoCredentialProvider.passwordFromChars(Some(chars))

  private def setDevTtyEcho(enabled: Boolean): Boolean =
    val mode = if enabled then "echo" else "-echo"
    try
      val process = ProcessBuilder("sh", "-c", s"stty $mode < /dev/tty")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      process.waitFor() == 0
    catch
      case _: Exception => false

private[cli] object TerminalSudoCredentialProvider:

  /** Hard cap on interactive password length; bounds the caller-owned read buffer. */
  private[cli] val maxPasswordBytes: Int = 4096

  def passwordFromChars(charsOption: Option[Array[Char]])
      : Either[SudoCredentialError, SudoPassword] = charsOption match
    case Some(chars) =>
      try
        if chars.isEmpty then Left(SudoCredentialError.Canceled)
        else Right(SudoPassword.fromChars(chars))
      finally java.util.Arrays.fill(chars, '\u0000')
    case None => Left(SudoCredentialError.Canceled)
