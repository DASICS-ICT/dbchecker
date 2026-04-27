package DBChecker

import chisel3._
import chisel3.util._
import axi._

// stage0-1: frontend
class DBCheckerPipeStage0 extends Module with DBCheckerConst { // receive AXI request
  val in_ar       = IO(Flipped(Decoupled(new AxiAddr(64, idWidth = 5))))
  val in_aw       = IO(Flipped(Decoupled(new AxiAddr(64, idWidth = 5))))
  val out_pipe    = IO(Decoupled(new DBCheckerPipeMedium))
  val ctrl_en     = IO(Input(new DBCheckerEnCtl))

  val pipe_v_reg    = RegInit(false.B)
  val rw_reg        = RegInit(false.B) // 0: R, 1: W
  val pipe_addr_reg = RegInit(0.U.asTypeOf(new AxiAddr(64, idWidth = 5)))

// handle logic
  when(in_ar.fire) {
    pipe_v_reg    := true.B
    pipe_addr_reg := in_ar.bits
    rw_reg        := false.B
  }.elsewhen(in_aw.fire) {
    pipe_v_reg    := true.B
    pipe_addr_reg := in_aw.bits
    rw_reg        := true.B
  }.elsewhen(out_pipe.fire) {
    pipe_v_reg    := false.B
    pipe_addr_reg := 0.U.asTypeOf(new AxiAddr(64, idWidth = 5))
    rw_reg        := false.B
  }

  in_ar.ready := !pipe_v_reg || out_pipe.fire
  in_aw.ready := (!pipe_v_reg || out_pipe.fire) && !in_ar.valid

  out_pipe.valid           := pipe_v_reg
  out_pipe.bits.axi_a      := pipe_addr_reg
  out_pipe.bits.axi_a_type := rw_reg
  out_pipe.bits.dbte       := 0.U.asTypeOf(UInt(128.W))
  out_pipe.bits.bypass     := !ctrl_en.en_dev_bm(pipe_addr_reg.id(4))
  out_pipe.bits.err_v      := false.B
  out_pipe.bits.err_req    := 0.U.asTypeOf(new DBCheckerErrReq)
}

class DBCheckerPipeStage1 extends Module with DBCheckerConst { // readDBTE

  val in_pipe     = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
  val out_pipe    = IO(Decoupled(new DBCheckerPipeMedium))
  val dbte_v_bm   = IO(Input(UInt(dbte_num.W)))
  val dbte_sram_if = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val dbte_refill_req_if = IO(Decoupled(new DBCheckerDBTEReq))
  val dbte_refill_rsp_if = IO(Flipped(Decoupled(new DBCheckerDBTERsp)))
  val perf = IO(Output(new DBCheckerPerfEvent))

  val fsm_state = RegInit(DBCheckerFetchState.RREQ)

