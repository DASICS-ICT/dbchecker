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
  val invalidate  = IO(Input(new DBCheckerInvalidate))
  val dbte_sram_if = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val dbte_meta_sram_if = IO(Flipped(new MemoryReadPort(new DBCheckerCacheMeta, log2Up(dbte_set_num))))
  val dbte_refill_req_if = IO(Decoupled(new DBCheckerDBTEReq))
  val dbte_refill_rsp_if = IO(Flipped(Decoupled(new DBCheckerDBTERsp)))
  val refill_line64 = IO(Input(Bool()))
  val perf = IO(Output(new DBCheckerPerfEvent))

  // Eight entries preserve request order while allowing requests behind a miss
  // to be accepted and coalesced into the same 64-byte refill.
  val robDepth = 8
  val robPtrWidth = log2Up(robDepth)
  val robCountWidth = log2Up(robDepth + 1)
  val stateNew = 0.U(3.W)
  val stateLookup = 1.U(3.W)
  val stateMiss = 2.U(3.W)
  val stateResolved = 3.U(3.W)
  val stateWait = 4.U(3.W)

  val robValid = RegInit(VecInit(Seq.fill(robDepth)(false.B)))
  val robState = RegInit(VecInit(Seq.fill(robDepth)(stateNew)))
  val robMedium = RegInit(VecInit(Seq.fill(robDepth)(0.U.asTypeOf(new DBCheckerPipeMedium))))
  val robDbte = RegInit(VecInit(Seq.fill(robDepth)(0.U(128.W))))
  val robHead = RegInit(0.U(robPtrWidth.W))
  val robTail = RegInit(0.U(robPtrWidth.W))
  val robCount = RegInit(0.U(robCountWidth.W))

  val refillInflight = RegInit(false.B)
  val refillIndex = RegInit(0.U(16.W))
  val refillLine64Reg = RegInit(true.B)
  val gatherCounter = RegInit(0.U(2.W))

  def entryPtr(entry: DBCheckerPipeMedium): DBCheckerPtr =
    entry.axi_a.addr.asTypeOf(new DBCheckerPtr)

  // Ordered retirement.
  val headPtr = entryPtr(robMedium(robHead))
  out_pipe.valid := robValid(robHead) && robState(robHead) === stateResolved
  out_pipe.bits := robMedium(robHead)
  out_pipe.bits.dbte := robDbte(robHead)

  val headInvalid = !robMedium(robHead).bypass && !robDbte(robHead).asTypeOf(new DBCheckerMtdt).v
  val headErrInfo = Wire(new DBCheckerErrInfo)
  headErrInfo.err_mtdt_index := headPtr.get_index
  headErrInfo.err_info := 0.U
  when(!robMedium(robHead).err_v && headInvalid) {
    out_pipe.bits.err_v := true.B
    out_pipe.bits.err_req.typ := err_mtdt_finv
    out_pipe.bits.err_req.addr := headPtr.asUInt
    out_pipe.bits.err_req.info := headErrInfo.asUInt
  }

  val retire = out_pipe.fire
  in_pipe.ready := robCount =/= robDepth.U || retire
  val enqueue = in_pipe.fire

  when(retire) {
    robValid(robHead) := false.B
    robState(robHead) := stateNew
    robHead := robHead + 1.U
  }

  switch(Cat(enqueue, retire)) {
    is("b10".U) { robCount := robCount + 1.U }
    is("b01".U) { robCount := robCount - 1.U }
  }

  // One synchronous SRAM lookup is issued per cycle.  Cache identity is
  // {tag[15:12], set[11:2], sector[1:0]}.
  val lookupCandidates = VecInit((0 until robDepth).map(i =>
    robValid(i) && robState(i) === stateNew && !robMedium(i).bypass))
  val lookupAny = lookupCandidates.asUInt.orR
  val lookupSlot = PriorityEncoder(lookupCandidates.asUInt)
  dbte_sram_if.enable := lookupAny
  dbte_sram_if.address := entryPtr(robMedium(lookupSlot)).get_cache_addr
  dbte_meta_sram_if.enable := lookupAny
  dbte_meta_sram_if.address := entryPtr(robMedium(lookupSlot)).get_set

  val lookupRspValid = RegNext(lookupAny, false.B)
  val lookupRspSlot = RegEnable(lookupSlot, lookupAny)
  when(lookupAny) {
    robState(lookupSlot) := stateLookup
  }

  val lookupPtr = entryPtr(robMedium(lookupRspSlot))
  val lookupMtdt = dbte_sram_if.data.asTypeOf(new DBCheckerMtdt)
  val lookupMeta = dbte_meta_sram_if.data
  val lookupHit = lookupMeta.valid(lookupPtr.get_sector) &&
                  lookupMeta.tag === lookupPtr.get_tag &&
                  lookupMtdt.v && lookupMtdt.index_offset === lookupPtr.get_index(3, 0)
  when(lookupRspValid && robValid(lookupRspSlot) && robState(lookupRspSlot) === stateLookup) {
    when(lookupHit) {
      robDbte(lookupRspSlot) := dbte_sram_if.data
      robState(lookupRspSlot) := stateResolved
    }.otherwise {
      robState(lookupRspSlot) := stateMiss
    }
  }

  // A single refill MSHR is used in this phase.  At AR.fire all requests already
  // present for that line become waiters; later no-cache requests must refill.
  val missCandidates = VecInit((0 until robDepth).map(i =>
    robValid(i) && robState(i) === stateMiss))
  val missAny = missCandidates.asUInt.orR
  val missSlot = PriorityEncoder(missCandidates.asUInt)
  val missPtr = entryPtr(robMedium(missSlot))
  dbte_refill_req_if.valid := missAny && !refillInflight && gatherCounter.andR
  dbte_refill_req_if.bits.index := missPtr.get_index

  when(refillInflight || !missAny) {
    gatherCounter := 0.U
  }.elsewhen(!gatherCounter.andR) {
    gatherCounter := gatherCounter + 1.U
  }

  when(dbte_refill_req_if.fire) {
    refillInflight := true.B
    refillIndex := missPtr.get_index
    refillLine64Reg := refill_line64
    gatherCounter := 0.U
    for (i <- 0 until robDepth) {
      val ptr = entryPtr(robMedium(i))
      val sameFill = Mux(refill_line64,
                         ptr.get_line === missPtr.get_line,
                         ptr.get_index === missPtr.get_index)
      when(robValid(i) && robState(i) =/= stateResolved &&
           !robMedium(i).bypass && ptr.get_index =/= 0.U &&
           sameFill) {
        robState(i) := stateWait
      }
    }
  }

  dbte_refill_rsp_if.ready := refillInflight
  val refillServedVec = VecInit((0 until robDepth).map(i => {
    val ptr = entryPtr(robMedium(i))
    val sameFill = Mux(refillLine64Reg,
                       ptr.get_line === dbte_refill_rsp_if.bits.line_index,
                       ptr.get_index === refillIndex)
    val returnedMtdt = dbte_refill_rsp_if.bits.dbte(ptr.get_sector).asTypeOf(new DBCheckerMtdt)
    val lateCacheUse = returnedMtdt.v && !returnedMtdt.no_cache &&
                       returnedMtdt.index_offset === ptr.get_index(3, 0)
    robValid(i) && robState(i) =/= stateResolved && sameFill &&
      (robState(i) === stateWait || lateCacheUse)
  }))
  val refillServedCount = PopCount(refillServedVec)
  when(dbte_refill_rsp_if.fire) {
    for (i <- 0 until robDepth) {
      val ptr = entryPtr(robMedium(i))
      val sameFill = Mux(refillLine64Reg,
                         ptr.get_line === dbte_refill_rsp_if.bits.line_index,
                         ptr.get_index === refillIndex)
      when(robValid(i) && robState(i) =/= stateResolved &&
           sameFill) {
        when(robState(i) === stateWait) {
          robDbte(i) := dbte_refill_rsp_if.bits.dbte(ptr.get_sector)
          robState(i) := stateResolved
        }.otherwise {
          // Arrived after AR.fire: retry the cache.  A no-cache sector will miss
          // again, so refill data is never retained beyond the frozen waiters.
          robState(i) := stateNew
        }
      }
    }
    refillInflight := false.B
  }

  // FREE invalidates matching queued work as well as the cache/MSHR.  This is
  // placed after lookup/refill resolution so FREE has final priority.
  when(invalidate.valid) {
    for (i <- 0 until robDepth) {
      val ptr = entryPtr(robMedium(i))
      when(robValid(i) && !robMedium(i).bypass &&
           (invalidate.clear_all || ptr.get_index === invalidate.index)) {
        val invalidMtdt = WireInit(robDbte(i).asTypeOf(new DBCheckerMtdt))
        invalidMtdt.v := false.B
        robDbte(i) := invalidMtdt.asUInt
        robState(i) := stateResolved
      }
    }
  }

  // Enqueue is last so a simultaneous retire/full enqueue correctly reuses the
  // same physical slot.  Metadata ID 0 is rejected without issuing AXI DBTE AR.
  when(enqueue) {
    val newPtr = entryPtr(in_pipe.bits)
    val invalidNew = invalidate.valid &&
                     (invalidate.clear_all || newPtr.get_index === invalidate.index)
    robValid(robTail) := true.B
    robMedium(robTail) := in_pipe.bits
    robDbte(robTail) := 0.U
    when(in_pipe.bits.bypass) {
      robState(robTail) := stateResolved
    }.elsewhen(newPtr.get_index === 0.U || invalidNew) {
      robState(robTail) := stateResolved
    }.elsewhen(dbte_refill_req_if.fire &&
               Mux(refill_line64,
                   newPtr.get_line === missPtr.get_line,
                   newPtr.get_index === missPtr.get_index)) {
      robState(robTail) := stateWait
    }.otherwise {
      robState(robTail) := stateNew
    }
    robTail := robTail + 1.U
  }

  perf.hit := lookupRspValid && robValid(lookupRspSlot) &&
              robState(lookupRspSlot) === stateLookup && lookupHit
  perf.miss := dbte_refill_req_if.fire
  perf.penalty := refillInflight
  perf.refill_waiters := Mux(dbte_refill_rsp_if.fire, refillServedCount, 0.U)
  val differentLineQueued = VecInit((0 until robDepth).map(i => {
    val ptr = entryPtr(robMedium(i))
    val sameFill = Mux(refillLine64Reg,
                       ptr.get_line === refillIndex(15, 2),
                       ptr.get_index === refillIndex)
    robValid(i) && robState(i) =/= stateResolved &&
      !robMedium(i).bypass && !sameFill
  })).asUInt.orR
  perf.different_line_wait := refillInflight && differentLineQueued
  perf.rob_full := robCount === robDepth.U && !retire
  perf.refill_bytes := Mux(dbte_refill_req_if.fire,
                           Mux(refill_line64, 64.U, 16.U),
                           0.U)

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
}

