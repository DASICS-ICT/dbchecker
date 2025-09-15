package DBChecker

import chisel3._
import chisel3.util._
import axi._

// stage0-2: frontend
class DBCheckerPipeStage0 extends Module with DBCheckerConst{ // receive AXI request
    val in_ar = IO(Flipped(Decoupled(new AxiAddr(64))))
    val in_aw = IO(Flipped(Decoupled(new AxiAddr(64))))
    val out_pipe = IO(Decoupled(new DBCheckerPipeMedium))
    val ctrl_chk_en = IO(Input(Bool()))

    val pipe_v_reg = RegInit(false.B)
    val rw_reg = RegInit(false.B) // 0: R, 1: W
    val pipe_addr_reg = RegInit(0.U.asTypeOf(new AxiAddr(64)))

// handle logic
    when (in_ar.fire) {
        pipe_v_reg := true.B
        pipe_addr_reg := in_ar.bits
        rw_reg := false.B
    }.elsewhen(in_aw.fire) {
        pipe_v_reg := true.B
        pipe_addr_reg := in_aw.bits
        rw_reg := true.B
    }.elsewhen (out_pipe.fire) {
        pipe_v_reg := false.B
        pipe_addr_reg := 0.U.asTypeOf(new AxiAddr(64))
        rw_reg := false.B
    }

    in_ar.ready := !pipe_v_reg || out_pipe.fire
    in_aw.ready := (!pipe_v_reg || out_pipe.fire) && !in_ar.valid

    out_pipe.valid := pipe_v_reg
    out_pipe.bits.axi_a := pipe_addr_reg
    out_pipe.bits.axi_a_type := rw_reg
    out_pipe.bits.dbte_lo := 0.U.asTypeOf(UInt(32.W))
    out_pipe.bits.bypass := !ctrl_chk_en
    out_pipe.bits.err_v := false.B
    out_pipe.bits.err_req := 0.U.asTypeOf(new DBCheckerErrReq)
}
class DBCheckerPipeStage1 extends Module with DBCheckerConst{ // readDBTE

    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
    val out_pipe = IO(Decoupled(new DBCheckerPipeMedium))
    val dbte_v_bm = IO(Input(UInt(dbte_num.W)))
    val dbte_mem_if = IO(Flipped(new MemoryReadPort(UInt(32.W), log2Up(dbte_num))))

    val pipe_v_reg = RegInit(false.B)
    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        pipe_medium_reg := in_pipe.bits
    }.elsewhen (out_pipe.fire) {
        pipe_v_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }

    val target_addr = Mux(in_pipe.fire, in_pipe.bits.axi_a.addr, pipe_medium_reg.axi_a.addr)
    val dbte_index = target_addr.asTypeOf(new DBCheckerPtr).get_index
    val err_finv = !dbte_v_bm(dbte_index) && !pipe_medium_reg.bypass

    dbte_mem_if.address := dbte_index
    dbte_mem_if.enable := true.B

    in_pipe.ready := !pipe_v_reg || out_pipe.fire
    out_pipe.valid := pipe_v_reg
    out_pipe.bits := pipe_medium_reg
    out_pipe.bits.dbte_lo := dbte_mem_if.data
    when (!pipe_medium_reg.err_v && err_finv) {
        out_pipe.bits.err_v := err_finv
        out_pipe.bits.err_req.typ := err_mtdt_finv
        out_pipe.bits.err_req.info := target_addr
        out_pipe.bits.err_req.mtdt := 0.U
    }
}

class DBCheckerPipeStage2 extends Module with DBCheckerConst{ // WaitCheckreq
    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
    val out_pipe = IO(Decoupled(new DBCheckerPipeMedium))
    val decrypt_req = IO(Decoupled(new QarmaInputBundle))
    val decrypt_key = IO(Input(UInt(128.W)))

    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
    val pipe_v_reg = RegInit(false.B)
    val decrypt_sent = RegInit(false.B)

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        pipe_medium_reg := in_pipe.bits
    }.elsewhen (out_pipe.fire) {
        pipe_v_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }

    when (decrypt_req.fire){
        decrypt_sent := true.B
    }.elsewhen (in_pipe.fire || out_pipe.fire) {
        decrypt_sent := false.B
    }

    decrypt_req.valid := Mux(in_pipe.fire, true.B, pipe_v_reg && !decrypt_sent)
    decrypt_req.bits.text := Mux(in_pipe.fire, 
                                 Cat(in_pipe.bits.axi_a.addr.asTypeOf(new DBCheckerPtr).e_mtdt_hi, in_pipe.bits.dbte_lo), 
                                 Cat(pipe_medium_reg.axi_a.addr.asTypeOf(new DBCheckerPtr).e_mtdt_hi, pipe_medium_reg.dbte_lo))
    decrypt_req.bits.encrypt := 0.U
    decrypt_req.bits.tweak := "hDEADBEEFDEADBEEF".U
    decrypt_req.bits.actual_round := 3.U
    decrypt_req.bits.keyh := decrypt_key(127,64)
    decrypt_req.bits.keyl := decrypt_key(63,0)

    in_pipe.ready := !pipe_v_reg || out_pipe.fire
    out_pipe.valid := pipe_v_reg && decrypt_sent
    out_pipe.bits := pipe_medium_reg
}

