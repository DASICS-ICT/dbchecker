package DBChecker

import chisel3._
import chisel3.util._
import axi._

/** Independent 16-byte reads, single AXI ID, one completion per cycle. */
class DBCheckerRefill extends Module {
  val req = IO(Flipped(Decoupled(new DBCheckerMetaKey)))
  val done = IO(Valid(new DBCheckerMetaDone))
  val m_axi = IO(new AxiMaster(48, 128))
  val fill_offer = IO(Valid(new DBCheckerFill))
  val fill_take = IO(Input(Bool()))
  val free_window = IO(Input(new DBCheckerFreeWindow))
  val base = IO(Input(UInt(48.W)))
  val limit = IO(Input(UInt(DBCheckerConfig.creditBits.W)))
  val idle = IO(Output(Bool()))
  val outstanding = IO(Output(UInt(DBCheckerConfig.creditBits.W)))
  val ar_event = IO(Output(Bool()))
  val r_event = IO(Output(Bool()))

  private val n = DBCheckerConfig.refillCapacity
  private val p = log2Ceil(n)
  val miss = Module(new Queue(new DBCheckerMetaKey, DBCheckerConfig.requestDepth))
  miss.io.enq <> req
  val keys = Reg(Vec(n, new DBCheckerMetaKey))
  val allocated = RegInit(VecInit(Seq.fill(n)(false.B)))
  val received = RegInit(VecInit(Seq.fill(n)(false.B)))
  val poisoned = RegInit(VecInit(Seq.fill(n)(false.B)))
  val bad = RegInit(VecInit(Seq.fill(n)(false.B)))
  val values = Mem(n, UInt(128.W))
  val issueTail = RegInit(0.U(p.W))
  val receiveHead = RegInit(0.U(p.W))
  val completeHead = RegInit(0.U(p.W))
  val count = RegInit(0.U(DBCheckerConfig.creditBits.W))
  outstanding := count

  val headData = values(completeHead)
  val headMtdt = headData.asTypeOf(new DBCheckerMtdt)
  val headReady = allocated(completeHead) && received(completeHead)
  val headPoison = poisoned(completeHead) || free_window.matches(keys(completeHead).index)
  val headInvalid = bad(completeHead) || !headMtdt.v ||
    keys(completeHead).index === 0.U ||
    headMtdt.index_offset =/= keys(completeHead).index(3, 0)
  val wantsFill = headReady && !headPoison && !headInvalid && !headMtdt.no_cache
  fill_offer.valid := wantsFill
  fill_offer.bits.key := keys(completeHead)
  fill_offer.bits.dbte := headData
  val finish = headReady && (!wantsFill || fill_take)
  done.valid := finish
  done.bits.key := keys(completeHead)
  done.bits.status := Mux(headPoison, DBCheckerMetaStatus.retry,
    Mux(headInvalid, DBCheckerMetaStatus.invalid, DBCheckerMetaStatus.data))
  val checkedData = WireInit(headMtdt)
  when(headInvalid) { checkedData.v := false.B }
  done.bits.dbte := checkedData.asUInt

  val arValid = RegInit(false.B)
  val arKey = Reg(new DBCheckerMetaKey)
  val arAddr = Reg(UInt(48.W))
  val arPoison = RegInit(false.B)
  m_axi.ar.valid := arValid
  m_axi.ar.bits := 0.U.asTypeOf(m_axi.ar.bits)
  m_axi.ar.bits.addr := arAddr
  m_axi.ar.bits.size := 4.U
  m_axi.ar.bits.len := 0.U
  m_axi.ar.bits.burst := 1.U
  m_axi.ar.bits.cache := "b1011".U
  m_axi.ar.bits.prot := "b010".U
  m_axi.aw.valid := false.B
  m_axi.aw.bits := 0.U.asTypeOf(m_axi.aw.bits)
  m_axi.w.valid := false.B
  m_axi.w.bits := 0.U.asTypeOf(m_axi.w.bits)
  m_axi.b.ready := false.B

  val nextCount = count +& m_axi.ar.fire.asUInt - finish.asUInt
  val mayLoad = (!arValid || m_axi.ar.fire) && nextCount < limit &&
    !free_window.matches(miss.io.deq.bits.index)
  miss.io.deq.ready := mayLoad
  when(arValid && free_window.matches(arKey.index)) { arPoison := true.B }
  when(m_axi.ar.fire) { arValid := false.B }
  when(miss.io.deq.fire) {
    arValid := true.B
    arKey := miss.io.deq.bits
    arAddr := base + (miss.io.deq.bits.index << 4)
    arPoison := false.B
  }

  // Every allocated entry retains poison until its final completion, including
  // entries whose R data arrived while the cache write port was unavailable.
  for (i <- 0 until n) {
    when(allocated(i) && free_window.matches(keys(i).index)) { poisoned(i) := true.B }
  }
  when(finish) {
    allocated(completeHead) := false.B
    received(completeHead) := false.B
    poisoned(completeHead) := false.B
    completeHead := completeHead + 1.U
  }
  when(m_axi.ar.fire) {
    assert(count < limit)
    assert(!allocated(issueTail) || (finish && completeHead === issueTail))
    allocated(issueTail) := true.B
    received(issueTail) := false.B
    poisoned(issueTail) := arPoison || free_window.matches(arKey.index)
    bad(issueTail) := false.B
    keys(issueTail) := arKey
    issueTail := issueTail + 1.U
  }
  count := nextCount
  assert(count <= limit)
  assert(limit > 0.U && limit <= n.U)

  // A malformed late RLAST is drained as part of the same transaction. Only
  // its first beat is stored and it is never installed as valid metadata.
  val draining = RegInit(false.B)
  m_axi.r.ready := allocated(receiveHead) && !received(receiveHead)
  when(m_axi.r.fire) {
    when(!draining) { values.write(receiveHead, m_axi.r.bits.data) }
    when(draining || !m_axi.r.bits.last || m_axi.r.bits.resp =/= 0.U) {
      bad(receiveHead) := true.B
    }
    when(m_axi.r.bits.last) {
      received(receiveHead) := true.B
      receiveHead := receiveHead + 1.U
      draining := false.B
    }.otherwise {
      draining := true.B
    }
  }
  idle := !miss.io.deq.valid && !arValid && count === 0.U
  ar_event := m_axi.ar.fire
  r_event := m_axi.r.fire
}