  val pipe_v_reg      = RegInit(false.B)
  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
  }.elsewhen(out_pipe.fire) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  /*
    dbte fetch fsm
    dbte_fetch_req: init state
      if dbte_v_bm[index] == 0, go to refill_req state;
      if dbte_v_bm[index] == 1, send dbte_sram_if req and go to fetch_rsp state
    dbte_fetch_rsp: wait for dbte_sram_if rsp, 
      then compare inpipe.addr.dbte_index with Cat(dbte_sram_if addr, dbte_sram_if data.index_offset)
      if equal, output dbte to the next pipeline and go to init state
      else, go to the dbte_refill_req state to send refill req
    dbte_refill_req: send refill req to ctrl module, go to refill_rsp state
    dbte_refill_rsp: wait for refill rsp, then output dbte to the next pipeline and go to init state                            
  */

  val addr_ptr = pipe_medium_reg.axi_a.addr.asTypeOf(new DBCheckerPtr)

  val dbte_index = addr_ptr.get_index
  val dbte_index_hi = addr_ptr.get_index_hi

  // Transient Forwarding Logic (Security Safe)
  val last_refill_data  = Reg(UInt(128.W))
  val last_refill_index = Reg(UInt(16.W))
  val refill_hazard_cnt = RegInit(0.U(2.W)) 

  when(dbte_refill_rsp_if.fire) {
    refill_hazard_cnt := 3.U 
    last_refill_data  := dbte_refill_rsp_if.bits.dbte
    last_refill_index := dbte_index 
  }.elsewhen(refill_hazard_cnt > 0.U) {
    refill_hazard_cnt := refill_hazard_cnt - 1.U
  }

  dbte_sram_if.enable  := true.B
  dbte_sram_if.address := Mux(in_pipe.fire, in_pipe.bits, pipe_medium_reg).axi_a.addr.asTypeOf(new DBCheckerPtr).get_index_hi
  
  val sram_out = dbte_sram_if.data
  val use_forwarding = (refill_hazard_cnt > 0.U) && (dbte_index === last_refill_index)

  val cached_dbte = Mux(use_forwarding, last_refill_data, sram_out)
  val fetch_dbte_valid = dbte_v_bm(dbte_index_hi) && dbte_index === Cat(dbte_index_hi, cached_dbte.asTypeOf(new DBCheckerMtdt).index_offset)

  dbte_refill_req_if.bits.index := dbte_index

  dbte_refill_req_if.valid := false.B
  dbte_refill_rsp_if.ready := false.B

  switch(fsm_state) {
    is(DBCheckerFetchState.RREQ) {
      dbte_refill_req_if.valid := pipe_v_reg && !fetch_dbte_valid && !pipe_medium_reg.bypass
      when(dbte_refill_req_if.fire) {
        fsm_state := DBCheckerFetchState.RRSP
      }
    }
    is(DBCheckerFetchState.RRSP) {
      when(out_pipe.fire) {
        dbte_refill_rsp_if.ready := true.B
        fsm_state := DBCheckerFetchState.RREQ
      }
    }
  }

  in_pipe.ready := !pipe_v_reg || out_pipe.fire

  out_pipe.valid := pipe_v_reg &&  (fsm_state === DBCheckerFetchState.RREQ && (fetch_dbte_valid || pipe_medium_reg.bypass) || // use cached dbte or bypass
                                    fsm_state === DBCheckerFetchState.RRSP && dbte_refill_rsp_if.valid) // use refilled dbte
  out_pipe.bits := pipe_medium_reg
  out_pipe.bits.dbte := Mux(fsm_state === DBCheckerFetchState.RRSP, dbte_refill_rsp_if.bits.dbte, cached_dbte)

  val err_finv = !pipe_medium_reg.bypass && fsm_state === DBCheckerFetchState.RRSP && !dbte_refill_rsp_if.bits.dbte.asTypeOf(new DBCheckerMtdt).v
  val err_info = Wire(new DBCheckerErrInfo)
  err_info.err_mtdt_index := addr_ptr.get_index
  err_info.err_info       := 0.U // metadata invalid, no extra info

  when(!pipe_medium_reg.err_v && err_finv) {
    out_pipe.bits.err_v        := err_finv
    out_pipe.bits.err_req.typ  := err_mtdt_finv
    out_pipe.bits.err_req.addr := addr_ptr.asUInt
    out_pipe.bits.err_req.info := err_info.asUInt
  }

  // perf events
  perf.hit := fsm_state === DBCheckerFetchState.RREQ &&
              !pipe_medium_reg.bypass && out_pipe.fire

  val miss_pulse = dbte_refill_req_if.valid && !RegNext(dbte_refill_req_if.valid, false.B)
  perf.miss := miss_pulse

  val miss_inflight = RegInit(false.B)
  when(miss_pulse) {
    miss_inflight := true.B
  }.elsewhen(fsm_state === DBCheckerFetchState.RRSP && dbte_refill_rsp_if.valid) {
    miss_inflight := false.B
  }
  perf.penalty := miss_inflight

}

// stage2-4: backend
class DBCheckerPipeStage2 extends Module with DBCheckerConst { // check request bound
  val in_pipe      = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
  val out_pipe     = IO(Decoupled(new DBCheckerPipeMedium))

  val pipe_medium_reg    = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg         = RegInit(false.B)

