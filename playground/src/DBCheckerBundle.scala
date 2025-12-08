package DBChecker

import chisel3._
import chisel3.util._
import axi._
trait DBCheckerConst {
  val RegNum    = 6
  val dbte_num  = 4096

  // reg index (actual addr is 4 byte aligned, r/w lo-hi)
  // checker enable register  (0x0, RW) : checker enable / disable register
  val chk_en          = 0x0 // 0x00

  // checker error addr lo register (0x5, RO) : latest error access addr low 32 bits
  val chk_err_addr_lo = 0x1 // 0x04

  // checker error addr hi register (0x6, RO) : latest error access addr high 32 bits
  val chk_err_addr_hi = 0x2 // 0x08

  // checker error info register (0x7, RO) : latest error info
  val chk_err_info    = 0x3 // 0xC

  // checker error counter register (0x8, RO)
  val chk_err_cnt     = 0x4 // 0x10

  // clr err 
  val chk_clr_err    = 0x5 // 0x14

  // sram base address
  val SramBaseAddr   = 0x20

  def err_bnd_farea = 0.U(2.W)
  def err_bnd_ftype = 1.U(2.W)
  def err_mtdt_finv = 2.U(2.W)
}

class DBCheckerEnCtl extends Bundle with DBCheckerConst{
  val rsvd = UInt(31.W)
  val func_en = Bool()
}
class DBCheckerMtdt extends Bundle with DBCheckerConst {
  val rsvf         = UInt(17.W)
  val v            = Bool()
  val w            = Bool()
  val r            = Bool()
  val index        = UInt(12.W)
  val bnd_hi       = UInt(48.W)
  val bnd_lo       = UInt(48.W)
}

// ---------------------------------------------------
// |cnt3(7)|cnt2(7)|cnt1(7)|cnt0(7)|latest err(4)|
// ---------------------------------------------------
// 0: bnd out-of-bound error
// 1: bnd type mismatch error
// 2: mtdt invalid error
// 3: wrong dev_id error

class DBCheckerErrCnt extends Bundle {
  val cnt        = Vec(4, UInt(7.W))
  val err_latest = UInt(4.W)
}

class DBCheckerErrInfo extends Bundle {
  val err_info = UInt(16.W) 
  val err_mtdt_index = UInt(16.W)
}
class DBCheckerErrReq extends Bundle {
  val typ  = UInt(2.W)
  val info = UInt(32.W)
  val addr = UInt(64.W)
}

class DBCheckerPtr extends Bundle with DBCheckerConst {
  val dbte_index  = UInt(16.W)
  val access_addr = UInt(48.W)

  def get_index: UInt = {
    this.dbte_index(log2Up(dbte_num) - 1, 0)
  }
}

// Pipeline passed structure
class DBCheckerPipeMedium extends Bundle with DBCheckerConst {
  val axi_a      = new AxiAddr(64, idWidth = 5)
  val axi_a_type = Bool()
  val dbte       = UInt(128.W)
  val bypass     = Bool() // bypass checker
  val err_v      = Bool()
  val err_req    = new DBCheckerErrReq
}

object DBCheckerFetchState extends ChiselEnum {
  val RREQ, RRSP = Value
}

object CtrlState extends ChiselEnum {
  val Idle, readReg, writeReg, writeResp, readSram, writeSramRMW = Value
}