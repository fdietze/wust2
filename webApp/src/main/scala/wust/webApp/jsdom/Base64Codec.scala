package wust.webApp.jsdom

import java.nio.ByteBuffer

import org.scalajs.dom.window.{atob, btoa}

import scala.scalajs.js

object Base64Codec {
  import js.Dynamic.{global => g}

  def encode(buffer: ByteBuffer): String = {
    val n = buffer.limit()
    val s = new StringBuilder(n)
    for (_ <- 0 until n) {
      val c = buffer.get
      s ++= g.String.fromCharCode(c & 0xFF).asInstanceOf[String]
    }

    btoa(s.result)
  }

  def decode(data: String): ByteBuffer = {
    // remove urlsafety first:
    val base64Data = data.replace("_", "/").replace("-", "+")

    val byteString = atob(base64Data)
    val buffer = ByteBuffer.allocateDirect(byteString.size)
    byteString.foreach(c => buffer.put(c.toByte))
    buffer.flip()
    buffer
  }
}
