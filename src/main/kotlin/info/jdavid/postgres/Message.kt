package info.jdavid.postgres

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Date

sealed class Message {

  interface Authentication

  internal interface FromServer
  internal interface FromClient {
    fun writeTo(buffer: ByteBuffer)
  }

  class StartupMessage(private val username: String, private val database: String): FromClient, Message() {
    override fun writeTo(buffer: ByteBuffer) {
      val start = buffer.position()
      buffer.putInt(0)
      buffer.putInt(196608)
      buffer.put("user".toByteArray())
      buffer.put(0)
      buffer.put(username.toByteArray())
      buffer.put(0)
      buffer.put("database".toByteArray())
      buffer.put(0)
      buffer.put(database.toByteArray())
      buffer.put(0)
      buffer.put(0)
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class AuthenticationOk: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationOk"
  }
  class AuthenticationKerberosV5: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationKerberosV5"
  }
  class AuthenticationCleartextPassword: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationCleartextPassword"
  }
  class AuthenticationMD5Password(val salt: ByteArray): FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationMd5Password(salt=${hex(salt)})"
  }
  class AuthenticationSCMCredential: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationSCMCredential"
  }
  class AuthenticationGSS: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationGSS"
  }
  class AuthenticationGSSContinue(val auth: ByteArray): FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationGSSContinue"
  }
  class AuthenticationSSPI: FromServer, Authentication, Message() {
    override fun toString() = "AuthenticationSSPI"
  }

  class ParameterStatus(val key: String, val value: String): FromServer, Message() {
    override fun toString() = "ParameterStatus(key: ${key}, value: ${value})"
  }

  class BackendKeyData(private val processId: Int, private val secretKey: Int): FromServer, Message() {
    override fun toString() = "BackendKeyData(processId: ${processId}, secretKey: ${secretKey})"
    operator fun component1(): Int = processId
    operator fun component2(): Int = secretKey
  }

  class ReadyForQuery(val status: Status): FromServer, Message() {
    override fun toString() = "ReadyForQuery(status: ${status})"
    enum class Status {
      IDLE, IN_TRANSACTION, IN_FAILED_TRANSACTION;
      companion object {
        internal fun from(b: Byte): Status {
          return when (b) {
            'I'.toByte() -> IDLE
            'T'.toByte() -> IN_TRANSACTION
            'E'.toByte() -> IN_FAILED_TRANSACTION
            else -> throw IllegalArgumentException()
          }
        }
      }
    }
  }

  class ParseComplete: FromServer, Message() {
    override fun toString() = "ParseComplete()"
  }

  class BindComplete: FromServer, Message() {
    override fun toString() = "BindComplete()"
  }

  class NoData: FromServer, Message() {
    override fun toString() = "NoData()"
  }

  class RowDescription(internal val fields: List<Pair<String, String>>): FromServer, Message() {
    override fun toString() = "RowDescription()"
  }

  class PortalSuspended: FromServer, Message() {
    override fun toString() = "PortalSuspended()"
  }

  class CloseComplete: FromServer, Message() {
    override fun toString() = "CloseComplete()"
  }

  class CommandComplete(internal val tag: String): FromServer, Message() {
    override fun toString() = "CommandComplete(${tag})"
  }

  class DataRow(internal val values: List<String?>): FromServer, Message() {
    override fun toString() = "DataRow(${values.joinToString { ", " }})"
  }

  class NoticeResponse(private val message: String): FromServer, Message() {
    override fun toString() = "NoticeResponse(){\n${message}}"
  }

  class ErrorResponse(private val message: String): FromServer, Message() {
    override fun toString() = "ErrorResponse(){\n${message}}"
  }

  //--------------------------------------------------------------------------------------------------

  class PasswordMessage(val username: String, val password: String,
                        val authMessage: Authentication): FromClient, Message() {
    override fun toString() = "PasswordMessage(username: ${username})"
    override fun writeTo(buffer: ByteBuffer) {
      when (authMessage) {
        is AuthenticationCleartextPassword -> {
          buffer.put('p'.toByte())
          val start = buffer.position()
          buffer.putInt(0)
          buffer.put(password.toByteArray())
          buffer.put(0)
          buffer.putInt(start, buffer.position() - start)
        }
        is AuthenticationMD5Password -> {
          val md5 = md5(username, password, authMessage.salt)
          buffer.put('p'.toByte())
          val start = buffer.position()
          buffer.putInt(0)
          buffer.put(md5)
          buffer.put(0)
          buffer.putInt(start, buffer.position() - start)
        }
      }
    }
  }

  class Parse(private val preparedStatementName: ByteArray?,
              private val query: String): FromClient, Message() {
    override fun toString() = "Parse(${preparedStatementName?.let { String(it) } ?: "unamed"}): ${query}"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('P'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      preparedStatementName?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.put(query.toByteArray(Charsets.US_ASCII))
      buffer.put(0)
      buffer.putShort(0) // no type specified
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class Bind(private val preparedStatementName: ByteArray?,
             private val portalName: ByteArray?,
             private val parameters: Iterable<Any?>): FromClient, Message() {
    override fun toString(): String {
      val params = parameters.map {
        return when (it) {
          is Boolean -> "${it}".toUpperCase()
          is Float -> "${it}F"
          is Long -> "${it}L"
          is Date -> "\"${it}\""
          is String -> "\"${it}\""
          else -> "${it}"
        }
      }
      val name1 = preparedStatementName?.let { String(it) } ?: "unamed"
      val name2 = portalName?.let { String(it) } ?: "unamed"
      return "Bind(${name1},${name2}): ${params.joinToString(", ")}"
    }
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('B'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      portalName?.apply { buffer.put(this) }
      buffer.put(0)
      preparedStatementName?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.putShort(0) // no input format code specified
      val position = buffer.position()
      buffer.putShort(0)
      var i = 0.toShort()
      for (p in parameters) {
        ++i
        if (p == null) buffer.putInt(-1) else {
          val bytes = TextFormat.format(p).toByteArray()
          buffer.putInt(bytes.size)
          buffer.put(bytes)
        }
      }
      buffer.putShort(position, i)
      buffer.putShort(0) // no output format code specified
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class Describe(private val portalName: ByteArray?): FromClient, Message() {
    override fun toString() = "Describe(${portalName?.let { String(it) } ?: "unamed"})"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('D'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      buffer.put('P'.toByte())
      portalName?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class Execute(private val portalName: ByteArray?, private val maxRows: Int): FromClient, Message() {
    override fun toString() = "Execute(${portalName?.let { String(it) } ?: "unamed"})"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('E'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      portalName?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.putInt(maxRows)
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class ClosePreparedStatement(private val name: ByteArray?): FromClient, Message() {
    override fun toString() = "ClosePreparedStatement(${name?.let { String(it) } ?: "unamed"})"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('C'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      buffer.put('S'.toByte())
      name?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class ClosePortal(private val name: ByteArray?): FromClient, Message() {
    override fun toString() = "ClosePortal(${name?.let { String(it) } ?: "unamed"})"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('C'.toByte())
      val start = buffer.position()
      buffer.putInt(0)
      buffer.put('P'.toByte())
      name?.apply { buffer.put(this) }
      buffer.put(0)
      buffer.putInt(start, buffer.position() - start)
    }
  }

  class Flush: FromClient, Message() {
    override fun toString() = "Flush"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('H'.toByte())
      buffer.putInt(4)
    }
  }

  class Sync: FromClient, Message() {
    override fun toString() = "Sync"
    override fun writeTo(buffer: ByteBuffer) {
      buffer.put('S'.toByte())
      buffer.putInt(4)
    }
  }

  //--------------------------------------------------------------------------------------------------

  companion object {
    @Suppress("UsePropertyAccessSyntax")
    internal fun fromBytes(buffer: ByteBuffer): Message.FromServer {
      val first = buffer.get()
      when (first) {
        'R'.toByte() -> { // authentication
          val length = buffer.getInt()
          val flag = buffer.getInt()
          when (flag) {
            0 -> {
              assert(length == 8)
              return AuthenticationOk()
            }
            2 -> {
              assert(length == 8)
              return AuthenticationKerberosV5()
            }
            3 -> {
              assert(length == 8)
              return AuthenticationCleartextPassword()
            }
            5 -> {
              assert(length == 12)
              val salt = ByteArray(4)
              buffer.get(salt)
              return AuthenticationMD5Password(salt)
            }
            6 -> {
              assert(length == 8)
              return AuthenticationSCMCredential()
            }
            7 -> {
              assert(length == 8)
              return AuthenticationGSS()
            }
            8 -> {
              assert(length == 8)
              return AuthenticationSSPI()
            }
            9 -> {
              val size = length - 8
              val auth = ByteArray(size)
              buffer.get(auth)
              return AuthenticationGSSContinue(auth)
            }
            else -> throw IllegalArgumentException()
          }
        }
        'S'.toByte() -> {
          val length = buffer.getInt()
          val data = ByteArray(length - 4)
          buffer.get(data)
          val i = data.indexOf(0)
          val key = String(data, 0, i)
          val value = String(data, i + 1, data.size - i - 2)
          return ParameterStatus(key, value)
        }
        'K'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 12)
          val processId = buffer.getInt()
          val secretKey = buffer.getInt()
          return BackendKeyData(processId, secretKey)
        }
        'Z'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 5)
          val status = ReadyForQuery.Status.from(buffer.get())
          return ReadyForQuery(status)
        }
        '1'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 4)
          return ParseComplete()
        }
        '2'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 4)
          return BindComplete()
        }
        '3'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 4)
          return CloseComplete()
        }
        'n'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 4)
          return NoData()
        }
        's'.toByte() -> {
          val length = buffer.getInt()
          assert(length == 4)
          return PortalSuspended()
        }
        'T'.toByte() -> {
          val length = buffer.getInt()
          assert(length >= 6)
          val n = buffer.getShort()
          val fields = (1..n).map {
            val sb = StringBuilder()
            while (true) {
              val b = buffer.get()
              if (b == 0.toByte()) break
              sb.appendCodePoint(b.toInt())
            }
            val table = buffer.getInt()
            val index = buffer.getShort()
            val oid = buffer.getInt()
            val len = buffer.getShort()
            val modifier = buffer.getInt()
            val format = buffer.getShort()
            assert(format == 0.toShort())
            sb.toString() to "${oid}:${len}"
          }
          return RowDescription(fields)
        }
        'C'.toByte() -> {
          val length = buffer.getInt()
          val data = ByteArray(length - 5)
          buffer.get(data)
          val tag = String(data, Charsets.US_ASCII)
          val end = buffer.get()
          assert(end == 0.toByte())
          return CommandComplete(tag)
        }
        'D'.toByte() -> {
          val length = buffer.getInt()
          val n = buffer.getShort()
          val data = ByteArray(length - 6 - n * 4)
          val list = ArrayList<String?>(n.toInt())
          for (i in 1..n) {
            val len = buffer.getInt()
            if (len == -1) {
              list.add(null)
            }
            else {
              buffer.get(data, 0, len)
              list.add(String(data, 0, len))
            }
          }
          return DataRow(list)
        }
        'N'.toByte(), 'E'.toByte() -> {
          val length = buffer.getInt()
          val data = ByteArray(length - 4)
          buffer.get(data)
          val message = StringBuilder()
          var sb = StringBuilder()
          for (b in data) {
            if (sb.isEmpty()) {
              when (b) {
                'S'.toByte() -> sb.append("SEVERITY: ")
                'C'.toByte() -> sb.append("SQLSTATE ERROR CODE: ")
                'M'.toByte() -> sb.append("MESSAGE: ")
                'D'.toByte() -> sb.append("DETAIL: ")
                'P'.toByte() -> sb.append("POSITION: ")
                'p'.toByte() -> sb.append("INTERNAL POSITION: ")
                'q'.toByte() -> sb.append("INTERNAL QUERY: ")
                'W'.toByte() -> sb.append("WHERE: ")
                's'.toByte() -> sb.append("SCHEMA NAME: ")
                't'.toByte() -> sb.append("TABLE NAME: ")
                'c'.toByte() -> sb.append("COLUMN NAME: ")
                'd'.toByte() -> sb.append("DATA TYPE NAME: ")
                'n'.toByte() -> sb.append("CONSTRAINT NAME: ")
                'F'.toByte() -> sb.append("FILE: ")
                'L'.toByte() -> sb.append("LINE: ")
                'R'.toByte() -> sb.append("ROUTINE: ")
              }
            }
            else {
              if (b == 0.toByte()) {
                message.append(sb)
                message.append('\n')
                sb = StringBuilder()
              }
              else {
                sb.appendCodePoint(b.toInt())
              }
            }
          }
          return message.toString().let {
            if (first == 'E'.toByte()) ErrorResponse(it) else NoticeResponse(it)
          }
        }
        else -> throw IllegalArgumentException("${first.toChar()}")
      }
    }

    private fun md5(username: String, password: String, salt: ByteArray): ByteArray {
      val md5 = MessageDigest.getInstance("MD5")
      md5.update(password.toByteArray())
      md5.update(username.toByteArray())
      val hash = hex(md5.digest()).toByteArray()
      md5.update(hash)
      md5.update(salt)
      return "md5${hex(md5.digest())}".toByteArray()
    }

    internal fun hex(bytes: ByteArray): String {
      val chars = CharArray(bytes.size * 2)
      var i = 0
      for (b in bytes) {
        chars[i++] = Character.forDigit(b.toInt().shr(4).and(0xf), 16)
        chars[i++] = Character.forDigit(b.toInt().and(0xf), 16)
      }
      return String(chars)
    }

  }

}
