package axi

import chisel3._
import chisel3.util._

class AxiAddr(val addrWidth: Int, val dataWidth: Int, val idWidth: Int, val userWidth: Int)
    extends Bundle {
  // required fields
  val addr = UInt(addrWidth.W)
  val id = UInt(idWidth.W)
  val size = UInt(3.W) // beatBytes = 2^size
  val len = UInt(8.W) // beatsPerBurst - 1. Max 255 for INCR, 15 otherwise
  val burst = UInt(2.W) // burst type: 0=fixed, 1=incr, 2=wrap

  // optional fields
  val cache = UInt(4.W)
  val lock = Bool()
  val prot = UInt(3.W)
  val qos = UInt(4.W)
  val region = UInt(4.W)
  val user = UInt(userWidth.W)

  def initDefault() = {
    id := 0.U
    size := log2Ceil(dataWidth / 8).U
    len := 1.U
    burst := 1.U
    cache := 0.U
    lock := false.B
    prot := 0.U
    qos := 0.U
    region := 0.U
    user := 0.U
  }
}

object AxiAddr {
  def apply(addrWidth: Int, dataWidth: Int, idWidth: Int = 0, userWidth: Int = 0) = {
    new AxiAddr(addrWidth = addrWidth,
                dataWidth = dataWidth,
                idWidth = idWidth,
                userWidth = userWidth)
  }
}

abstract class AxiData(val dataWidth: Int, val userWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val last = Bool()
  val user = UInt(userWidth.W) // optional

  def initDefault() = {
    user := 0.U
  }

  // used for "downcasting" Axi(Read|Write)Data
  def getId: Option[UInt] = None
  def getStrb: Option[UInt] = None
}

class AxiReadData(dataWidth: Int, val idWidth: Int, userWidth: Int)
    extends AxiData(dataWidth = dataWidth, userWidth = userWidth) {
  val id = UInt(idWidth.W)
  val resp = UInt(2.W)

  override def initDefault() = {
    id := 0.U
  }

  override def getId = Some(id)
}

object AxiReadData {
  def apply(dataWidth: Int, idWidth: Int = 0, userWidth: Int = 0) = {
    new AxiReadData(dataWidth = dataWidth, idWidth = idWidth, userWidth = userWidth)
  }
}

class AxiWriteData(dataWidth: Int, userWidth: Int)
    extends AxiData(dataWidth = dataWidth, userWidth = userWidth) {
  val strb = UInt((dataWidth / 8).W)

  override def initDefault() = {
    strb := (Math.pow(2, dataWidth).toInt - 1).U
  }

  override def getStrb = Some(strb)
}

object AxiWriteData {
  def apply(dataWidth: Int, userWidth: Int = 0) = {
    new AxiWriteData(dataWidth = dataWidth, userWidth = userWidth)
  }
}

class AxiWriteResp(val idWidth: Int, val userWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val resp = UInt(2.W)
  val user = UInt(userWidth.W) // optional

  def initDefault() = {
    id := 0.U
    user := 0.U
  }
}

object AxiWriteResp {
  def apply(idWidth: Int = 0, userWidth: Int = 0) = {
    new AxiWriteResp(idWidth = idWidth, userWidth = userWidth)
  }
}

trait AxiReadAddr;
trait AxiWriteAddr;

class AxiSlave(val addrWidth: Int,
               val dataWidth: Int,
               val idWidth: Int = 0,
               val arUserWidth: Int = 0,
               val rUserWidth: Int = 0,
               val awUserWidth: Int = 0,
               val wUserWidth: Int = 0,
               val bUserWidth: Int = 0)
    extends Bundle {
  val ar = Flipped(
    Decoupled(
      new AxiAddr(addrWidth = addrWidth,
                  idWidth = idWidth,
                  dataWidth = dataWidth,
                  userWidth = arUserWidth) with AxiReadAddr))
  val r = Decoupled(
    new AxiReadData(dataWidth = dataWidth, idWidth = idWidth, userWidth = rUserWidth))

  val aw = Flipped(
    Decoupled(
      new AxiAddr(addrWidth = addrWidth,
                  idWidth = idWidth,
                  dataWidth = dataWidth,
                  userWidth = awUserWidth) with AxiWriteAddr))
  val w = Flipped(
    Decoupled(new AxiWriteData(dataWidth = dataWidth, userWidth = wUserWidth)))
  val b = Decoupled(AxiWriteResp(idWidth = idWidth, userWidth = bUserWidth))

  def initDefault() = {
    r.bits.initDefault()
    b.bits.initDefault()
  }
}

object AxiSlave {
  def apply(addrWidth: Int,
            dataWidth: Int,
            idWidth: Int = 0,
            arUserWidth: Int = 0,
            rUserWidth: Int = 0,
            awUserWidth: Int = 0,
            wUserWidth: Int = 0,
            bUserWidth: Int = 0) = {
    new AxiSlave(
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      idWidth = idWidth,
      arUserWidth = arUserWidth,
      rUserWidth = rUserWidth,
      awUserWidth = awUserWidth,
      wUserWidth = wUserWidth,
      bUserWidth = bUserWidth
    )
  }
}

class AxiMaster(val addrWidth: Int,
                val dataWidth: Int,
                val idWidth: Int = 0,
                val arUserWidth: Int = 0,
                val rUserWidth: Int = 0,
                val awUserWidth: Int = 0,
                val wUserWidth: Int = 0,
                val bUserWidth: Int = 0)
    extends Bundle {
  val ar = Decoupled(
    new AxiAddr(addrWidth = addrWidth,
                dataWidth = dataWidth,
                idWidth = idWidth,
                userWidth = arUserWidth) with AxiReadAddr)
  val r = Flipped(
    Decoupled(AxiReadData(dataWidth = dataWidth, idWidth = idWidth, userWidth = rUserWidth)))

  val aw = Decoupled(
    new AxiAddr(addrWidth = addrWidth,
                dataWidth = dataWidth,
                idWidth = idWidth,
                userWidth = awUserWidth) with AxiWriteAddr)
  val w = Decoupled(AxiWriteData(dataWidth = dataWidth, userWidth = wUserWidth))
  val b = Flipped(Decoupled(AxiWriteResp(idWidth = idWidth, userWidth = bUserWidth)))

  def initDefault() = {
    ar.bits.initDefault()
    aw.bits.initDefault()
    w.bits.initDefault()
  }

  def setupRead(srcAddr: UInt, numBeats: UInt) = {
    ar.bits.addr := srcAddr
    ar.bits.len := numBeats
    ar.valid := true.B
    r.ready := true.B
    when(ar.ready) { ar.valid := false.B }
    when(r.bits.last) { r.ready := false.B }
  }

  def setupRead(srcAddr: UInt, numBits: Width): Unit = {
    setupRead(srcAddr, (numBits.get / dataWidth min 1).U)
  }
}

object AxiMaster {
  def apply(addrWidth: Int,
            dataWidth: Int,
            idWidth: Int = 0,
            arUserWidth: Int = 0,
            rUserWidth: Int = 0,
            awUserWidth: Int = 0,
            wUserWidth: Int = 0,
            bUserWidth: Int = 0) = {
    new AxiMaster(
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      idWidth = idWidth,
      arUserWidth = arUserWidth,
      rUserWidth = rUserWidth,
      awUserWidth = awUserWidth,
      wUserWidth = wUserWidth,
      bUserWidth = bUserWidth
    )
  }
}