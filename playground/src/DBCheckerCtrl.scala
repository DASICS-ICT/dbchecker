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
  val perf_event   = IO(Input(new DBCheckerPerfEvent))

  // CAM interface exposed to pipeline
  val cam_lookup_key   = IO(Input(UInt(16.W)))
  val cam_lookup_valid = IO(Output(Bool()))
  val cam_lookup_id    = IO(Output(UInt(7.W)))
  val cam_insert_key   = IO(Input(UInt(16.W)))
  val cam_insert_valid = IO(Input(Bool()))
  val cam_insert_id    = IO(Output(UInt(7.W)))
  val cam_remove_key   = IO(Input(UInt(16.W)))
  val cam_remove_valid = IO(Input(Bool()))
  val cam_counter_slot   = IO(Input(UInt(7.W)))
  val cam_counter_bytes  = IO(Input(UInt(8.W)))
  val cam_counter_init_target = IO(Input(UInt(48.W)))
  val cam_counter_update = IO(Input(Bool()))
  val cam_auto_clear     = IO(Output(Bool()))
  val cam_used_slots     = IO(Output(UInt(8.W)))
  val cam_full           = IO(Output(Bool()))

  // Auto-clear request from pipeline (wired in commit 6)
  val auto_clear_req = IO(Flipped(Decoupled(new AutoClearReq)))

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

  // auto-clear FSM state (declared early for use in readData/perf logic)
  val auto_clear_state = RegInit(AutoClearState.Idle)
  val auto_clear_index = Reg(UInt(16.W))
  val auto_clear_index_off = Reg(UInt(4.W))

  // perf counters (declared early for use in readData state)
  val perf_hit_cnt     = RegInit(0.U(32.W))
  val perf_miss_cnt    = RegInit(0.U(32.W))
  val perf_penalty_cnt = RegInit(0.U(32.W))
  val auto_rel_cnt     = RegInit(0.U(32.W))

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
        switch(index) {
          is(chk_perf_hit.U)     { s_axil.r.bits.data := perf_hit_cnt }
          is(chk_perf_miss.U)    { s_axil.r.bits.data := perf_miss_cnt }
          is(chk_perf_penalty.U) { s_axil.r.bits.data := perf_penalty_cnt }
          is(chk_auto_rel_status.U) {
            val auto_rel_active = auto_clear_state === AutoClearState.Verify
            s_axil.r.bits.data := Cat(0.U(15.W), auto_rel_active, cam_full, 0.U(7.W), cam_used_slots)
          }
          is(chk_auto_rel_perf.U)    { s_axil.r.bits.data := auto_rel_cnt }
        }
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
        }.otherwise {
          val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
          clr_cmd.v := false.B
          cmd_reg   := clr_cmd.asUInt // clear v
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
    next_cnt(i) := 
      err_cnt_reg_struct.cnt(i) +& 
      (err_req_r.valid && err_req_r.bits.typ === i.U).asUInt +& 
      (err_req_w.valid && err_req_w.bits.typ === i.U).asUInt
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
  val refill_data_reg  = RegInit(0.U(128.W))
  val refill_index_reg_hi = refill_index_reg(15, 16 - log2Up(dbte_num))
  val dbte_base = Cat(regFile(chk_dbte_mb_hi), regFile(chk_dbte_mb_lo))
  val index_collision = refill_index_reg === cmd_reg_struct.get_index

  // aw / w / b are not used
  m_axi_dbte.aw.valid := false.B
  m_axi_dbte.aw.bits  := 0.U.asTypeOf(m_axi_dbte.aw.bits)
  m_axi_dbte.w.valid := false.B
  m_axi_dbte.w.bits  := 0.U.asTypeOf(m_axi_dbte.w.bits)
  m_axi_dbte.b.ready := false.B 

  // ar / r are used to read DBTE entries from memory
  // bits are preset to values
  m_axi_dbte.ar.bits       := 0.U.asTypeOf(m_axi_dbte.ar.bits)
  m_axi_dbte.ar.bits.cache := "b1011".U(4.W) // 4'b1011, cacheable, bufferable
  m_axi_dbte.ar.bits.prot  := "b010".U(3.W) // 3'b010, unprivileged, non-secure, data access
  m_axi_dbte.ar.bits.addr  := dbte_base + (refill_dbte_req_if.bits.index << 4) // DBTE entries addr from memory
  m_axi_dbte.ar.bits.len   := 0.U
  m_axi_dbte.ar.bits.size  := 4.U // 16 bytes
  m_axi_dbte.ar.bits.burst := 1.U // INCR

  // valid / ready are preset to false
  m_axi_dbte.ar.valid      := false.B
  m_axi_dbte.r.ready       := false.B

  refill_dbte_req_if.ready := false.B
  refill_dbte_rsp_if.valid := false.B
  refill_dbte_rsp_if.bits.dbte := 0.U

  switch(refill_state) {
    is(DBCheckerRefillState.AR) {
      m_axi_dbte.ar.valid := refill_dbte_req_if.valid
      refill_dbte_req_if.ready := m_axi_dbte.ar.ready
      when(m_axi_dbte.ar.fire) {
        refill_index_reg := refill_dbte_req_if.bits.index
        refill_state := DBCheckerRefillState.R
      }
    }
    is(DBCheckerRefillState.R) {
      m_axi_dbte.r.ready := true.B
      when(m_axi_dbte.r.fire) {
        // save data to keep good timing
        refill_data_reg   := m_axi_dbte.r.bits.data
        refill_state      := DBCheckerRefillState.WB
      }
    }
    is(DBCheckerRefillState.WB) {
      val rb_mtdt = refill_data_reg.asTypeOf(new DBCheckerMtdt)
      when (rb_mtdt.v) {
        // write back to SRAM and set bitmap
        when(is_freeing) {
          // do not write back when freeing
          // if the index is euqal between the one being freed and refilled, it is a collision
          // in this case, we need to clear the valid bit
          when(index_collision) {
            val invalid_mtdt = WireInit(rb_mtdt)
            invalid_mtdt.v  := false.B
            refill_dbte_rsp_if.valid := true.B
            refill_dbte_rsp_if.bits.dbte := invalid_mtdt.asUInt
            when(refill_dbte_rsp_if.ready) {
              m_axi_dbte.r.ready := true.B
              refill_state := DBCheckerRefillState.AR
            }
          }
          // otherwise wait until freeing is done
        }.otherwise {
          dbte_sram_w.address := refill_index_reg_hi
          dbte_sram_w.data    := rb_mtdt.asUInt
          dbte_sram_w.enable  := true.B
          dbte_v_bitmap       := dbte_v_bitmap | (1.U << refill_index_reg_hi)
          refill_dbte_rsp_if.valid := true.B
          refill_dbte_rsp_if.bits.dbte := rb_mtdt.asUInt
          when(refill_dbte_rsp_if.ready) {
            m_axi_dbte.r.ready := true.B
            refill_state := DBCheckerRefillState.AR
          }
        }
      }.otherwise{
        refill_dbte_rsp_if.valid := true.B
        refill_dbte_rsp_if.bits.dbte := rb_mtdt.asUInt
        when(refill_dbte_rsp_if.ready) {
          m_axi_dbte.r.ready := true.B
          refill_state := DBCheckerRefillState.AR
        }
      }
    }
  }

  debug_if := Cat(cmd_reg,err_info_reg,err_addr_hi_reg,err_addr_lo_reg) // reserved

  // perf counter logic
  def saturated(cnt: UInt): Bool = cnt(31, 16).andR

  when(!saturated(perf_hit_cnt) && perf_event.hit) {
    perf_hit_cnt := perf_hit_cnt + 1.U
  }
  when(!saturated(perf_miss_cnt) && perf_event.miss) {
    perf_miss_cnt := perf_miss_cnt + 1.U
  }
  when(!saturated(perf_penalty_cnt) && perf_event.penalty) {
    perf_penalty_cnt := perf_penalty_cnt + 1.U
  }

  // auto-release perf counters
  when(!saturated(auto_rel_cnt) && auto_clear_state === AutoClearState.Verify) {
    auto_rel_cnt := auto_rel_cnt + 1.U
  }

  // soft reset perf counters when chk_en is written with non-zero value
  val wmask_perf = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
  val chk_en_write_nonzero = (state === AXILiteState.writeData) &&
                             (writeAddrReg(log2Up(RegNum) + 1, 2) === chk_en.U) &&
                             ((writeDataReg & wmask_perf) =/= 0.U)
  when(chk_en_write_nonzero) {
    perf_hit_cnt     := 0.U
    perf_miss_cnt    := 0.U
    perf_penalty_cnt := 0.U
    auto_rel_cnt     := 0.U
  }

  // --- Auto-Release CAM + Counter ---
  val cam = Module(new DBCheckerCAM(128, 16))

  // Wire CAM IO from pipeline (combinational passthrough)
  cam.io.lookup_key   := cam_lookup_key
  cam_lookup_valid    := cam.io.lookup_valid
  cam_lookup_id       := cam.io.lookup_id
  cam.io.insert_key   := cam_insert_key
  cam.io.insert_valid := cam_insert_valid
  cam_insert_id       := cam.io.insert_id
  cam.io.counter_slot   := cam_counter_slot
  cam.io.counter_bytes  := cam_counter_bytes
  cam.io.counter_init_target := cam_counter_init_target
  cam.io.counter_update := cam_counter_update
  cam_auto_clear        := cam.io.auto_clear
  cam_used_slots        := cam.io.used_slots
  cam_full              := cam.io.cam_full

  // clear_all: wired from FREE clear_all command
  cam.io.clear_all := cmd_reg_struct.v && cmd_reg_struct.op === cmd_op_free && cmd_reg_struct.imm(16)

  // --- Auto-Clear FSM logic ---
  // (auto_clear_state, auto_clear_index, auto_clear_index_off declared above)

  // Default: pipeline drives remove; FSM overrides in Verify state
  cam.io.remove_key   := cam_remove_key
  cam.io.remove_valid := cam_remove_valid

  // SRAM read arbitration: FREE has priority over auto-clear
  // FREE uses dbte_sram_r when !free_sram_wait and dbte_v_bitmap set
  val free_needs_sram = is_freeing && dbte_v_bitmap(cmd_reg_struct.get_index_hi) && !free_sram_wait
  val ac_needs_sram   = auto_clear_state === AutoClearState.Idle && auto_clear_req.valid

  when(ac_needs_sram && !free_needs_sram) {
    dbte_sram_r.address := auto_clear_req.bits.index(15, 16 - log2Up(dbte_num))
    dbte_sram_r.enable  := true.B
  }

  // Default: pipeline drives auto_clear_req (overridden in FSM states)
  auto_clear_req.ready := false.B

  switch(auto_clear_state) {
    is(AutoClearState.Idle) {
      when(auto_clear_req.valid && !free_needs_sram) {
        auto_clear_req.ready := true.B
        auto_clear_index     := auto_clear_req.bits.index
        auto_clear_index_off := auto_clear_req.bits.index_offset
        auto_clear_state     := AutoClearState.Verify
      }
    }
    is(AutoClearState.Verify) {
      val sram_entry = dbte_sram_r.data.asTypeOf(new DBCheckerMtdt)
      val index_hi = auto_clear_index(15, 16 - log2Up(dbte_num))
      when(sram_entry.index_offset === auto_clear_index_off &&
           dbte_v_bitmap(index_hi)) {
        dbte_v_bitmap := dbte_v_bitmap & ~(1.U << index_hi)
      }
      // Remove from CAM (transfer owning this CAM slot is done)
      cam.io.remove_key   := auto_clear_index
      cam.io.remove_valid := true.B
      auto_clear_state := AutoClearState.Idle
    }
  }
}
