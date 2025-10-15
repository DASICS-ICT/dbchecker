package DBChecker

import chisel3._
import chisel3.util._
import axi._

// stage0-1: frontend
class DBCheckerPipeStage0 extends Module with DBCheckerConst { // receive AXI request
  val in_ar       = IO(Flipped(Decoupled(new AxiAddr(64))))
  val in_aw       = IO(Flipped(Decoupled(new AxiAddr(64))))
  val out_pipe    = IO(Decoupled(new DBCheckerPipeMedium))
  val ctrl_en     = IO(Input(new DBCheckerEnCtl))

  val pipe_v_reg    = RegInit(false.B)
  val rw_reg        = RegInit(false.B) // 0: R, 1: W
  val pipe_addr_reg = RegInit(0.U.asTypeOf(new AxiAddr(64)))

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
    pipe_addr_reg := 0.U.asTypeOf(new AxiAddr(64))
    rw_reg        := false.B
  }

  in_ar.ready := !pipe_v_reg || out_pipe.fire
  in_aw.ready := (!pipe_v_reg || out_pipe.fire) && !in_ar.valid

  out_pipe.valid           := pipe_v_reg
  out_pipe.bits.axi_a      := pipe_addr_reg
  out_pipe.bits.axi_a_type := rw_reg
  out_pipe.bits.dbte    := 0.U.asTypeOf(UInt(128.W))
  out_pipe.bits.bypass     := !ctrl_en.func_en
  out_pipe.bits.err_v      := false.B
  out_pipe.bits.err_req    := 0.U.asTypeOf(new DBCheckerErrReq)
}

class DBCheckerPipeStage1 extends Module with DBCheckerConst { // readDBTE

  val in_pipe     = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
  val out_pipe    = IO(Decoupled(new DBCheckerPipeMedium))
  val dbte_v_bm   = IO(Input(UInt(dbte_num.W)))
  val dbte_mem_if = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))

  val pipe_v_reg      = RegInit(false.B)
  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
  }.elsewhen(out_pipe.fire) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  val target_addr = Mux(in_pipe.fire, in_pipe.bits.axi_a.addr, pipe_medium_reg.axi_a.addr)
  val dbte_index  = target_addr.asTypeOf(new DBCheckerPtr).get_index
  val err_finv    = !dbte_v_bm(dbte_index) && !pipe_medium_reg.bypass

  dbte_mem_if.address := dbte_index
  dbte_mem_if.enable  := true.B

  in_pipe.ready         := !pipe_v_reg || out_pipe.fire
  out_pipe.valid        := pipe_v_reg
  out_pipe.bits         := pipe_medium_reg
  out_pipe.bits.dbte := dbte_mem_if.data
  when(!pipe_medium_reg.err_v && err_finv) {
    out_pipe.bits.err_v        := err_finv
    out_pipe.bits.err_req.typ  := err_mtdt_finv
    out_pipe.bits.err_req.info := target_addr
    out_pipe.bits.err_req.mtdt := 0.U
  }
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
  val lo_bnd   = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).lo_bnd
  val up_bnd   = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).up_bnd
  val r        = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).r.asBool
  val w        = pipe_medium_reg.dbte.asTypeOf(new DBCheckerMtdt).w.asBool
  
  val bnd_err  = (addr_ptr.access_addr < lo_bnd) || 
                 ((addr_ptr.access_addr + pipe_medium_reg.axi_a.len) > up_bnd)
  val type_mismatch  = Mux(pipe_medium_reg.axi_a_type, !w, !r)
  val access_err     = (bnd_err || type_mismatch) && !pipe_medium_reg.bypass

  in_pipe.ready      := !pipe_v_reg || out_pipe.fire
  out_pipe.valid     := pipe_v_reg
  out_pipe.bits      := pipe_medium_reg
  when(!pipe_medium_reg.err_v && access_err) {
    out_pipe.bits.err_v        := access_err
    out_pipe.bits.err_req.typ  := Mux(bnd_err, err_bnd_farea, err_bnd_ftype)
    out_pipe.bits.err_req.info := in_pipe.bits.axi_a.addr
    out_pipe.bits.err_req.mtdt := pipe_medium_reg.dbte
  }
}

