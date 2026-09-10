package DBChecker

import chisel3._
import chisel3.util._
import axi._
trait DBCheckerConst {
  val RegNum    = 23
  val dbte_num  = 4096
  val dbte_line_entries = 4
  val dbte_set_num = dbte_num / dbte_line_entries

  // reg index (actual addr is 4 byte aligned, r/w lo-hi)
  // checker enable register  (0x0, RW) : checker enable / disable register
  val chk_en          = 0x0 // 0x00

  // checker command register (0x1, RW) : used to free metadata / clr error cnt register
  val chk_cmd         = 0x1 // 0x04

  // dbte table memory base lo addr (0x2, RW): full dbte table place in memory low 32 bits
  val chk_dbte_mb_lo  = 0x2 // 0x08

  // dbte table memory base hi addr (0x3, RW): full dbte table place in memory high 32 bits
  val chk_dbte_mb_hi  = 0x3 // 0x0C
  
  // checker error addr lo register (0x4, RO) : latest error access addr low 32 bits
  val chk_err_addr_lo = 0x4 // 0x10

  // checker error addr hi register (0x5, RO) : latest error access addr high 32 bits
  val chk_err_addr_hi = 0x5 // 0x14

  // checker error info register (0x6, RO) : latest error info
  val chk_err_info    = 0x6 // 0x18

  // checker error counter register (0x7, RO)
  val chk_err_cnt     = 0x7 // 0x1C

  // performance counter registers (RO)
  val chk_perf_hit     = 0x8 // 0x20
  val chk_perf_miss    = 0x9 // 0x24
  val chk_perf_penalty = 0xA // 0x28

  // refill mode (RW while checker disabled): bit 0, 0=16B, 1=64B
  val chk_refill_cfg = 0xB // 0x2C

  // 0x30/0x34 remain reserved for the older auto-release experiment.
  val chk_refill_hist_1    = 0x10 // 0x40: refills serving 1 request
  val chk_refill_hist_2    = 0x11 // 0x44: refills serving 2 requests
  val chk_refill_hist_3    = 0x12 // 0x48: refills serving 3 requests
  val chk_refill_hist_4p   = 0x13 // 0x4C: refills serving at least 4 requests
  val chk_diff_line_wait   = 0x14 // 0x50
  val chk_rob_full         = 0x15 // 0x54
  val chk_refill_bytes     = 0x16 // 0x58


  def cmd_op_free    = 0.U(1.W)
  def cmd_op_clr_err = 1.U(1.W)

  def err_bnd_farea = 0.U(2.W)
  def err_bnd_ftype = 1.U(2.W)
  def err_mtdt_finv = 2.U(2.W)
  def err_wrong_dev = 3.U(2.W)
}

class DBCheckerEnCtl extends Bundle with DBCheckerConst{
  val en_dev_bm = UInt(32.W) // enable device ID bitmap
}
class DBCheckerMtdt extends Bundle with DBCheckerConst {
  val index_offset = UInt((16 - log2Up(dbte_num)).W)
  val reserved     = UInt(( 7 + log2Up(dbte_num)).W)
  val no_cache     = Bool()
  val v            = Bool()
  val w            = Bool()
  val r            = Bool()
  val dev_id       = UInt(5.W)
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

class DBCheckerCommand extends Bundle with DBCheckerConst{
  val v      = Bool()
  val op     = UInt(1.W)
  val imm    = UInt(30.W)

// used for free cmd
  def get_index_lo: UInt = {
    this.imm(15 - log2Up(dbte_num),0)
  }
  def get_index_hi: UInt = {
    this.imm(15, 16 - log2Up(dbte_num))
  }
  def get_index: UInt = {
    this.imm(15, 0)
  }
}

class DBCheckerPtr extends Bundle with DBCheckerConst {
  val dbte_index   = UInt(16.W)
  val access_addr = UInt(48.W)
  def get_index_hi: UInt = {
    this.dbte_index(15, 16 - log2Up(dbte_num))
  }
  def get_index: UInt = {
    this.dbte_index
  }
  def get_cache_addr: UInt = {
    this.dbte_index(log2Up(dbte_num) - 1, 0)
  }
  def get_set: UInt = {
    this.dbte_index(log2Up(dbte_num) - 1, log2Up(dbte_line_entries))
  }
  def get_tag: UInt = {
    this.dbte_index(15, log2Up(dbte_num))
  }
  def get_sector: UInt = {
    this.dbte_index(log2Up(dbte_line_entries) - 1, 0)
  }
  def get_line: UInt = {
    this.dbte_index(15, log2Up(dbte_line_entries))
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

class DBCheckerDBTEReq extends Bundle with DBCheckerConst {
  val index     = UInt(16.W)
}

class DBCheckerDBTERsp extends Bundle with DBCheckerConst {
  val line_index = UInt((16 - log2Up(dbte_line_entries)).W)
  val dbte = Vec(dbte_line_entries, UInt(128.W))
}

class DBCheckerInvalidate extends Bundle with DBCheckerConst {
  val valid = Bool()
  val clear_all = Bool()
  val index = UInt(16.W)
}

class DBCheckerCacheMeta extends Bundle with DBCheckerConst {
  val tag = UInt((16 - log2Up(dbte_num)).W)
  val valid = UInt(dbte_line_entries.W)
}

class DBCheckerDataWriteForward extends Bundle with DBCheckerConst {
  val enable = Bool()
  val address = UInt(log2Up(dbte_num).W)
  val data = UInt(128.W)
}

class DBCheckerMetaWriteForward extends Bundle with DBCheckerConst {
  val enable = Bool()
  val address = UInt(log2Up(dbte_set_num).W)
  val data = new DBCheckerCacheMeta
}
object DBCheckerFetchState extends ChiselEnum {
  val RREQ, RRSP = Value
}
object DBCheckerRefillState extends ChiselEnum {
  val AR, R, WB, RSP = Value
}

class DBCheckerPerfEvent extends Bundle {
  val hit     = Bool()
  val miss    = Bool()
  val penalty = Bool()
  val refill_waiters = UInt(4.W)
  val different_line_wait = Bool()
  val rob_full = Bool()
  val refill_bytes = UInt(7.W)
}
