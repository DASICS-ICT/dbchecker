package DBChecker

import chisel3._
import chisel3.util._
import axi._

/** MMIO and FREE control only; metadata reads run in DBCheckerRefill. */
class DBCheckerCtrl extends Module with DBCheckerConst {
  val s_axil = IO(new AxiLiteSlave(32, 32))
  val enable_mask = IO(Output(UInt(32.W)))
  val dbte_base = IO(Output(UInt(48.W)))
  val refill_limit = IO(Output(UInt(DBCheckerConfig.creditBits.W)))
  val free_window = IO(Output(new DBCheckerFreeWindow))
  val free_start = IO(Output(Bool()))
  val cache_free_done = IO(Input(Bool()))
  val error = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val events = IO(Input(new DBCheckerEvents))
  val pipeline_idle = IO(Input(Bool()))
  val refill_idle = IO(Input(Bool()))
  val occupancy = IO(Input(UInt(log2Ceil(DBCheckerConfig.requestDepth + 1).W)))
  val outstanding = IO(Input(UInt(DBCheckerConfig.creditBits.W)))
  val debug_if = IO(Output(UInt(128.W)))

  val en = RegInit(0.U(32.W))
  val baseLo = RegInit(0.U(32.W))
  val baseHi = RegInit(0.U(32.W))
  val command = RegInit(0.U(32.W))
  val limit = RegInit(math.min(32, DBCheckerConfig.refillCapacity).U(DBCheckerConfig.creditBits.W))
  val errCount = RegInit(VecInit(Seq.fill(4)(0.U(7.W))))
  val errLatest = RegInit(0.U(4.W))
  val errInfo = RegInit(0.U(32.W))
  val errAddr = RegInit(0.U(64.W))
  val cmd = command.asTypeOf(new DBCheckerCommand)
  val isFree = cmd.v && cmd.op === cmd_op_free
  val clearError = cmd.v && cmd.op === cmd_op_clr_err
  val wasFree = RegNext(isFree, false.B)
  free_window.active := isFree
  free_window.clear_all := cmd.imm(16)
  free_window.index := cmd.imm(15, 0)
  free_start := isFree && !wasFree
  when(isFree && cache_free_done) { command := command & "h7fffffff".U }
  when(clearError) {
    command := command & "h7fffffff".U
    errCount.foreach(_ := 0.U)
    errLatest := 0.U
    errInfo := 0.U
    errAddr := 0.U
  }
  error.ready := !clearError
  when(error.fire) {
    val t = error.bits.typ
    when(!errCount(t).andR) { errCount(t) := errCount(t) + 1.U }
    errLatest := UIntToOH(t, 4)
    errInfo := error.bits.info
    errAddr := error.bits.addr
  }

  // Independent AW and W capture: pending commands are retained while the
  // previous command is active, without acknowledging a dropped command.
  val awPending = RegInit(false.B)
  val wPending = RegInit(false.B)
  val awAddr = Reg(UInt(32.W))
  val wData = Reg(UInt(32.W))
  val wStrb = Reg(UInt(4.W))
  val bValid = RegInit(false.B)
  val bResp = Reg(UInt(2.W))
  s_axil.aw.ready := !awPending && !bValid
  s_axil.w.ready := !wPending && !bValid
  when(s_axil.aw.fire) { awPending := true.B; awAddr := s_axil.aw.bits.addr }
  when(s_axil.w.fire) { wPending := true.B; wData := s_axil.w.bits.data; wStrb := s_axil.w.bits.strb }
  s_axil.b.valid := bValid
  s_axil.b.bits.resp := bResp
  when(s_axil.b.fire) { bValid := false.B }
  // Interconnects may preserve the physical MMIO base; decode only the offset.
  val writeIndex = awAddr(6, 2)
  val wordAligned = awAddr(1, 0) === 0.U
  val writeCanExecute = awPending && wPending && !bValid &&
    !(wordAligned && writeIndex === chk_cmd.U && cmd.v)
  val mask = Cat((0 until 4).reverse.map(i => Fill(8, wStrb(i))))
  def merged(old: UInt): UInt = (old.pad(32) & ~mask) | (wData & mask)
  val resetPerf = writeCanExecute && wordAligned && writeIndex === chk_en.U && merged(en) =/= 0.U
  when(writeCanExecute) {
    awPending := false.B
    wPending := false.B
    bValid := true.B
    bResp := 0.U
    when(!wordAligned) { bResp := 2.U }.otherwise {
      switch(writeIndex) {
        is(chk_en.U) { en := merged(en) }
        is(chk_cmd.U) { command := merged(command) }
        is(chk_dbte_mb_lo.U) {
          when(en === 0.U && pipeline_idle && refill_idle && !isFree) { baseLo := merged(baseLo) }
            .otherwise { bResp := 2.U }
        }
        is(chk_dbte_mb_hi.U) {
          when(en === 0.U && pipeline_idle && refill_idle && !isFree) { baseHi := merged(baseHi) }
            .otherwise { bResp := 2.U }
        }
        is(16.U) {
          val requested = merged(limit)
          when(refill_idle && requested >= 1.U && requested <= DBCheckerConfig.refillCapacity.U) {
            limit := requested
          }.otherwise { bResp := 2.U }
        }
      }
    }
  }

