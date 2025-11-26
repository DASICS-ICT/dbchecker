package DBChecker

import chisel3._
import chisel3.util._
import axi._
trait DBCheckerConst {
  val RegNum    = 8
  val dbte_num  = 4096

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
  val reserved     = UInt(( 8 + log2Up(dbte_num)).W)
  val v            = Bool()
  val w            = Bool()
  val r            = Bool()
  val dev_id       = UInt(5.W)
  val bnd_hi       = UInt(48.W)
  val bnd_lo       = UInt(48.W)
}

// ---------------------------------------------------
// |cnt3(15)|cnt2(15)|cnt1(15)|cnt0(15)|latest err(4)|
// ---------------------------------------------------
// 0: bnd out-of-bound error
// 1: bnd type mismatch error
// 2: mtdt invalid error
// 4: nothing error

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