// Ringbuffer

// stage3-5: backend
class DBCheckerPipeStage3 extends Module with DBCheckerConst{ // WaitCheckResp
    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
    val out_pipe = IO(Decoupled(new DBCheckerPipeMedium))
    val decrypt_resp = IO(Flipped(Decoupled(new QarmaOutputBundle)))

    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
    val decrypt_medium_reg = RegInit(0.U.asTypeOf(new QarmaOutputBundle))
    val pipe_v_reg = RegInit(false.B)
    val decrypt_v_reg = RegInit(false.B)

    val check_bypass = WireInit(false.B)
    val err_v = WireInit(false.B)
    val err_req = WireInit(0.U.asTypeOf(new DBCheckerErrReq))

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        pipe_medium_reg := in_pipe.bits
    }.elsewhen (out_pipe.fire) {
        pipe_v_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }

    when (decrypt_resp.fire) {
        decrypt_v_reg := true.B
        decrypt_medium_reg := decrypt_resp.bits
    }.elsewhen (out_pipe.fire) {
        decrypt_v_reg := false.B
        decrypt_medium_reg := 0.U.asTypeOf(new QarmaOutputBundle)
    }
    val addr_ptr = pipe_medium_reg.axi_a.addr.asTypeOf(new DBCheckerPtr)

    // receive decrypt response
    val decrypt_result = decrypt_medium_reg.result.asTypeOf(new DBCheckerMtdt)
    val magic_num_err = (decrypt_result.mn =/= magic_num.U)
    val bnd_base   = Mux(decrypt_result.typ.asBool, 
                        Cat(decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_base, 0.U((32 - (new DBCheckerBndL).limit_base.getWidth).W)), 
                        decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_base)
    val bnd_offset = Mux(decrypt_result.typ.asBool, 
                        decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_offset, 
                        decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_offset)
    val bnd_limit_err = (addr_ptr.access_addr < bnd_base) || ((addr_ptr.access_addr + pipe_medium_reg.axi_a.len) > (bnd_base + bnd_offset))
    val bnd_mismatch = Mux(pipe_medium_reg.axi_a_type, !decrypt_result.w.asBool, !decrypt_result.r.asBool)
    val bnd_err = (magic_num_err || bnd_limit_err || bnd_mismatch) && !pipe_medium_reg.bypass

    in_pipe.ready := !pipe_v_reg || out_pipe.fire
    decrypt_resp.ready := !decrypt_v_reg || out_pipe.fire
    out_pipe.valid := pipe_v_reg && decrypt_v_reg
    out_pipe.bits := pipe_medium_reg
    when (!pipe_medium_reg.err_v && bnd_err) {
        out_pipe.bits.err_v := bnd_err
        out_pipe.bits.err_req.typ := Mux(magic_num_err, err_mtdt_fmn, Mux(bnd_limit_err, err_bnd_farea, err_bnd_ftype))
        out_pipe.bits.err_req.info := in_pipe.bits.axi_a.addr
        out_pipe.bits.err_req.mtdt := decrypt_medium_reg.result
    }
}

class DBCheckerPipeStage4 extends Module with DBCheckerConst { // divide pipe to handle R/W seperately, report error
    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
    val out_pipe_r = IO(Decoupled(new DBCheckerPipeMedium))
    val out_pipe_w = IO(Decoupled(new DBCheckerPipeMedium))