  // Legacy hit/miss/head-wait offsets plus explicit new endpoint counters.
  val counters = RegInit(VecInit(Seq.fill(12)(0.U(32.W))))
  val pulses = Seq(events.hit, events.ar, events.head_wait, events.accepted,
    events.committed, events.lookup, events.r, events.refill_done, events.retry,
    events.input_stall, events.output_ar, events.output_aw)
  for (i <- pulses.indices) {
    when(pulses(i) && !counters(i).andR) { counters(i) := counters(i) + 1.U }
    when(resetPerf) { counters(i) := 0.U }
  }
  val readValid = RegInit(false.B)
  val readValue = Reg(UInt(32.W))
  val readResp = Reg(UInt(2.W))
  s_axil.ar.ready := !readValid || s_axil.r.ready
  s_axil.r.valid := readValid
  s_axil.r.bits.data := readValue
  s_axil.r.bits.resp := readResp
  when(s_axil.r.fire) { readValid := false.B }
  val readIndex = s_axil.ar.bits.addr(6, 2)
  val readWords = Wire(Vec(32, UInt(32.W)))
  readWords.foreach(_ := 0.U)
  readWords(chk_en) := en
  readWords(chk_cmd) := command
  readWords(chk_dbte_mb_lo) := baseLo
  readWords(chk_dbte_mb_hi) := baseHi
  readWords(chk_err_addr_lo) := errAddr(31, 0)
  readWords(chk_err_addr_hi) := errAddr(63, 32)
  readWords(chk_err_info) := errInfo
  readWords(chk_err_cnt) := Cat(errCount.asUInt, errLatest)
  readWords(chk_perf_hit) := counters(0)
  readWords(chk_perf_miss) := counters(1)
  readWords(chk_perf_penalty) := counters(2)
  readWords(16) := limit
  readWords(17) := Cat(DBCheckerConfig.refillCapacity.U(16.W), DBCheckerConfig.requestDepth.U(16.W))
  readWords(18) := Cat(0.U(15.W), isFree, outstanding.pad(8), occupancy.pad(8))
  // 0x4c..0x6c: accepted, committed, lookup, R beats, refill done, retry
  // events, input stall cycles, downstream AR and downstream AW.
  for (i <- 3 until 12) { readWords(19 + i - 3) := counters(i) }
  readWords(31) := "h42433131".U // BC11: buffered-check implementation v1
  when(s_axil.ar.fire) {
    readValid := true.B
    readValue := readWords(readIndex)
    readResp := Mux(s_axil.ar.bits.addr(1, 0) === 0.U, 0.U, 2.U)
  }
  enable_mask := en
  dbte_base := Cat(baseHi(15, 0), baseLo)
  refill_limit := limit
  debug_if := Cat(command, errInfo, errAddr)
}
