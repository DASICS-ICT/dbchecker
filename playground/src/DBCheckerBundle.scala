package DBChecker

import chisel3._
import chisel3.util._
import axi._
trait DBCheckerConst {
  val debug  = true
  val RegNum = 16 // total register num

  val magic_num = 0xcb // 11101011
  val dbte_num  = 4096

  // reg index (actual addr is 8 byte aligned, r/w lo-hi)
  // checker enable register  (0x0, RW) : checker enable / disable register; reserved
  val chk_en       = 0x0 // 0x00

  // checker command register (0x1, R, W only when not valid) : used to alloc / free metadata
  val chk_cmd      = 0x1 // 0x08

  // checker metadata low 64 bits (0x2, RW), for mtdt low 64 bits
  val mtdt_lo = 0x2 // 0x10

  // checker metadata high 64 bits (0x3, RW), for mtdt high 64 bits
  val mtdt_hi = 0x3 // 0x18

  // checker result register (0x4, RO) : when alloc, read this register to get encrypted pointer
  val chk_res      = 0x4 // 0x20

  // checker key lowbit register (0x5, WO, if debug RW)
  val chk_keyl     = 0x5 // 0x28

  // checker key highbit register (0x6, WO, if debug RW)
  val chk_keyh     = 0x6 // 0x30

  // checker error counter register (0x7, RO)
  val chk_err_cnt  = 0x7 // 0x38

  // checker error info register (0x8, RO)
  val chk_err_info = 0x8 // 0x40

  // checker error metadata register (0x9, RO)
  val chk_err_mtdt = 0x9 // 0x48

  def cmd_op_free    = 0.U(2.W)
  def cmd_op_alloc   = 1.U(2.W)
  def cmd_op_clr_err = 2.U(2.W)
  def cmd_op_switch  = 3.U(2.W)

  def cmd_status_inv   = 0.U(2.W)
  def cmd_status_req   = 1.U(2.W)
  def cmd_status_ok    = 2.U(2.W)
  def cmd_status_error = 3.U(2.W)

  def err_bnd_farea = 0.U(2.W)
  def err_bnd_ftype = 1.U(2.W)
  def err_mtdt_finv = 2.U(2.W)
}

class DBCheckerEnCtl extends Bundle with DBCheckerConst{
  val reserved = UInt(58.W)
  val err_rpt = Bool() // WO, when stall, write 1 to send error to bus
  val err_byp  = Bool()  // WO, when stall, write 1 to bypass
  val stall_mode = Bool() // 0: non-stall when error, 1: stall when error
  val intr_clr  = Bool() // WO, clear intr
  val intr_en  = Bool() // enable intr sending
  val func_en   = Bool() // 0: disable, 1: enable
}
class DBCheckerMtdt extends Bundle with DBCheckerConst {
  val w        = UInt(1.W)
  val r        = UInt(1.W)
  val off_len  = UInt(5.W)
  val id       = UInt(25.W)
  val up_bnd   = UInt(48.W)
  val lo_bnd   = UInt(48.W)

  def get_modifier: UInt = {
    val totalBits = this.asUInt
    val hi64 = totalBits(127, 64)
    val lo64 = totalBits(63, 0)
    hi64 ^ lo64
  }
}

// ---------------------------------------------------
// |cnt3(15)|cnt2(15)|cnt1(15)|cnt0(15)|latest err(4)|
// ---------------------------------------------------
// 0: bnd out-of-bound error
// 1: bnd type mismatch error
// 2: mtdt invalid error
// 4: nothing error

class DBCheckerErrCnt extends Bundle {
  val cnt        = Vec(4, UInt(15.W))
  val err_latest = UInt(4.W)
}
class DBCheckerErrReq extends Bundle {
  val typ  = UInt(2.W) // 01: bnd, 10: mtdt
  val info = UInt(64.W)
  val mtdt = UInt(128.W)
}

// ---------------------------------------------------------------------------------------------------------------------
// |status(2)|operation(0:free 1:alloc 2:clr_err 3:switch)|0...0|E(metadata)_index(if free) / metadata[51:0] (if alloc)|
// ---------------------------------------------------------------------------------------------------------------------

class DBCheckerCommand extends Bundle {
  val status = UInt(2.W)
  val op     = UInt(2.W)
  val pad    = UInt(8.W)
  val imm    = UInt(52.W)
}

// ------------------------------------
// |E(metadata)[63:48]|access_addr(48)|
// ------------------------------------

class DBCheckerPtr extends Bundle with DBCheckerConst {
  val e_mtdt_hi   = UInt(16.W)
  val access_addr = UInt(48.W)
  def get_index: UInt = {
    this.e_mtdt_hi(15, 16 - log2Up(dbte_num))
  }
}

// FSM

object DBTEAllocState extends ChiselEnum {
  val WaitEncryptReq, WaitEncryptResp = Value
}

object DBCheckerState extends ChiselEnum {
  val ReadDBTE, WaitCheckreq, WaitCheckresp, Return = Value
}

// Pipeline passed structure

class DBCheckerPipeIn     extends Bundle with DBCheckerConst {
  val axi_a      = new AxiAddr(64)
  val axi_a_type = Bool()
  val dbte_v_bm  = Vec(dbte_num, Bool())
  val en         = Bool()
}
class DBCheckerPipeMedium extends Bundle with DBCheckerConst {
  val axi_a      = new AxiAddr(64)
  val axi_a_type = Bool()
  val dbte       = UInt(128.W)
  val bypass     = Bool() // bypass checker
  val err_v      = Bool()
  val err_req    = new DBCheckerErrReq
}
