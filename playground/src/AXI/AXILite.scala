package axi

import chisel3._
import chisel3.util._

class AxiLiteAddr(val addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  // val prot = UInt(3.W) // optional, but included by vivado
}

object AxiLiteAddr {
  def apply(addrWidth: Int) = new AxiLiteAddr(addrWidth)
}

class AxiLiteWriteData(val dataWidth: Int) extends Bundle {
  require(dataWidth == 32 || dataWidth == 64, "AxiLite `dataWidth` must be 32 or 64")
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
}

object AxiLiteWriteData {
  def apply(dataWidth: Int) = new AxiLiteWriteData(dataWidth)
}

class AxiLiteReadData(val dataWidth: Int) extends Bundle {
  require(dataWidth == 32 || dataWidth == 64, "AxiLite `dataWidth` must be 32 or 64")
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
}

object AxiLiteReadData {
  def apply(dataWidth: Int) = new AxiLiteReadData(dataWidth)
}

class AxiLiteWriteResp extends Bundle {
  val resp = UInt(2.W) // OKAY, EXOKAY, SLVERR, DECERR
}

object AxiLiteWriteResp {
  def apply() = new AxiLiteWriteResp()
}

class AxiLiteSlave(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val ar = Flipped(Decoupled(AxiLiteAddr(addrWidth)))
  val r = Decoupled(AxiLiteReadData(dataWidth))

  val aw = Flipped(Decoupled(AxiLiteAddr(addrWidth)))
  val w = Flipped(Decoupled(AxiLiteWriteData(dataWidth)))
  val b = Decoupled(AxiLiteWriteResp())
}

object AxiLiteSlave {
  def apply(addrWidth: Int, dataWidth: Int) =
    new AxiLiteSlave(addrWidth = addrWidth, dataWidth = dataWidth)
}

class AxiLiteMaster(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val ar = Decoupled(AxiLiteAddr(addrWidth))
  val r = Flipped(Decoupled(AxiLiteReadData(dataWidth)))

  val aw = Decoupled(AxiLiteAddr(addrWidth))
  val w = Decoupled(AxiLiteWriteData(dataWidth))
  val b = Flipped(AxiLiteWriteResp())
}

object AxiLiteMaster {
  def apply(addrWidth: Int, dataWidth: Int) =
    new AxiLiteMaster(addrWidth = addrWidth, dataWidth = dataWidth)
}