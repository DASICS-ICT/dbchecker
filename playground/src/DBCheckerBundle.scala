package DBChecker

import chisel3._
import chisel3.util._

trait DBCheckerConst {
  val debug = true.B
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

    def cmd_op_free  = 0.U
    def cmd_op_alloc = 1.U
    def cmd_op_clr_err = 2.U

    def mtdt_typ_offset = 53.U
    def mtdt_typ_s = 0.U
    def mtdt_typ_l = 1.U    

    def mtdt_rw_r = 0.U
    def mtdt_rw_w = 1.U

    def err_bnd  = 0.U
    def err_mtdt = 1.U
    def err_cmd  = 2.U
}

  // --------------------------------------------------------------------------------------------------
  // |magic_num(6)|id(4)|type(1)|R/W(1)|limit_offset(s:16, l:32)|limit_base(s:36, l:20(low 16 bit = 0))|
  // --------------------------------------------------------------------------------------------------


class DBCheckerMtdt extends Bundle {
    val magic_num    = UInt(6.W)
    val id           = UInt(4.W)
    val typ          = UInt(1.W)
    val rw           = UInt(1.W)
    val bnd          = UInt(52.W)
}

class DBCheckerBndS extends Bundle {
    val limit_offset = UInt(16.W)
    val limit_base   = UInt(36.W)
}

class DBCheckerBndL extends Bundle {
    val limit_offset = UInt(32.W)
    val limit_base   = UInt(20.W)
}

  // ------------------------------------------
  // |cnt2(20)|cnt1(20)|cnt0(20)|latest err(4)|
  // ------------------------------------------
  // 0: bnd err   (out of bound / permission error) 
  // 1: mtdt err  (err magic_num / metadata not valid) 
  // 2: cmd err   (illegal command; DBTE is full)
  // 3: reserved

class DBCheckerErrCnt extends Bundle {
    val cnt        = Vec(3, UInt(20.W))
    val err_latest = UInt(4.W)
}

  // -----------------------------------------------------------------------------------------------------------
  // |valid(1)|operation(0:free 1:alloc 2:clr_err)|0...0|E(metadata)_index(if free) / metadata[53:0] (if alloc)|
  // -----------------------------------------------------------------------------------------------------------

class DBCheckerCommand extends Bundle {
    val v   = Bool()
    val op  = UInt(2.W)
    val pad = UInt(7.W)
    val imm = UInt(54.W)
}

  // ------------------------------------
  // |E(metadata)[63:36]|access_addr(36)|
  // ------------------------------------

class DBCheckerPtr extends Bundle {
    val e_mtdt_hi = UInt(28.W)
    val access_addr = UInt(36.W)
}

object DBTEAllocState extends ChiselEnum {
  val waitEncryptReq, waitEncryptResp = Value
}

object DBCheckerRState extends ChiselEnum {
  val waitCheckARreq, waitCheckARresp, releaseAR, returnR = Value
}

object DBCheckerWState extends ChiselEnum {
  val waitCheckAWreq, waitCheckAWresp, waitW, releaseAWnW, returnB = Value
}