    val err_req_if = IO(Decoupled(new DBCheckerErrReq))

    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
    val pipe_v_reg = RegInit(false.B)
    val err_sent = RegInit(false.B)

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        pipe_medium_reg := in_pipe.bits
    }.elsewhen (out_pipe_r.fire || out_pipe_w.fire) {
        pipe_v_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }

    err_req_if.valid := Mux(in_pipe.fire,in_pipe.bits.err_v,pipe_v_reg && pipe_medium_reg.err_v && !err_sent)
    err_req_if.bits  := Mux(in_pipe.fire, in_pipe.bits.err_req, pipe_medium_reg.err_req)

    when (err_req_if.fire){
        err_sent := true.B
    }.elsewhen (in_pipe.fire || out_pipe_r.fire || out_pipe_w.fire) {
        err_sent := false.B
    }

    when (pipe_medium_reg.axi_a_type) { // write
        out_pipe_w.valid := pipe_v_reg && (!pipe_medium_reg.err_v || err_sent)
        out_pipe_w.bits := pipe_medium_reg
        in_pipe.ready := !pipe_v_reg || out_pipe_w.fire
        out_pipe_r.valid := false.B
        out_pipe_r.bits := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }.otherwise {
        out_pipe_r.valid := pipe_v_reg && (!pipe_medium_reg.err_v || err_sent)
        out_pipe_r.bits := pipe_medium_reg
        in_pipe.ready := !pipe_v_reg || out_pipe_r.fire
        out_pipe_w.valid := false.B
        out_pipe_w.bits := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }
}

class DBCheckerPipeStage5R extends Module with DBCheckerConst{ // Return_R
    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

    val s_r_chan = IO(Decoupled(new AxiReadData(128)))
    val m_ar_chan = IO(Decoupled(new AxiAddr(32)))
    val m_r_chan = IO(Flipped(Decoupled(new AxiReadData(128))))

    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
    val pipe_v_reg = RegInit(false.B)
    val ar_release_reg = RegInit(false.B)
    val transfer_done = WireInit(false.B)

    m_ar_chan.valid := false.B
    m_ar_chan.bits := 0.U.asTypeOf(new AxiAddr(32))
    m_r_chan.ready := false.B
    s_r_chan.valid := false.B
    s_r_chan.bits := 0.U.asTypeOf(new AxiReadData(128))

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        ar_release_reg := true.B
        pipe_medium_reg := in_pipe.bits
    }.elsewhen (transfer_done) {
        pipe_v_reg := false.B
        ar_release_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
    }

    when (pipe_medium_reg.err_v) {
        s_r_chan.valid := true.B
        s_r_chan.bits.data := 0.U
        s_r_chan.bits.resp := 2.U // SLVERR
        s_r_chan.bits.last := true.B
    }.otherwise{
          m_ar_chan.valid := ar_release_reg
          m_ar_chan.bits  := pipe_medium_reg.axi_a
          m_ar_chan.bits.addr := pipe_medium_reg.axi_a.addr(31, 0)
          s_r_chan.valid := m_r_chan.valid
          s_r_chan.bits  := m_r_chan.bits
          m_r_chan.ready := s_r_chan.ready
    }
    when (m_ar_chan.fire){
        ar_release_reg := false.B
    }
    when (s_r_chan.fire && s_r_chan.bits.last) {
        transfer_done := true.B
    }
    in_pipe.ready := !pipe_v_reg || transfer_done
}

class DBCheckerPipeStage5W extends Module with DBCheckerConst{ // Return_W
    val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

    val s_w_chan = IO(Flipped(Decoupled(new AxiWriteData(128))))
    val s_b_chan = IO(Decoupled(new AxiWriteResp()))
    val m_aw_chan = IO(Decoupled(new AxiAddr(32)))
    val m_w_chan = IO(Decoupled(new AxiWriteData(128)))
    val m_b_chan = IO(Flipped(Decoupled(new AxiWriteResp())))

    val pipe_medium_reg = RegInit(0.U.asTypeOf(new DBCheckerPipeMedium))
    val pipe_v_reg = RegInit(false.B)
    val aw_release_reg = RegInit(false.B)
    val w_release_reg = RegInit(false.B)
    val transfer_done = WireInit(false.B)

    m_aw_chan.valid := false.B
    m_aw_chan.bits := 0.U.asTypeOf(new AxiAddr(32))

    s_w_chan.ready := false.B
    m_w_chan.valid := false.B
    m_w_chan.bits := 0.U.asTypeOf(new AxiWriteData(128))

    m_b_chan.ready := false.B
    s_b_chan.valid := false.B
    s_b_chan.bits := 0.U.asTypeOf(new AxiWriteResp)

