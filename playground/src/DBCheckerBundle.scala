package DBChecker

import chisel3._
import chisel3.util._
import axi._
trait DBCheckerConst {
  val RegNum    = 32

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


  def cmd_op_free    = 0.U(1.W)
  def cmd_op_clr_err = 1.U(1.W)

  def err_bnd_farea = 0.U(2.W)
  def err_bnd_ftype = 1.U(2.W)
  def err_mtdt_finv = 2.U(2.W)
  def err_wrong_dev = 3.U(2.W)
}

class DBCheckerMtdt extends Bundle with DBCheckerConst {
  val index_offset = UInt(4.W)
  val reserved     = UInt(19.W)
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

}

object DBCheckerConfig {
  val requestDepth = sys.env.getOrElse("DBCHECKER_D", "64").toInt
  val refillCapacity = sys.env.getOrElse("DBCHECKER_KMAX", "64").toInt
  val cacheEntries = sys.env.getOrElse("DBCHECKER_CACHE", "4096").toInt
  // Occupancy/status MMIO fields are eight bits, including the full count.
  require(requestDepth >= 2 && requestDepth <= 128 && isPow2(requestDepth))
  require(refillCapacity >= 2 && refillCapacity <= 128 && isPow2(refillCapacity))
  require(cacheEntries >= 2 && cacheEntries <= 32768 && isPow2(cacheEntries))
  val slotBits = log2Ceil(requestDepth)
  val creditBits = log2Ceil(refillCapacity + 1)
}

class DBCheckerRequest extends Bundle {
  val axi_a = new AxiAddr(64, idWidth = 5)
  val is_write = Bool()
  val bypass = Bool()
}
class DBCheckerMetaKey extends Bundle {
  val slot = UInt(DBCheckerConfig.slotBits.W)
  val index = UInt(16.W)
}
object DBCheckerMetaStatus {
  val data = 0.U(2.W)
  val miss = 1.U(2.W)
  val invalid = 2.U(2.W)
  val retry = 3.U(2.W)
}
class DBCheckerMetaDone extends Bundle {
  val key = new DBCheckerMetaKey
  val status = UInt(2.W)
  val dbte = UInt(128.W)
}
class DBCheckerFill extends Bundle {
  val key = new DBCheckerMetaKey
  val dbte = UInt(128.W)
}
class DBCheckerFreeWindow extends Bundle {
  val active = Bool()
  val clear_all = Bool()
  val index = UInt(16.W)
  def matches(idx: UInt): Bool = active && (clear_all || index === idx)
}
class DBCheckerCommit extends Bundle {
  val request = new DBCheckerRequest
  val error = Bool()
  val error_info = new DBCheckerErrReq
}
class DBCheckerEvents extends Bundle {
  val accepted = Bool()
  val committed = Bool()
  val lookup = Bool()
  val hit = Bool()
  val ar = Bool()
  val r = Bool()
  val refill_done = Bool()
  val retry = Bool()
  val input_stall = Bool()
  val head_wait = Bool()
  val output_ar = Bool()
  val output_aw = Bool()
}