class DBCheckerPipeStage3 extends Module with DBCheckerConst { // divide pipe to handle R/W seperately, report error
  val in_pipe    = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
  val out_pipe_r = IO(Decoupled(new DBCheckerPipeMedium))
  val out_pipe_w = IO(Decoupled(new DBCheckerPipeMedium))

  val err_req_if = IO(Decoupled(new DBCheckerErrReq))
  val ctrl_en = IO(Input(new DBCheckerEnCtl))
  val intr_out = IO(Output(Bool()))

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

  val intr_state = RegInit(false.B)
  when(err_req_if.fire && ctrl_en.intr_en) {
    intr_state := true.B
  }.elsewhen(ctrl_en.intr_clr) {
    intr_state := false.B
  }
  intr_out := intr_state

  val stall_err_byp = RegInit(false.B)
  val stall_err_rpt = RegInit(false.B)
  when(ctrl_en.stall_mode && (ctrl_en.err_byp || ctrl_en.err_rpt)) {
    stall_err_byp := ctrl_en.err_byp
    stall_err_rpt := ctrl_en.err_rpt
  }.elsewhen(in_pipe.fire || out_pipe_r.fire || out_pipe_w.fire) {
    stall_err_byp := false.B
    stall_err_rpt := false.B
  }

  val allow_pass = (err_sent && (!ctrl_en.stall_mode || stall_err_byp || stall_err_rpt)) || pipe_medium_reg.bypass

  when(pipe_medium_reg.axi_a_type) { // write
    out_pipe_w.valid := pipe_v_reg && !intr_state && (!pipe_medium_reg.err_v || allow_pass)
    out_pipe_w.bits  := pipe_medium_reg
    out_pipe_w.bits.err_v := pipe_medium_reg.err_v && !stall_err_byp
    in_pipe.ready    := !pipe_v_reg || out_pipe_w.fire
    out_pipe_r.valid := false.B
    out_pipe_r.bits  := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }.otherwise {
    out_pipe_r.valid := pipe_v_reg && !intr_state && (!pipe_medium_reg.err_v || allow_pass)
    out_pipe_r.bits  := pipe_medium_reg
    out_pipe_r.bits.err_v := pipe_medium_reg.err_v && !stall_err_byp
    in_pipe.ready    := !pipe_v_reg || out_pipe_r.fire
    out_pipe_w.valid := false.B
    out_pipe_w.bits  := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }
}

class DBCheckerPipeStage4R extends Module with DBCheckerConst { // Return_R
  val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

  val s_r_chan  = IO(Decoupled(new AxiReadData(128)))
  val m_ar_chan = IO(Decoupled(new AxiAddr(32)))
  val m_r_chan  = IO(Flipped(Decoupled(new AxiReadData(128))))

  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg      = RegInit(false.B)
  val ar_release_reg  = RegInit(false.B)
  val transfer_done   = WireInit(false.B)

  m_ar_chan.valid := false.B
  m_ar_chan.bits  := 0.U.asTypeOf(new AxiAddr(32))
  m_r_chan.ready  := false.B
  s_r_chan.valid  := false.B
  s_r_chan.bits   := 0.U.asTypeOf(new AxiReadData(128))

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    ar_release_reg  := true.B
    pipe_medium_reg := in_pipe.bits
  }.elsewhen(transfer_done) {
    pipe_v_reg      := false.B
    ar_release_reg  := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
  }

  when(pipe_medium_reg.err_v) {
    // TODO: let software decide to pass / to slverr
    // maybe switch mode to accelerate? add intr mode?
    s_r_chan.valid     := true.B
    s_r_chan.bits.data := 0.U
    s_r_chan.bits.resp := 2.U // SLVERR
    s_r_chan.bits.last := true.B
  }.otherwise {
    m_ar_chan.valid     := ar_release_reg
    m_ar_chan.bits      := pipe_medium_reg.axi_a
    m_ar_chan.bits.addr := pipe_medium_reg.axi_a.addr(31, 0)
    s_r_chan.valid      := m_r_chan.valid
    s_r_chan.bits       := m_r_chan.bits
    m_r_chan.ready      := s_r_chan.ready
  }
  when(m_ar_chan.fire) {
    ar_release_reg := false.B
  }
  when(s_r_chan.fire && s_r_chan.bits.last) {
    transfer_done := true.B
  }
  in_pipe.ready := !pipe_v_reg || transfer_done
}