    when (in_pipe.fire) {
        pipe_v_reg := true.B
        pipe_medium_reg := in_pipe.bits
        aw_release_reg := true.B
        w_release_reg := true.B
    }.elsewhen (transfer_done) {
        pipe_v_reg := false.B
        pipe_medium_reg := 0.U.asTypeOf(new DBCheckerPipeMedium)
        aw_release_reg := false.B
        w_release_reg := false.B
    }

     when (pipe_medium_reg.err_v) {
        s_w_chan.ready := w_release_reg
        s_b_chan.valid := !w_release_reg
        s_b_chan.bits.resp := 2.U  // SUPPOSED TO BE SLVERR
    }.otherwise{
        m_aw_chan.valid := aw_release_reg
        m_aw_chan.bits  := pipe_medium_reg.axi_a
        m_aw_chan.bits.addr := pipe_medium_reg.axi_a.addr(31, 0)
        m_w_chan.valid := s_w_chan.valid & w_release_reg
        m_w_chan.bits  := s_w_chan.bits
        s_w_chan.ready := m_w_chan.ready & w_release_reg
        s_b_chan.valid := m_b_chan.valid & !w_release_reg
        s_b_chan.bits  := m_b_chan.bits
        m_b_chan.ready := s_b_chan.ready & !w_release_reg
    }
    when (m_aw_chan.fire) {
        aw_release_reg := false.B
    }
    when (s_w_chan.fire && s_w_chan.bits.last) {
        w_release_reg := false.B
    }
    when (s_b_chan.fire) {
        transfer_done := true.B
    }

    in_pipe.ready := !pipe_v_reg || transfer_done
}
class DBCheckerPipeline extends Module with DBCheckerConst{
  val m_axi_io_rx = IO(new AxiMaster(32, 128))
  val s_axi_io_rx = IO(new AxiSlave(64, 128))
  val ctrl_reg = IO(Input(Vec(RegNum, UInt(64.W))))
  val dbte_v_bm = IO(Input(UInt(dbte_num.W)))
  val decrypt_req = IO(Decoupled(new QarmaInputBundle))
  val decrypt_resp = IO(Flipped(Decoupled(new QarmaOutputBundle)))
  val err_req_r = IO(Decoupled(new DBCheckerErrReq))
  val err_req_w = IO(Decoupled(new DBCheckerErrReq))
  val dbte_sram_r = IO(Flipped(new MemoryReadPort(UInt(32.W), log2Up(dbte_num))))
  val debug_if = IO(Output(UInt(64.W)))

    err_req_w <> DontCare
//frontend
    val stage0 = Module(new DBCheckerPipeStage0)
    val stage1 = Module(new DBCheckerPipeStage1)
    val stage2 = Module(new DBCheckerPipeStage2)
//ringbuffer
    val rb = Module(new RingBuffer(new DBCheckerPipeMedium, 4))
//backend
    val stage3 = Module(new DBCheckerPipeStage3)
    val stage4 = Module(new DBCheckerPipeStage4)
    val stage5r = Module(new DBCheckerPipeStage5R)
    val stage5w = Module(new DBCheckerPipeStage5W)

    stage0.in_ar <> s_axi_io_rx.ar
    stage0.in_aw <> s_axi_io_rx.aw
    stage0.ctrl_chk_en := ctrl_reg(chk_en)(0)

    stage1.in_pipe <> stage0.out_pipe
    stage1.dbte_v_bm := dbte_v_bm
    stage1.dbte_mem_if <> dbte_sram_r

    stage2.in_pipe <> stage1.out_pipe
    stage2.decrypt_key := Cat(ctrl_reg(chk_keyh), ctrl_reg(chk_keyl))
    stage2.decrypt_req <> decrypt_req

    rb.io.enq <> stage2.out_pipe
    rb.io.deq <> stage3.in_pipe

    stage3.decrypt_resp <> decrypt_resp
    stage4.in_pipe <> stage3.out_pipe
    stage4.err_req_if <> err_req_r

    stage5r.in_pipe <> stage4.out_pipe_r
    stage5r.s_r_chan <> s_axi_io_rx.r
    stage5r.m_ar_chan <> m_axi_io_rx.ar
    stage5r.m_r_chan <> m_axi_io_rx.r

    stage5w.in_pipe <> stage4.out_pipe_w
    stage5w.s_w_chan <> s_axi_io_rx.w
    stage5w.s_b_chan <> s_axi_io_rx.b
    stage5w.m_aw_chan <> m_axi_io_rx.aw
    stage5w.m_w_chan <> m_axi_io_rx.w
    stage5w.m_b_chan <> m_axi_io_rx.b

    debug_if := rb.io.count
}