  val check_bypass = WireInit(false.B)
  val err_v        = WireInit(false.B)
  val err_req      = WireInit(0.U.asTypeOf(new DBCheckerErrReq))

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
  }.elsewhen(out_pipe.fire) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  val addr_ptr = pipe_medium_reg.axi_a.addr.asTypeOf(new DBCheckerPtr)
  val bnd_lo   = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).bnd_lo
  val bnd_hi   = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).bnd_hi
  val r        = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).r.asBool
  val w        = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).w.asBool
  
  val burst_len_bytes = ((pipe_medium_reg.axi_a.len +& 1.U) << pipe_medium_reg.axi_a.size) - 1.U
  val bnd_err  = (addr_ptr.access_addr < bnd_lo) || 
                 ((addr_ptr.access_addr + burst_len_bytes) >= bnd_hi)
  val type_mismatch  = Mux(pipe_medium_reg.axi_a_type, !w, !r)
  val dev_err        = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).dev_id =/= pipe_medium_reg.axi_a.id(4)
  val access_err     = (bnd_err || type_mismatch || dev_err) && !pipe_medium_reg.bypass

  val err_info       = Wire(new DBCheckerErrInfo)
  err_info.err_mtdt_index := addr_ptr.get_index
  err_info.err_info       := Mux(bnd_err,!(addr_ptr.access_addr < bnd_lo), // 0: lo bound error, 1: hi bound error
                             Mux(type_mismatch,!w, //0: read type mismatch, 1: write type mismatch
                                  Cat(pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).dev_id.pad(8),pipe_medium_reg.axi_a.id.pad(8)))) // dev id info

  in_pipe.ready      := !pipe_v_reg || out_pipe.fire
  out_pipe.valid     := pipe_v_reg
  out_pipe.bits      := pipe_medium_reg
  when(!pipe_medium_reg.err_v && access_err) {
    out_pipe.bits.err_v        := access_err
    out_pipe.bits.err_req.typ  := Mux(bnd_err, err_bnd_farea, Mux(type_mismatch, err_bnd_ftype, err_wrong_dev))
    out_pipe.bits.err_req.addr := addr_ptr.asUInt
    out_pipe.bits.err_req.info := err_info.asUInt
  }
}

class DBCheckerPipeStage3 extends Module with DBCheckerConst { // divide pipe to handle R/W seperately, report error
  val in_pipe    = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
  val out_pipe_r = IO(Decoupled(new DBCheckerPipeMedium))
  val out_pipe_w = IO(Decoupled(new DBCheckerPipeMedium))

  val err_req_if = IO(Decoupled(new DBCheckerErrReq))
  val debug_dbte = IO(Output(new DBCheckerMtdt))

  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg      = RegInit(false.B)
  val err_sent        = RegInit(false.B)

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
  }.elsewhen(out_pipe_r.fire || out_pipe_w.fire) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  debug_dbte := pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt)

  err_req_if.valid := Mux(in_pipe.fire, in_pipe.bits.err_v, pipe_v_reg && pipe_medium_reg.err_v && !err_sent) // when bypass, it must be false
  err_req_if.bits  := Mux(in_pipe.fire, in_pipe.bits.err_req, pipe_medium_reg.err_req)

  when(err_req_if.fire) {
    err_sent := true.B
  }.elsewhen(in_pipe.fire || out_pipe_r.fire || out_pipe_w.fire) {
    err_sent := false.B
  }

  val allow_pass = err_sent || pipe_medium_reg.bypass

  out_pipe_w.bits  := pipe_medium_reg
  out_pipe_r.bits  := pipe_medium_reg

  when(pipe_medium_reg.axi_a_type) { // write
    out_pipe_r.valid := false.B
    out_pipe_w.valid := pipe_v_reg && (!pipe_medium_reg.err_v || allow_pass)
    out_pipe_w.bits.err_v := pipe_medium_reg.err_v 
    in_pipe.ready    := !pipe_v_reg || out_pipe_w.fire

  }.otherwise {
    out_pipe_r.valid := pipe_v_reg && (!pipe_medium_reg.err_v || allow_pass)
    out_pipe_r.bits.err_v := pipe_medium_reg.err_v
    out_pipe_w.valid := false.B
    in_pipe.ready    := !pipe_v_reg || out_pipe_r.fire
  }
}