class DBCheckerPipeStage4W extends Module with DBCheckerConst { // Return_W
  val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

  val s_w_chan  = IO(Flipped(Decoupled(new AxiWriteData(128))))
  val s_b_chan  = IO(Decoupled(new AxiWriteResp()))
  val m_aw_chan = IO(Decoupled(new AxiAddr(32)))
  val m_w_chan  = IO(Decoupled(new AxiWriteData(128)))
  val m_b_chan  = IO(Flipped(Decoupled(new AxiWriteResp())))

  val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
  val pipe_v_reg      = RegInit(false.B)
  val aw_release_reg  = RegInit(false.B)
  val w_release_reg   = RegInit(false.B)
  val transfer_done   = WireInit(false.B)

  m_aw_chan.valid := false.B
  m_aw_chan.bits  := 0.U.asTypeOf(new AxiAddr(32))

  s_w_chan.ready := false.B
  m_w_chan.valid := false.B
  m_w_chan.bits  := 0.U.asTypeOf(new AxiWriteData(128))

  m_b_chan.ready := false.B
  s_b_chan.valid := false.B
  s_b_chan.bits  := 0.U.asTypeOf(new AxiWriteResp)

  when(in_pipe.fire) {
    pipe_v_reg      := true.B
    pipe_medium_reg := in_pipe.bits
    aw_release_reg  := true.B
    w_release_reg   := true.B
  }.elsewhen(transfer_done) {
    pipe_v_reg      := false.B
    pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    aw_release_reg  := false.B
    w_release_reg   := false.B
  }

  when(pipe_medium_reg.err_v) {
    // TODO: let software decide to pass / to slverr
    s_w_chan.ready     := w_release_reg
    s_b_chan.valid     := !w_release_reg
    s_b_chan.bits.resp := 2.U // SLVERR
  }.otherwise {
    m_aw_chan.valid     := aw_release_reg
    m_aw_chan.bits      := pipe_medium_reg.axi_a
    m_aw_chan.bits.addr := pipe_medium_reg.axi_a.addr(31, 0)
    m_w_chan.valid      := s_w_chan.valid & w_release_reg
    m_w_chan.bits       := s_w_chan.bits
    s_w_chan.ready      := m_w_chan.ready & w_release_reg
    s_b_chan.valid      := m_b_chan.valid & !w_release_reg
    s_b_chan.bits       := m_b_chan.bits
    m_b_chan.ready      := s_b_chan.ready & !w_release_reg
  }
  when(m_aw_chan.fire) {
    aw_release_reg := false.B
  }
  when(s_w_chan.fire && s_w_chan.bits.last) {
    w_release_reg := false.B
  }
  when(s_b_chan.fire) {
    transfer_done := true.B
  }

  in_pipe.ready := !pipe_v_reg || transfer_done
}
class DBCheckerPipeline extends Module with DBCheckerConst {
  val m_axi_io_rx  = IO(new AxiMaster(32, 128))
  val s_axi_io_rx  = IO(new AxiSlave(64, 128))
  val ctrl_reg     = IO(Input(Vec(RegNum, UInt(64.W))))
  val dbte_v_bm    = IO(Input(UInt(dbte_num.W)))
  val err_req_r    = IO(Decoupled(new DBCheckerErrReq))
  val err_req_w    = IO(Decoupled(new DBCheckerErrReq))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val debug_if     = IO(Output(UInt(64.W)))
  val intr_state   = IO(Output(Bool()))

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
  stage1.dbte_mem_if <> dbte_sram_r

  stage2.in_pipe <> stage1.out_pipe

  stage3.in_pipe <> stage2.out_pipe
  stage3.err_req_if <> err_req_r
  stage3.ctrl_en := ctrl_reg(chk_en).asTypeOf(new DBCheckerEnCtl)
  intr_state := stage3.intr_out
  
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

  debug_if := stage3.debug_dbte.asUInt
}
