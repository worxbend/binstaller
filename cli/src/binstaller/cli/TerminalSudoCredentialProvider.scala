package binstaller.cli

import binstaller.core.SudoCredentialError
import binstaller.core.SudoCredentialProvider
import binstaller.core.SudoCredentialRequest
import binstaller.core.SudoPassword

import java.io.ByteArrayOutputStream
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
      passwordFromChars(Option(console.readPassword("sudo password: ")))

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
      try
        output.write(
          s"sudo password required for $operation\nsudo password: ".getBytes(StandardCharsets.UTF_8)
        )
        output.flush()
        val bytes = readPasswordBytes(input)
        output.write('\n')
        output.flush()
        passwordFromBytes(bytes)
      finally
        val _ = setDevTtyEcho(enabled = true)

  private def readPasswordBytes(input: FileInputStream): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    var done  = false
    while !done do
      input.read() match
        case -1                  => done = true
        case '\n' | '\r'         => done = true
        case value if value >= 0 => bytes.write(value)
        case _                   => done = true
    bytes.toByteArray

  private def passwordFromBytes(bytes: Array[Byte]): Either[SudoCredentialError, SudoPassword] =
    try passwordFromChars(Some(String(bytes, StandardCharsets.UTF_8).toCharArray))
    finally java.util.Arrays.fill(bytes, 0.toByte)

  private def passwordFromChars(charsOption: Option[Array[Char]])
      : Either[SudoCredentialError, SudoPassword] = charsOption match
    case Some(chars) =>
      try
        val password = String(chars)
        if password.isEmpty then Left(SudoCredentialError.Canceled)
        else Right(SudoPassword.fromString(password))
      finally java.util.Arrays.fill(chars, '\u0000')
    case None => Left(SudoCredentialError.Canceled)

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
