package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerCtrl extends Module with DBCheckerConst {
  assert(RegNum >= 2, "RegNum must be at least 2")
  // io
  val s_axil       = IO(new AxiLiteSlave(32, 32))
  // val m_axi        = IO(new AxiMaster(32, 128))
  val ctrl_reg     = IO(Output(Vec(RegNum, UInt(32.W))))
  val dbte_v_bm    = IO(Output(UInt(dbte_num.W)))
  val dbte_sram_w  = IO(Flipped(new MemoryWritePort(UInt(128.W), log2Up(dbte_num), false)))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val err_req_r    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val err_req_w    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val refill_dbte_req_if = IO(Flipped(Decoupled(new DBCheckerDBTEReq)))
  val refill_dbte_rsp_if = IO(Decoupled(new DBCheckerDBTERsp))
  val m_axi_dbte   = IO(new AxiMaster(48, 128))

  val debug_if = IO(Output(UInt(128.W)))

  // register file r/w logic
  val regFile = RegInit(VecInit(Seq.fill(RegNum)(0.U(32.W))))
  ctrl_reg := regFile

  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)

  // Internal signals
  val readAddrReg  = RegInit(0.U(32.W))
  val writeAddrReg = RegInit(0.U(32.W))
  val writeDataReg = RegInit(0.U(32.W))
  val writeStrbReg = RegInit(0.U(4.W))
  // Default outputs
  s_axil.aw.ready    := false.B
  s_axil.w.ready     := false.B
  s_axil.b.valid     := false.B
  s_axil.b.bits.resp := 0.U // OKAY
  s_axil.ar.ready    := false.B
  s_axil.r.valid     := false.B
  s_axil.r.bits.resp := 0.U // OKAY
  s_axil.r.bits.data := 0.U

  // FSM logic
  switch(state) {
    is(AXILiteState.Idle) {
      // Priority to write over read
      when(s_axil.aw.valid && s_axil.w.valid) {
        // Capture write address and data
        writeAddrReg    := s_axil.aw.bits.addr
        writeDataReg    := s_axil.w.bits.data
        writeStrbReg    := s_axil.w.bits.strb
        s_axil.aw.ready := true.B
        s_axil.w.ready  := true.B
        state           := AXILiteState.writeData
      }.elsewhen(s_axil.ar.valid) {
        // Capture read address
        readAddrReg     := s_axil.ar.bits.addr
        s_axil.ar.ready := true.B
        state           := AXILiteState.readData
      }
    }

    is(AXILiteState.readData) {
      s_axil.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(log2Up(RegNum) + 1, 2)
      when(index < RegNum.U && readAddrReg(1, 0) === 0.U) {
        // read logic
        s_axil.r.bits.data := regFile(index)
      }.otherwise {
        s_axil.r.bits.data := 0.U
        s_axil.r.bits.resp := 0.U // fake SLVERR for invalid address
      }
      when(s_axil.r.ready) {
        state := AXILiteState.Idle
      }
    }

    is(AXILiteState.writeData) {
      // Perform write operations
      val index = writeAddrReg(log2Up(RegNum) + 1, 2) // aligned
      when(index < RegNum.U && writeAddrReg(1, 0) === 0.U) {
        // Byte-wise write using w.bits.strb
        // write logic
        val wmask = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
        when((index === chk_cmd.U && !regFile(index).asTypeOf(new DBCheckerCommand).v) || 
              index === chk_en.U || index === chk_dbte_mb_hi.U || index === chk_dbte_mb_lo.U)
        {
          // write success
          regFile(index) := (regFile(index) & ~wmask) | (writeDataReg & wmask)
        }
      }.otherwise {
        s_axil.b.bits.resp := 0.U // fake SLVERR for invalid address or write RO reg
      }
      // when cmd is already valid, wait until it is processed, then write and change state
      when (!(index === chk_cmd.U && regFile(index).asTypeOf(new DBCheckerCommand).v)) {
        state := AXILiteState.writeResp
      }
    }

    is(AXILiteState.writeResp) {
      s_axil.b.valid := true.B
      when(s_axil.b.ready) {
        state := AXILiteState.Idle
      }
    }
  }

  // special logic for DBChecker control process

  // DBTE ram table, 128 bits each
  dbte_sram_r.address := 0.U
  dbte_sram_r.enable  := false.B

  dbte_sram_w.address := 0.U
  dbte_sram_w.data    := 0.U
  dbte_sram_w.enable  := false.B

  val free_sram_wait = RegInit(false.B)
  // valid bitmap for DBTE entries
  val dbte_v_bitmap  = RegInit(0.U(dbte_num.W))
  dbte_v_bm := dbte_v_bitmap

  val cmd_reg            = regFile(chk_cmd)
  val cmd_reg_struct     = cmd_reg.asTypeOf(new DBCheckerCommand)
  val err_cnt_reg        = regFile(chk_err_cnt)
  val err_cnt_reg_struct = err_cnt_reg.asTypeOf(new DBCheckerErrCnt)
  val err_info_reg       = regFile(chk_err_info)
  val err_addr_lo_reg    = regFile(chk_err_addr_lo)
  val err_addr_hi_reg    = regFile(chk_err_addr_hi)

  val is_freeing = cmd_reg_struct.v && cmd_reg_struct.op === cmd_op_free

  when(cmd_reg_struct.v) { // command is valid
    switch(cmd_reg_struct.op) {
      is(cmd_op_free) { // free
        // Free the DBTE entry
        val clear_all  = cmd_reg_struct.imm(16)
        val dbte_index_hi = cmd_reg_struct.get_index_hi
        when (clear_all) {
          dbte_v_bitmap := 0.U
          val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
          clr_cmd.v     := false.B
          cmd_reg       := clr_cmd.asUInt // clear v
        }
        .elsewhen(dbte_v_bitmap(dbte_index_hi)){
          when(!free_sram_wait) {
            dbte_sram_r.address := dbte_index_hi
            dbte_sram_r.enable  := true.B
            free_sram_wait      := true.B
          }.otherwise {
            free_sram_wait := false.B
            val index_offset_in_cache = dbte_sram_r.data.asTypeOf(new DBCheckerMtdt).index_offset
            when(index_offset_in_cache === cmd_reg_struct.get_index_lo) {
              // the entry to be freed matches the cached one
              dbte_v_bitmap := dbte_v_bitmap & ~(1.U << dbte_index_hi)
            }
            val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
            clr_cmd.v     := false.B
            cmd_reg       := clr_cmd.asUInt // clear v
            // otherwise do nothing
          }
        }
      }
      is(cmd_op_clr_err) { // clear err counter
          val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
          clr_cmd.v     := false.B
          cmd_reg       := clr_cmd.asUInt // clear v
          err_cnt_reg     := 0.U
          err_info_reg    := 0.U
          err_addr_hi_reg := 0.U
          err_addr_lo_reg := 0.U
      }
    }
  }

  // error reg handler

  val next_cnt    = Wire((new DBCheckerErrCnt).cnt.cloneType)
  val cnt_max_val = (1.U << next_cnt(0).getWidth) - 1.U

  for (i <- 0 until 4) {
    next_cnt(i) := Mux(
      err_cnt_reg_struct.cnt(i) <= cnt_max_val,
      (err_cnt_reg_struct.cnt(
        i
      ) + (err_req_r.valid && err_req_r.bits.typ === i.U).asUInt + (err_req_w.valid && err_req_w.bits.typ === i.U).asUInt),
      cnt_max_val
    )
  }
  val err_latest = Mux(err_req_r.valid, 1.U << err_req_r.bits.typ, 1.U << err_req_w.bits.typ)
  val cnt_enable = !(cmd_reg_struct.v && cmd_reg_struct.op === cmd_op_clr_err)
  when(cnt_enable && (err_req_r.valid || err_req_w.valid)) { // not clr_err
    err_cnt_reg  := Cat(next_cnt.asUInt, err_latest)
    err_info_reg := Mux(err_req_r.valid, err_req_r.bits.info, err_req_w.bits.info)
    err_addr_hi_reg := Mux(err_req_r.valid, err_req_r.bits.addr(63,32), err_req_w.bits.addr(63,32))
    err_addr_lo_reg := Mux(err_req_r.valid, err_req_r.bits.addr(31, 0), err_req_w.bits.addr(31, 0))
  }
  err_req_r.ready := cnt_enable
  err_req_w.ready := cnt_enable

  // DBTE refill handler
  val refill_state = RegInit(DBCheckerRefillState.AR)
  val refill_index_reg = RegInit(0.U(16.W))
  val refill_index_reg_hi = refill_index_reg(15, 16 - log2Up(dbte_num))
  val refill_data_reg  = RegInit(0.U(128.W))
  val dbte_base = Cat(regFile(chk_dbte_mb_hi), regFile(chk_dbte_mb_lo))
  val index_collision = refill_index_reg === cmd_reg_struct.get_index

  // aw / w / b are not used
  m_axi_dbte.aw.valid := false.B
  m_axi_dbte.aw.bits  := 0.U.asTypeOf(m_axi_dbte.aw.bits)
  m_axi_dbte.w.valid := false.B
  m_axi_dbte.w.bits  := 0.U.asTypeOf(m_axi_dbte.w.bits)
  m_axi_dbte.b.ready := false.B 

  // ar / r are used to read DBTE entries from memory
  m_axi_dbte.ar.valid     := refill_state === DBCheckerRefillState.AR && refill_dbte_req_if.valid
  m_axi_dbte.ar.bits       := 0.U.asTypeOf(m_axi_dbte.ar.bits)
  m_axi_dbte.ar.bits.addr  := dbte_base + (refill_dbte_req_if.bits.index << 4) // DBTE entries addr from memory
  m_axi_dbte.ar.bits.len   := 0.U
  m_axi_dbte.ar.bits.size  := 4.U // 16 bytes
  m_axi_dbte.ar.bits.burst := 1.U // INCR

  m_axi_dbte.r.ready := refill_state === DBCheckerRefillState.R

  refill_dbte_req_if.ready := refill_state === DBCheckerRefillState.AR && m_axi_dbte.ar.ready

  refill_dbte_rsp_if.valid := refill_state === DBCheckerRefillState.RSP
  refill_dbte_rsp_if.bits.dbte := refill_data_reg

  switch(refill_state) {
    is(DBCheckerRefillState.AR) {
      when(m_axi_dbte.ar.fire) {
        refill_index_reg := refill_dbte_req_if.bits.index
        refill_state := DBCheckerRefillState.R
      }
    }
    is(DBCheckerRefillState.R) {
      when(m_axi_dbte.r.fire) {
        refill_data_reg   := m_axi_dbte.r.bits.data
        when (m_axi_dbte.r.bits.data.asTypeOf(new DBCheckerMtdt).v) {
          refill_state := DBCheckerRefillState.WB
        }.otherwise{
          refill_state := DBCheckerRefillState.RSP
        }
      }
    }
    is(DBCheckerRefillState.WB) {
      // write back to SRAM and set bitmap
        when(is_freeing) {
          // do not write back when freeing
          // if the index is euqal between the one being freed and refilled, it is a collision
          // in this case, we need to clear the valid bit
          when(index_collision) {
            val invalid_mtdt = WireInit(refill_data_reg.asTypeOf(new DBCheckerMtdt))
            invalid_mtdt.v  := false.B
            refill_data_reg := invalid_mtdt.asUInt
            refill_state := DBCheckerRefillState.RSP
          }
          // otherwise wait until freeing is done
        }.otherwise {
          dbte_sram_w.address := refill_index_reg_hi
          dbte_sram_w.data    := refill_data_reg
          dbte_sram_w.enable  := true.B
          dbte_v_bitmap       := dbte_v_bitmap | (1.U << refill_index_reg_hi)
          refill_state := DBCheckerRefillState.RSP
        }
      }
    is(DBCheckerRefillState.RSP) {
      when(refill_dbte_rsp_if.ready) {
        refill_state := DBCheckerRefillState.AR
      }
    }
  }

  debug_if := Cat(cmd_reg,err_info_reg,err_addr_hi_reg,err_addr_lo_reg) // reserved
}