class DBCheckerPipeStage4R extends Module with DBCheckerConst { // Return_R
  val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

  val s_r_chan  = IO(Decoupled(new AxiReadData(128, idWidth = 5)))
  val m_ar_chan = IO(Decoupled(new AxiAddr(64, idWidth = 5)))
  val m_r_chan  = IO(Flipped(Decoupled(new AxiReadData(128, idWidth = 5))))

  // Auto-Release CAM/counter interface
  val rd_active_index   = IO(Output(UInt(16.W)))
  val rd_ar_fire        = IO(Output(Bool()))
  val rd_should_track   = IO(Output(Bool()))
  val rd_target         = IO(Output(UInt(48.W)))
  val rd_beat_bytes     = IO(Output(UInt(8.W)))
  val rd_beat_fire      = IO(Output(Bool()))
  val rd_slot_id        = IO(Input(UInt(7.W)))
  val rd_slot_valid     = IO(Input(Bool()))
  val rd_auto_clear     = IO(Input(Bool()))
  val rd_active_slot    = IO(Output(UInt(7.W)))
  val rd_slot_valid_out = IO(Output(Bool()))
  val auto_clear_req    = IO(Decoupled(new AutoClearReq))

  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg      = RegInit(false.B)
  val ar_release_reg  = RegInit(false.B)
  val transfer_done   = WireInit(false.B)
  val beat_cnt        = Reg(UInt(8.W))

  m_ar_chan.valid := false.B

  m_ar_chan.bits      := pipe_medium_reg.axi_a
  // m_ar_chan.bits.addr := pipe_medium_reg.axi_a.addr
  m_ar_chan.bits.addr := Cat(pipe_medium_reg.err_v, 0.U(15.W), pipe_medium_reg.axi_a.addr(47,0))
  s_r_chan.bits      := m_r_chan.bits
  s_r_chan.valid     := m_r_chan.valid
  m_r_chan.ready     := s_r_chan.ready

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    ar_release_reg  := true.B
    pipe_medium_reg := in_pipe.bits
    beat_cnt        := in_pipe.bits.axi_a.len
  }.elsewhen(transfer_done) {
    pipe_v_reg      := false.B
    ar_release_reg  := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  m_ar_chan.valid     := ar_release_reg

  when(m_ar_chan.fire) {
    transfer_done := true.B
  }

  in_pipe.ready := !pipe_v_reg || transfer_done

  // --- Auto-Release: active read latch ---
  val active_rd_dbte_index = Reg(UInt(16.W))
  val active_rd_valid      = RegInit(false.B)
  val active_rd_should_track = Reg(Bool())
  val active_rd_target     = Reg(UInt(48.W))
  val active_rd_beat_bytes = Reg(UInt(8.W))
  val active_rd_slot       = Reg(UInt(7.W))
  val active_rd_slot_valid = RegInit(false.B)

  val dbte_mtdt = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt)
  val ar_fire   = m_ar_chan.fire
  val ar_should_track = !pipe_medium_reg.bypass &&
                        !pipe_medium_reg.err_v &&
                        dbte_mtdt.auto_rel_en

  when(ar_fire) {
    active_rd_dbte_index   := pipe_medium_reg.axi_a.addr(63, 48)
    active_rd_valid        := true.B
    active_rd_should_track := ar_should_track
    active_rd_target       := dbte_mtdt.bnd_hi - dbte_mtdt.bnd_lo
    active_rd_beat_bytes   := (1.U << pipe_medium_reg.axi_a.size)(7, 0)
    active_rd_slot         := rd_slot_id
  }

  when(rd_slot_valid) {
    active_rd_slot_valid := true.B
  }

  // Cleared on last R beat (rlast && m_r_chan.fire)
  val r_beat_fire = m_r_chan.valid && m_r_chan.ready
  when(r_beat_fire && m_r_chan.bits.last) {
    active_rd_valid      := false.B
    active_rd_slot_valid := false.B
  }

  // Connect active state to pipeline body
  rd_active_index   := pipe_medium_reg.axi_a.addr(63, 48)
  rd_ar_fire        := ar_fire
  rd_should_track   := ar_should_track
  rd_target         := active_rd_target
  rd_beat_bytes     := active_rd_beat_bytes
  rd_beat_fire      := r_beat_fire && active_rd_valid && active_rd_should_track
  rd_active_slot    := active_rd_slot
  rd_slot_valid_out := active_rd_slot_valid

  // Auto-clear request to pipeline (wired in commit 6)
  auto_clear_req.valid := false.B
  auto_clear_req.bits  := 0.U.asTypeOf(new AutoClearReq)
}