class DBCheckerPipeStage4W extends Module with DBCheckerConst { // Return_W
  val in_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))

  val s_w_chan  = IO(Flipped(Decoupled(new AxiWriteData(128))))
  val s_b_chan  = IO(Decoupled(new AxiWriteResp(idWidth = 5)))
  val m_aw_chan = IO(Decoupled(new AxiAddr(64, idWidth = 5)))
  val m_w_chan  = IO(Decoupled(new AxiWriteData(128)))
  val m_b_chan  = IO(Flipped(Decoupled(new AxiWriteResp(idWidth = 5))))

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
}

class DBCheckerPipeline extends Module with DBCheckerConst {
  val m_axi_io_rx  = IO(new AxiMaster(64, 128, idWidth = 5))
  val s_axi_io_rx  = IO(new AxiSlave(64, 128, idWidth = 5))
  val ctrl_reg     = IO(Input(Vec(RegNum, UInt(32.W))))
  val invalidate   = IO(Input(new DBCheckerInvalidate))
  val err_req_r    = IO(Decoupled(new DBCheckerErrReq))
  val err_req_w    = IO(Decoupled(new DBCheckerErrReq))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val dbte_meta_sram_r = IO(Flipped(new MemoryReadPort(new DBCheckerCacheMeta, log2Up(dbte_set_num))))
  val refill_dbte_req_if = IO(Decoupled(new DBCheckerDBTEReq))
  val refill_dbte_rsp_if = IO(Flipped(Decoupled(new DBCheckerDBTERsp)))
  val debug_if     = IO(Output(UInt(128.W)))
  val perf         = IO(Output(new DBCheckerPerfEvent))

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
  stage1.invalidate := invalidate
  stage1.dbte_sram_if <> dbte_sram_r
  stage1.dbte_meta_sram_if <> dbte_meta_sram_r
  stage1.dbte_refill_req_if <> refill_dbte_req_if
  stage1.dbte_refill_rsp_if <> refill_dbte_rsp_if
  stage1.refill_line64 := ctrl_reg(chk_refill_cfg)(0)

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

  debug_if := stage3.debug_dbte.asUInt
  perf     := stage1.perf
}
