
package axi

import chisel3._
import chisel3.util._

/** Axi stream signals.
  *
  * @param dataWidth bus width in bits
  * @param userWidth user data width in bits. Recommended to be a multiple of `dataWidth`.
  * @param destWidth dest signal width in bits. Recommended to be no more than 4.
  * @param idWidth id signal width in bits. Recommended to be no more than 8.
  *
  * Note: optional fields are not Option[Data] for compatibility with Vivado which makes
  *       them have width=1. They are optimized out during synthesis. Use `initDefault` to
  *       squelch warnings generated by Chisel.
  */
class AxiStream(val dataWidth: Int, val userWidth: Int, val destWidth: Int, val idWidth: Int)
    extends Bundle {
  val dataBytes = dataWidth / 8
  val data = UInt(dataWidth.W)
  val strb = UInt(dataBytes.W)
  val keep = UInt(dataBytes.W)
  val last = Bool()
  val user = UInt(userWidth.W)
  val dest = UInt(destWidth.W)
  val id = UInt(idWidth.W)

  def initDefault() = {
    val allStrb = (Math.pow(2, dataBytes).toInt - 1).U
    keep := allStrb
    strb := allStrb
    user := 0.U
    dest := 0.U
    id := 0.U
  }
}

object AxiStream {
  def apply(dataWidth: Int, userWidth: Int = 0, destWidth: Int = 0, idWidth: Int = 0) = {
    new AxiStream(dataWidth = dataWidth,
                  userWidth = userWidth,
                  destWidth = destWidth,
                  idWidth = idWidth)
  }
}

object AxiStreamMaster {
  def apply(dataWidth: Int, userWidth: Int = 0, destWidth: Int = 0, idWidth: Int = 0) = {
    Decoupled(
      AxiStream(dataWidth = dataWidth,
                userWidth = userWidth,
                destWidth = destWidth,
                idWidth = idWidth))
  }
}

object AxiStreamSlave {
  def apply(dataWidth: Int, userWidth: Int = 0, destWidth: Int = 0, idWidth: Int = 0) = {
    Flipped(
      Decoupled(
        AxiStream(dataWidth = dataWidth,
                  userWidth = userWidth,
                  destWidth = destWidth,
                  idWidth = idWidth)))
  }
}
