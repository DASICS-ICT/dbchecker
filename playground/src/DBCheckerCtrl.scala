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
  val invalidate   = IO(Output(new DBCheckerInvalidate))
  val dbte_sram_w  = IO(Flipped(new MemoryWritePort(UInt(128.W), log2Up(dbte_num), false)))
  val dbte_meta_sram_w = IO(Flipped(new MemoryWritePort(new DBCheckerCacheMeta, log2Up(dbte_set_num), false)))
  val dbte_meta_sram_r = IO(Flipped(new MemoryReadPort(new DBCheckerCacheMeta, log2Up(dbte_set_num))))
  val err_req_r    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val err_req_w    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val refill_dbte_req_if = IO(Flipped(Decoupled(new DBCheckerDBTEReq)))
  val refill_dbte_rsp_if = IO(Decoupled(new DBCheckerDBTERsp))
  val m_axi_dbte   = IO(new AxiMaster(48, 128))
  val perf_event   = IO(Input(new DBCheckerPerfEvent))

  val debug_if = IO(Output(UInt(128.W)))

  // register file r/w logic
  val regFile = RegInit(VecInit(Seq.tabulate(RegNum) { index =>
    if (index == chk_refill_cfg) 1.U(32.W) else 0.U(32.W)
  }))
  ctrl_reg := regFile
  val refill_cfg_idle = WireDefault(false.B)

  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)

  // Internal signals
  val readAddrReg  = RegInit(0.U(32.W))
  val writeAddrReg = RegInit(0.U(32.W))
  val writeDataReg = RegInit(0.U(32.W))
  val writeStrbReg = RegInit(0.U(4.W))

  // perf counters (declared early for use in readData state)
  val perf_hit_cnt     = RegInit(0.U(32.W))
  val perf_miss_cnt    = RegInit(0.U(32.W))
  val perf_penalty_cnt = RegInit(0.U(32.W))
  val perf_refill_hist = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  val perf_diff_line_wait_cnt = RegInit(0.U(32.W))
  val perf_rob_full_cnt = RegInit(0.U(32.W))
  val perf_refill_bytes_cnt = RegInit(0.U(32.W))

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
          is(chk_refill_hist_1.U)  { s_axil.r.bits.data := perf_refill_hist(0) }
          is(chk_refill_hist_2.U)  { s_axil.r.bits.data := perf_refill_hist(1) }
          is(chk_refill_hist_3.U)  { s_axil.r.bits.data := perf_refill_hist(2) }
          is(chk_refill_hist_4p.U) { s_axil.r.bits.data := perf_refill_hist(3) }
          is(chk_diff_line_wait.U) { s_axil.r.bits.data := perf_diff_line_wait_cnt }
          is(chk_rob_full.U)       { s_axil.r.bits.data := perf_rob_full_cnt }
          is(chk_refill_bytes.U)   { s_axil.r.bits.data := perf_refill_bytes_cnt }
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
              index === chk_en.U || index === chk_dbte_mb_hi.U || index === chk_dbte_mb_lo.U ||
              (index === chk_refill_cfg.U && regFile(chk_en) === 0.U && refill_cfg_idle))
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
  dbte_sram_w.address := 0.U
  dbte_sram_w.data    := 0.U
  dbte_sram_w.enable  := false.B
  dbte_meta_sram_w.address := 0.U
  dbte_meta_sram_w.data := 0.U.asTypeOf(new DBCheckerCacheMeta)
  dbte_meta_sram_w.enable := false.B
  dbte_meta_sram_r.address := 0.U
  dbte_meta_sram_r.enable := false.B

  val cmd_reg            = regFile(chk_cmd)
  val cmd_reg_struct     = cmd_reg.asTypeOf(new DBCheckerCommand)
  val err_cnt_reg        = regFile(chk_err_cnt)
  val err_cnt_reg_struct = err_cnt_reg.asTypeOf(new DBCheckerErrCnt)
  val err_info_reg       = regFile(chk_err_info)
  val err_addr_lo_reg    = regFile(chk_err_addr_lo)
  val err_addr_hi_reg    = regFile(chk_err_addr_hi)

  val is_freeing = cmd_reg_struct.v && cmd_reg_struct.op === cmd_op_free
  val cache_init_active = RegInit(true.B)
  val cache_init_ptr = RegInit(0.U(log2Up(dbte_set_num).W))
  val free_tag_wait = RegInit(false.B)
  val free_clear_ptr = RegInit(0.U(log2Up(dbte_set_num).W))
  val free_set = cmd_reg_struct.get_index(log2Up(dbte_num) - 1, log2Up(dbte_line_entries))
  val free_tag = cmd_reg_struct.get_index(15, log2Up(dbte_num))

  invalidate.valid := is_freeing && !cache_init_active
  invalidate.clear_all := is_freeing && !cache_init_active && cmd_reg_struct.imm(16)
  invalidate.index := cmd_reg_struct.get_index

  when(cmd_reg_struct.v && !cache_init_active) { // command is valid
    switch(cmd_reg_struct.op) {
      is(cmd_op_free) { // free
        // Cache invalidation itself is applied after refill writeback below, so
        // FREE wins every same-cycle race.
        when(cmd_reg_struct.imm(16)) {
          free_tag_wait := false.B
          dbte_meta_sram_w.address := free_clear_ptr
          dbte_meta_sram_w.data := 0.U.asTypeOf(new DBCheckerCacheMeta)
          dbte_meta_sram_w.enable := true.B
          when(free_clear_ptr === (dbte_set_num - 1).U) {
            free_clear_ptr := 0.U
            val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
            clr_cmd.v := false.B
            cmd_reg := clr_cmd.asUInt
          }.otherwise {
            free_clear_ptr := free_clear_ptr + 1.U
          }
        }.elsewhen(!free_tag_wait) {
          dbte_meta_sram_r.address := free_set
          dbte_meta_sram_r.enable := true.B
          free_tag_wait := true.B
        }.otherwise {
          val free_meta = dbte_meta_sram_r.data
          when(free_meta.tag === free_tag) {
            val cleared_meta = WireInit(free_meta)
            cleared_meta.valid := free_meta.valid & ~UIntToOH(cmd_reg_struct.get_index(1, 0), dbte_line_entries)
            dbte_meta_sram_w.address := free_set
            dbte_meta_sram_w.data := cleared_meta
            dbte_meta_sram_w.enable := true.B
          }
          free_tag_wait := false.B
          val clr_cmd = WireInit(cmd_reg.asTypeOf(new DBCheckerCommand))
          clr_cmd.v := false.B
          cmd_reg := clr_cmd.asUInt
        }
      }
      is(cmd_op_clr_err) { // clear err counter
          free_tag_wait   := false.B
          free_clear_ptr  := 0.U
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
  val refill_beat_reg = RegInit(0.U(log2Up(dbte_line_entries).W))
  val refill_wb_sector = RegInit(0.U(log2Up(dbte_line_entries).W))
  val refill_data_reg = RegInit(VecInit(Seq.fill(dbte_line_entries)(0.U(128.W))))
  val refill_rsp_data = RegInit(VecInit(Seq.fill(dbte_line_entries)(0.U(128.W))))
  val refill_error = RegInit(false.B)
  val refill_expected_done = RegInit(false.B)
  val refill_poison_mask = RegInit(0.U(dbte_line_entries.W))
  val refill_line64_reg = RegInit(true.B)
  val refill_old_meta = RegInit(0.U.asTypeOf(new DBCheckerCacheMeta))
  val dbte_base = Cat(regFile(chk_dbte_mb_hi), regFile(chk_dbte_mb_lo))
  val refill_line64 = regFile(chk_refill_cfg)(0)

  refill_cfg_idle := refill_state === DBCheckerRefillState.AR &&
                     !refill_dbte_req_if.valid

  // Before AR.fire there is no refill to poison, and AR is stalled while FREE
  // is active.  Once issued, only the registered index may feed WB/RSP.
  val refill_active = refill_state =/= DBCheckerRefillState.AR
  val free_hits_refill = is_freeing && refill_active &&
                         (cmd_reg_struct.imm(16) ||
                          cmd_reg_struct.get_index(15, 2) === refill_index_reg(15, 2))
  val free_sector_mask = Mux(cmd_reg_struct.imm(16),
                             Fill(dbte_line_entries, 1.U(1.W)),
                             UIntToOH(cmd_reg_struct.get_index(1, 0), dbte_line_entries))
  val effective_poison_mask = refill_poison_mask |
                              Mux(free_hits_refill, free_sector_mask, 0.U)

  // aw / w / b are not used
  m_axi_dbte.aw.valid := false.B
  m_axi_dbte.aw.bits  := 0.U.asTypeOf(m_axi_dbte.aw.bits)
  m_axi_dbte.w.valid := false.B
  m_axi_dbte.w.bits  := 0.U.asTypeOf(m_axi_dbte.w.bits)
  m_axi_dbte.b.ready := false.B 

  // ar / r are used to read DBTE entries from memory
  // bits are preset to values
  m_axi_dbte.ar.bits       := 0.U.asTypeOf(m_axi_dbte.ar.bits)
  m_axi_dbte.ar.bits.cache := "b1111".U(4.W) // 4'b1111, cacheable, bufferable
  m_axi_dbte.ar.bits.prot  := "b010".U(3.W) // 3'b010, unprivileged, non-secure, data access
  val refill_ar_index = Mux(refill_line64,
                            Cat(refill_dbte_req_if.bits.index(15, 2), 0.U(2.W)),
                            refill_dbte_req_if.bits.index)
  m_axi_dbte.ar.bits.addr  := dbte_base + (refill_ar_index << 4)
  m_axi_dbte.ar.bits.len   := Mux(refill_line64, (dbte_line_entries - 1).U, 0.U)
  m_axi_dbte.ar.bits.size  := 4.U // 16 bytes
  m_axi_dbte.ar.bits.burst := 1.U // INCR

  // valid / ready are preset to false
  m_axi_dbte.ar.valid      := false.B
  m_axi_dbte.r.ready       := false.B

  refill_dbte_req_if.ready := false.B
  refill_dbte_rsp_if.valid := false.B
  refill_dbte_rsp_if.bits.line_index := refill_index_reg(15, 2)
  refill_dbte_rsp_if.bits.dbte := refill_rsp_data

  // A FREE that overlaps RSP must also invalidate the value returned in that
  // cycle; relying only on the registered poison mask would be one cycle late.
  for (sector <- 0 until dbte_line_entries) {
    when(effective_poison_mask(sector)) {
      val invalid_rsp_mtdt = WireInit(refill_rsp_data(sector).asTypeOf(new DBCheckerMtdt))
      invalid_rsp_mtdt.v := false.B
      refill_dbte_rsp_if.bits.dbte(sector) := invalid_rsp_mtdt.asUInt
    }
  }

  switch(refill_state) {
    is(DBCheckerRefillState.AR) {
      m_axi_dbte.ar.valid := refill_dbte_req_if.valid && !is_freeing && !cache_init_active
      refill_dbte_req_if.ready := m_axi_dbte.ar.ready && !is_freeing && !cache_init_active
      when(m_axi_dbte.ar.fire) {
        refill_index_reg := refill_dbte_req_if.bits.index
        refill_line64_reg := refill_line64
        refill_beat_reg := 0.U
        refill_wb_sector := 0.U
        refill_error := false.B
        refill_expected_done := false.B
        refill_poison_mask := 0.U
        for (sector <- 0 until dbte_line_entries) {
          refill_data_reg(sector) := 0.U
          refill_rsp_data(sector) := 0.U
        }
        dbte_meta_sram_r.address := refill_dbte_req_if.bits.index(11, 2)
        dbte_meta_sram_r.enable := true.B
        refill_state := DBCheckerRefillState.R
      }
    }
    is(DBCheckerRefillState.R) {
      m_axi_dbte.r.ready := true.B
      when(m_axi_dbte.r.fire) {
        val expected_last = !refill_line64_reg ||
                            refill_beat_reg === (dbte_line_entries - 1).U
        val refill_sector = Mux(refill_line64_reg,
                                refill_beat_reg,
                                refill_index_reg(1, 0))
        when(!refill_expected_done) {
          refill_data_reg(refill_sector) := m_axi_dbte.r.bits.data
        }
        when(m_axi_dbte.r.bits.resp =/= 0.U || m_axi_dbte.r.bits.last =/= expected_last) {
          refill_error := true.B
        }
        when(m_axi_dbte.r.bits.last) {
          refill_wb_sector := 0.U
          refill_state := DBCheckerRefillState.WB
        }.elsewhen(expected_last) {
          // AXI slave returned RLAST late.  Keep draining this transaction so
          // the single-ID R channel is usable by the following refill.
          refill_expected_done := true.B
        }.otherwise {
          refill_beat_reg := refill_beat_reg + 1.U
        }
      }
    }
    is(DBCheckerRefillState.WB) {
      val wb_index = Cat(refill_index_reg(15, 2), refill_wb_sector)
      val wb_cache_addr = wb_index(log2Up(dbte_num) - 1, 0)
      val wb_set = wb_index(log2Up(dbte_num) - 1, log2Up(dbte_line_entries))
      val wb_tag = wb_index(15, log2Up(dbte_num))
      val wb_raw_mtdt = refill_data_reg(refill_wb_sector).asTypeOf(new DBCheckerMtdt)
      val wb_mtdt = WireInit(wb_raw_mtdt)
      when(wb_index === 0.U || refill_error || effective_poison_mask(refill_wb_sector) ||
           wb_raw_mtdt.index_offset =/= wb_index(3, 0)) {
        wb_mtdt.v := false.B
      }

      val fill_valid = Wire(Vec(dbte_line_entries, Bool()))
      for (sector <- 0 until dbte_line_entries) {
        val sector_index = Cat(refill_index_reg(15, 2), sector.U(log2Up(dbte_line_entries).W))
        val sector_mtdt = refill_data_reg(sector).asTypeOf(new DBCheckerMtdt)
        fill_valid(sector) := sector_index =/= 0.U && !refill_error &&
                              !effective_poison_mask(sector) &&
                              sector_mtdt.v && !sector_mtdt.no_cache &&
                              sector_mtdt.index_offset === sector_index(3, 0)
      }

      // Exact FREE uses the metadata SRAM write port, so writeback pauses until
      // the command completes.  The poison mask preserves the collision.
      when(!is_freeing) {
        refill_rsp_data(refill_wb_sector) := wb_mtdt.asUInt
        when(wb_mtdt.v && !wb_mtdt.no_cache) {
          dbte_sram_w.address := wb_cache_addr
          dbte_sram_w.data := wb_mtdt.asUInt
          dbte_sram_w.enable := true.B
        }

        // Publish the replacement tag and the valid mask with every data-SRAM
        // writeback beat.  A different-tag refill therefore evicts the old
        // line before any newly written sector can be observed under its tag.
        val demand_mask = UIntToOH(refill_index_reg(1, 0), dbte_line_entries)
        val preserved_valid = Mux(!refill_line64_reg && refill_old_meta.tag === wb_tag,
                                  refill_old_meta.valid,
                                  0.U) & ~demand_mask & ~effective_poison_mask
        val written_mask = VecInit((0 until dbte_line_entries).map(sector =>
          sector.U <= refill_wb_sector)).asUInt
        val new_meta = Wire(new DBCheckerCacheMeta)
        new_meta.tag := wb_tag
        new_meta.valid := (preserved_valid | (fill_valid.asUInt & written_mask)) &
                          ~effective_poison_mask
        dbte_meta_sram_w.address := wb_set
        dbte_meta_sram_w.data := new_meta
        dbte_meta_sram_w.enable := true.B

        when(refill_wb_sector === (dbte_line_entries - 1).U) {
          refill_state := DBCheckerRefillState.RSP
        }.otherwise {
          refill_wb_sector := refill_wb_sector + 1.U
        }
      }
    }
    is(DBCheckerRefillState.RSP) {
      refill_dbte_rsp_if.valid := true.B
      when(refill_dbte_rsp_if.fire) {
        refill_poison_mask := 0.U
        refill_state := DBCheckerRefillState.AR
      }
    }
  }

  // The metadata SRAM read launched with AR.fire returns on the next cycle.
  when(RegNext(m_axi_dbte.ar.fire, false.B)) {
    refill_old_meta := dbte_meta_sram_r.data
  }

  // Sticky per-sector poison captures FREE after an AR has been issued.
  when(free_hits_refill) {
    refill_poison_mask := refill_poison_mask | free_sector_mask
  }

  // SRAM has no reset port.  Clear every metadata set once after reset before
  // permitting refill traffic, so no uninitialized valid bit can create a hit.
  when(cache_init_active) {
    dbte_meta_sram_w.address := cache_init_ptr
    dbte_meta_sram_w.data := 0.U.asTypeOf(new DBCheckerCacheMeta)
    dbte_meta_sram_w.enable := true.B
    when(cache_init_ptr === (dbte_set_num - 1).U) {
      cache_init_ptr := 0.U
      cache_init_active := false.B
    }.otherwise {
      cache_init_ptr := cache_init_ptr + 1.U
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
  when(perf_event.refill_waiters =/= 0.U) {
    val waiter_bucket = Mux(perf_event.refill_waiters >= 4.U,
                            3.U(2.W),
                            (perf_event.refill_waiters - 1.U)(1, 0))
    when(!saturated(perf_refill_hist(waiter_bucket))) {
      perf_refill_hist(waiter_bucket) := perf_refill_hist(waiter_bucket) + 1.U
    }
  }
  when(!saturated(perf_diff_line_wait_cnt) && perf_event.different_line_wait) {
    perf_diff_line_wait_cnt := perf_diff_line_wait_cnt + 1.U
  }
  when(!saturated(perf_rob_full_cnt) && perf_event.rob_full) {
    perf_rob_full_cnt := perf_rob_full_cnt + 1.U
  }
  when(perf_event.refill_bytes =/= 0.U && !saturated(perf_refill_bytes_cnt)) {
    perf_refill_bytes_cnt := perf_refill_bytes_cnt + perf_event.refill_bytes
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
    perf_refill_hist := VecInit(Seq.fill(4)(0.U(32.W)))
    perf_diff_line_wait_cnt := 0.U
    perf_rob_full_cnt := 0.U
    perf_refill_bytes_cnt := 0.U
  }
}