class DBCheckerPipeStage4W extends Module with DBCheckerConst { // Return_W
  val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

  val s_w_chan  = IO(Flipped(Decoupled(new AxiWriteData(128))))
  val s_b_chan  = IO(Decoupled(new AxiWriteResp(idWidth = 5)))
  val m_aw_chan = IO(Decoupled(new AxiAddr(64, idWidth = 5)))
  val m_w_chan  = IO(Decoupled(new AxiWriteData(128)))
  val m_b_chan  = IO(Flipped(Decoupled(new AxiWriteResp(idWidth = 5))))

  // Auto-Release CAM/counter interface
  val wr_active_index = IO(Output(UInt(16.W)))
  val wr_target       = IO(Output(UInt(48.W)))
  val wr_beat_bytes   = IO(Output(UInt(8.W)))
  val wr_beat_fire    = IO(Output(Bool()))
  val wr_slot_id      = IO(Input(UInt(7.W)))
  val wr_slot_valid   = IO(Output(Bool()))
  val wr_auto_clear   = IO(Input(Bool()))
  val wr_active_slot  = IO(Output(UInt(7.W)))
  val auto_clear_req  = IO(Decoupled(new AutoClearReq))

  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg      = RegInit(false.B)
  val aw_release_reg  = RegInit(false.B)
  val transfer_done   = WireInit(false.B)

  m_aw_chan.valid := false.B

  m_aw_chan.bits      := pipe_medium_reg.axi_a
  m_aw_chan.bits.addr := Cat(pipe_medium_reg.err_v, 0.U(15.W), pipe_medium_reg.axi_a.addr(47,0))

  m_w_chan.bits := s_w_chan.bits
  s_b_chan.bits := m_b_chan.bits

  m_w_chan.valid := s_w_chan.valid
  s_w_chan.ready := m_w_chan.ready
  s_b_chan.valid := m_b_chan.valid
  m_b_chan.ready := s_b_chan.ready

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
    aw_release_reg  := true.B
  }.elsewhen(transfer_done) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    aw_release_reg  := false.B
  }

  m_aw_chan.valid     := aw_release_reg

  when(m_aw_chan.fire) {
    transfer_done := true.B
  }

  in_pipe.ready := !pipe_v_reg || transfer_done

  // --- Auto-Release: active write latch ---
  val active_wr_dbte_index = Reg(UInt(16.W))
  val active_wr_valid      = RegInit(false.B)
  val active_wr_should_track = Reg(Bool())
  val active_wr_target     = Reg(UInt(48.W))
  val active_wr_beat_bytes = Reg(UInt(8.W))
  val active_wr_slot       = Reg(UInt(7.W))
  val active_wr_slot_valid = RegInit(false.B)

  val dbte_mtdt = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt)
  val aw_fire   = m_aw_chan.fire
  val aw_should_track = !pipe_medium_reg.bypass &&
                        !pipe_medium_reg.err_v &&
                        dbte_mtdt.auto_rel_en

  when(aw_fire) {
    active_wr_dbte_index   := pipe_medium_reg.axi_a.addr(63, 48)
    active_wr_valid        := true.B
    active_wr_should_track := aw_should_track
    active_wr_target       := dbte_mtdt.bnd_hi - dbte_mtdt.bnd_lo
    active_wr_beat_bytes   := (1.U << pipe_medium_reg.axi_a.size)(7, 0)
    active_wr_slot         := wr_slot_id
    active_wr_slot_valid   := aw_should_track
  }

  when(m_b_chan.fire) {
    active_wr_valid      := false.B
    active_wr_slot_valid := false.B
  }

  // Tap W passthrough: observe beats without breaking passthrough
  val w_beat_fire = m_w_chan.valid && m_w_chan.ready

  wr_active_index := pipe_medium_reg.axi_a.addr(63, 48)
  wr_slot_valid   := aw_fire && aw_should_track
  wr_target       := active_wr_target
  wr_beat_bytes   := active_wr_beat_bytes
  wr_beat_fire    := w_beat_fire && active_wr_valid && active_wr_should_track
  wr_active_slot  := active_wr_slot

  // Auto-clear request to pipeline (asserted for 1 cycle on match)
  val auto_clear_pending = RegInit(false.B)

  when(active_wr_slot_valid && wr_auto_clear && !auto_clear_pending) {
    auto_clear_pending := true.B
  }

  auto_clear_req.valid := auto_clear_pending
  auto_clear_req.bits.index        := active_wr_dbte_index
  auto_clear_req.bits.index_offset := active_wr_dbte_index(3, 0)

  when(auto_clear_req.fire) {
    auto_clear_pending := false.B
  }
}

