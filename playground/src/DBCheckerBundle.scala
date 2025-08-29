package DBChecker

import chisel3._
import chisel3.util._

trait DBCheckerConst {
  val debug = true
  val RegNum = 8 // total register num
  val addrWidth = 32 // datawidth = 64

  val magic_num = 0x2a // 101010
  val dbte_num  = 4096
  
  // reg index (actual addr is 8 byte aligned)
  // checker enable register  (0x0, RW) : checker enable / disable register; reserved
  val chk_en        = 0x0 // 0x00
  // checker command register (0x1, R, W only when not valid) : used to alloc / free metadata
  val chk_cmd       = 0x1 // 0x08
  // checker result register (0x2, RO) : when alloc, read this register to get encrypted pointer
  val chk_res       = 0x2 // 0x10
  // checker key lowbit register (0x3, WO, if debug RW)
  val chk_keyl      = 0x3 // 0x18
  // checker key highbit register (0x4, WO, if debug RW)
  val chk_keyh      = 0x4 // 0x20
  // checker error counter register (0x5, RO)
  val chk_err_cnt   = 0x5 // 0x28
  // checker error info register (0x6, RO)
  val chk_err_info  = 0x6 // 0x30
  // checker error metadata register (0x7, RO)
  val chk_err_mtdt  = 0x7 // 0x38

    def cmd_op_free  = 0.U(2.W)
    def cmd_op_alloc = 1.U(2.W)
    def cmd_op_clr_err = 2.U(2.W)

    def cmd_status_inv     = 0.U(2.W)
    def cmd_status_req     = 1.U(2.W)
    def cmd_status_ok      = 2.U(2.W)
    def cmd_status_error   = 3.U(2.W)

    def mtdt_typ_s = 0.U(1.W)
    def mtdt_typ_l = 1.U(1.W)  

    def err_bnd_farea  = 0.U(2.W)
    def err_bnd_ftype  = 1.U(2.W)
    def err_mtdt_finv  = 2.U(2.W)
    def err_mtdt_fmn   = 3.U(2.W)
}

  // ------------------------------------------------------------------------------------------------------
  // |magic_num(6)|id(3)|type(1)|w(1)|r(1)|limit_offset(s:16, l:32)|limit_base(s:36, l:20(low 16 bit = 0))|
  // ------------------------------------------------------------------------------------------------------


class DBCheckerMtdt extends Bundle with DBCheckerConst{
    val mn           = UInt(6.W)
    val id           = UInt(3.W)
    val typ          = UInt(1.W)
    val w            = UInt(1.W)
    val r            = UInt(1.W)
    val bnd          = UInt(52.W)

    def get_index: UInt = {
        this.asUInt(63, 64 - log2Up(dbte_num))
    }
    def get_ptr(base: UInt): UInt = {
        Cat(this.asUInt(63, 36), base)
    }
    def get_dbte: UInt = {
        this.asUInt(35, 0)
    }
}

class DBCheckerBndS extends Bundle {
    val limit_offset = UInt(16.W)
    val limit_base   = UInt(36.W)
}

class DBCheckerBndL extends Bundle {
    val limit_offset = UInt(32.W)
    val limit_base   = UInt(20.W)
}

  // ---------------------------------------------------
  // |cnt3(15)|cnt2(15)|cnt1(15)|cnt0(15)|latest err(4)|
  // ---------------------------------------------------
  // 0: bnd out-of-bound error
  // 1: bnd type mismatch error
  // 2: mtdt invalid error
  // 3: mtdt wrong magic_num  error

class DBCheckerErrCnt extends Bundle {
    val cnt        = Vec(4, UInt(15.W))
    val err_latest = UInt(4.W)
}
class DBCheckerErrReq extends Bundle {
    val typ = UInt(2.W) // 0: bnd, 1: mtdt
    val info = UInt(64.W)
    val mtdt = UInt(64.W)
}

  // -----------------------------------------------------------------------------------------------------------
  // |status(2)|operation(0:free 1:alloc 2:clr_err)|0...0|E(metadata)_index(if free) / metadata[53:0] (if alloc)|
  // -----------------------------------------------------------------------------------------------------------

class DBCheckerCommand extends Bundle {
    val status = UInt(2.W) 
    val op  = UInt(2.W)
    val pad = UInt(5.W)
    val imm = UInt(55.W)
}

  // ------------------------------------
  // |E(metadata)[63:36]|access_addr(36)|
  // ------------------------------------

class DBCheckerPtr extends Bundle with DBCheckerConst{
    val e_mtdt_hi = UInt(28.W)
    val access_addr = UInt(36.W)
    def get_index: UInt = {
        this.asUInt(63, 64 - log2Up(dbte_num))
    }
}

object DBTEAllocState extends ChiselEnum {
  val waitEncryptReq, waitEncryptResp = Value
}

object DBCheckerState extends ChiselEnum {
  val ReadDBTE, WaitCheckreq, WaitCheckresp, Release, Return = Value
}