class DBCheckerPipeline extends Module with DBCheckerConst {
  val m_axi_io_rx  = IO(new AxiMaster(64, 128, idWidth = 5))
  val s_axi_io_rx  = IO(new AxiSlave(64, 128, idWidth = 5))
  val ctrl_reg     = IO(Input(Vec(RegNum, UInt(32.W))))
  val dbte_v_bm    = IO(Input(UInt(dbte_num.W)))
  val err_req_r    = IO(Decoupled(new DBCheckerErrReq))
  val err_req_w    = IO(Decoupled(new DBCheckerErrReq))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val refill_dbte_req_if = IO(Decoupled(new DBCheckerDBTEReq))
  val refill_dbte_rsp_if = IO(Flipped(Decoupled(new DBCheckerDBTERsp)))
  val debug_if     = IO(Output(UInt(128.W)))
  val perf         = IO(Output(new DBCheckerPerfEvent))

  // CAM interface (passthrough to/from DBCheckerCtrl)
  val cam_lookup_key   = IO(Output(UInt(16.W)))
  val cam_lookup_valid = IO(Input(Bool()))
  val cam_lookup_id    = IO(Input(UInt(7.W)))
  val cam_insert_key   = IO(Output(UInt(16.W)))
  val cam_insert_valid = IO(Output(Bool()))
  val cam_insert_id    = IO(Input(UInt(7.W)))
  val cam_remove_key   = IO(Output(UInt(16.W)))
  val cam_remove_valid = IO(Output(Bool()))
  val cam_counter_slot   = IO(Output(UInt(7.W)))
  val cam_counter_bytes  = IO(Output(UInt(8.W)))
  val cam_counter_init_target = IO(Output(UInt(48.W)))
  val cam_counter_update = IO(Output(Bool()))
  val cam_auto_clear     = IO(Input(Bool()))
  val cam_used_slots     = IO(Input(UInt(8.W)))
  val cam_full           = IO(Input(Bool()))

  // Auto-clear request to Ctrl FSM
  val auto_clear_req = IO(Decoupled(new AutoClearReq))

  err_req_w <> DontCare
//frontend
  val stage0  = Module(new DBCheckerPipeStage0)
  val stage1  = Module(new DBCheckerPipeStage1)
//backend
  val stage2  = Module(new DBCheckerPipeStage2)
  val stage3  = Module(new DBCheckerPipeStage3)
  val stage4r = Module(new DBCheckerPipeStage4R)
  val stage4w = Module(new DBCheckerPipeStage4W)

  stage0.in_ar <> s_axi_io_rx.ar
  stage0.in_aw <> s_axi_io_rx.aw
  stage0.ctrl_en := ctrl_reg(chk_en).asTypeOf(new DBCheckerEnCtl)

  stage1.in_pipe <> stage0.out_pipe
  stage1.dbte_v_bm := dbte_v_bm
  stage1.dbte_sram_if <> dbte_sram_r
  stage1.dbte_refill_req_if <> refill_dbte_req_if
  stage1.dbte_refill_rsp_if <> refill_dbte_rsp_if

  stage2.in_pipe <> stage1.out_pipe

  stage3.in_pipe <> stage2.out_pipe
  stage3.err_req_if <> err_req_r
  
  stage4r.in_pipe <> stage3.out_pipe_r
  stage4r.s_r_chan <> s_axi_io_rx.r
  stage4r.m_ar_chan <> m_axi_io_rx.ar
  stage4r.m_r_chan <> m_axi_io_rx.r

  stage4w.in_pipe <> stage3.out_pipe_w
  stage4w.s_w_chan <> s_axi_io_rx.w
  stage4w.s_b_chan <> s_axi_io_rx.b
  stage4w.m_aw_chan <> m_axi_io_rx.aw
  stage4w.m_w_chan <> m_axi_io_rx.w
  stage4w.m_b_chan <> m_axi_io_rx.b

  // --- CAM access muxing between Stage4W and Stage4R ---
  // W channel has priority for CAM/counter access

  // CAM lookup/insert on AW/AR fire (W priority)
  val cam_lookup_sel = Wire(UInt(16.W))
  val cam_insert_sel = Wire(UInt(16.W))
  val do_cam_insert   = Wire(Bool())

  when(stage4w.wr_slot_valid) {
    cam_lookup_sel := stage4w.wr_active_index
    cam_insert_sel := stage4w.wr_active_index
    do_cam_insert  := !cam_lookup_valid
  }.elsewhen(stage4r.rd_ar_fire && stage4r.rd_should_track) {
    cam_lookup_sel := stage4r.rd_active_index
    cam_insert_sel := stage4r.rd_active_index
    do_cam_insert  := !cam_lookup_valid
  }.otherwise {
    cam_lookup_sel := 0.U
    cam_insert_sel := 0.U
    do_cam_insert  := false.B
  }

  cam_lookup_key   := cam_lookup_sel
  cam_insert_key   := cam_insert_sel
  cam_insert_valid := do_cam_insert
  cam_remove_key   := 0.U
  cam_remove_valid := false.B

  // Drive slot allocation back to stages
  stage4w.wr_slot_id    := Mux(cam_lookup_valid, cam_lookup_id, cam_insert_id)
  stage4r.rd_slot_id    := Mux(cam_lookup_valid, cam_lookup_id, cam_insert_id)
  stage4r.rd_slot_valid := stage4r.rd_ar_fire && stage4r.rd_should_track && !stage4w.wr_slot_valid

  // Counter update on beat fire (W priority)
  cam_counter_slot   := 0.U
  cam_counter_bytes  := 0.U
  cam_counter_init_target := 0.U
  cam_counter_update := false.B
  when(stage4w.wr_beat_fire) {
    cam_counter_slot   := stage4w.wr_active_slot
    cam_counter_bytes  := stage4w.wr_beat_bytes
    cam_counter_init_target := stage4w.wr_target
    cam_counter_update := true.B
  }.elsewhen(stage4r.rd_beat_fire) {
    cam_counter_slot   := stage4r.rd_active_slot
    cam_counter_bytes  := stage4r.rd_beat_bytes
    cam_counter_init_target := stage4r.rd_target
    cam_counter_update := true.B
  }

  // Auto-clear feedback to stages (registered, no beat_fire qualification needed)
  stage4w.wr_auto_clear := cam_auto_clear
  stage4r.rd_auto_clear := cam_auto_clear && !stage4w.wr_beat_fire

  // auto_clear_req from Stage4W to ctrl
  auto_clear_req <> stage4w.auto_clear_req
  // Stage4R auto_clear_req not used yet (commit 5)
  stage4r.auto_clear_req.ready := false.B

  debug_if := stage3.debug_dbte.asUInt
  perf     := stage1.perf
}
