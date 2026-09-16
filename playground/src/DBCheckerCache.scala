package DBChecker

import chisel3._
import chisel3.util._

/** Single-entry cache identities. DBTE layout is independent of cache capacity. */
class DBCheckerCache extends Module {
  val lookup_req = IO(Flipped(Decoupled(new DBCheckerMetaKey)))
  val lookup_done = IO(Valid(new DBCheckerMetaDone))
  val fill_offer = IO(Flipped(Valid(new DBCheckerFill)))
  val fill_take = IO(Output(Bool()))
  val free_window = IO(Input(new DBCheckerFreeWindow))
  val free_start = IO(Input(Bool()))
  val free_done = IO(Output(Bool()))
  val cache_ready = IO(Output(Bool()))

  private val n = DBCheckerConfig.cacheEntries
  private val addrBits = log2Ceil(n)
  private val tagBits = 16 - addrBits
  val data = SyncReadMem(n, UInt(128.W))
  val tags = SyncReadMem(n, UInt(tagBits.W))
  val valid = RegInit(VecInit(Seq.fill(n)(false.B)))
  def addr(idx: UInt): UInt = idx(addrBits - 1, 0)
  def tag(idx: UInt): UInt = idx(15, addrBits)

  cache_ready := !reset.asBool
  lookup_req.ready := cache_ready && !free_window.matches(lookup_req.bits.index)
  val readData = data.read(addr(lookup_req.bits.index), lookup_req.fire)
  val readTag = tags.read(addr(lookup_req.bits.index), lookup_req.fire)
  val responseValid = RegNext(lookup_req.fire, false.B)
  val responseKey = RegEnable(lookup_req.bits, lookup_req.fire)
  val sampledValid = RegEnable(valid(addr(lookup_req.bits.index)), lookup_req.fire)

  // Forward both data and identity on read/write collisions, never only one.
  fill_take := fill_offer.valid && cache_ready && !free_window.active
  val forward = lookup_req.fire && fill_take &&
    addr(lookup_req.bits.index) === addr(fill_offer.bits.key.index)
  val forwardValid = RegNext(forward, false.B)
  val forwardData = RegEnable(fill_offer.bits.dbte, forward)
  val forwardTag = RegEnable(tag(fill_offer.bits.key.index), forward)
  val resultData = Mux(forwardValid, forwardData, readData)
  val resultTag = Mux(forwardValid, forwardTag, readTag)
  val mtdt = resultData.asTypeOf(new DBCheckerMtdt)
  val hit = (sampledValid || forwardValid) &&
    resultTag === tag(responseKey.index) && mtdt.v && !mtdt.no_cache &&
    mtdt.index_offset === responseKey.index(3, 0) && responseKey.index =/= 0.U
  lookup_done.valid := responseValid
  lookup_done.bits.key := responseKey
  lookup_done.bits.dbte := resultData
  // Query latency is exactly one cycle. Launch is blocked during FREE; the
  // response-cycle match covers every query crossing the start of a window.
  lookup_done.bits.status := Mux(free_window.matches(responseKey.index),
    DBCheckerMetaStatus.retry, Mux(hit, DBCheckerMetaStatus.data, DBCheckerMetaStatus.miss))

  when(fill_take) {
    assert(fill_offer.bits.dbte.asTypeOf(new DBCheckerMtdt).v)
    assert(!fill_offer.bits.dbte.asTypeOf(new DBCheckerMtdt).no_cache)
    data.write(addr(fill_offer.bits.key.index), fill_offer.bits.dbte)
    tags.write(addr(fill_offer.bits.key.index), tag(fill_offer.bits.key.index))
    valid(addr(fill_offer.bits.key.index)) := true.B
  }

  // Exact FREE verifies the cache tag; clear_all scans the valid array. No
  // cache writes are accepted during either operation. Query completions do
  // not need to drain before acknowledging: their own response match wins.
  val freeTag = tags.read(addr(free_window.index), free_start && !free_window.clear_all)
  val exactPending = RegNext(free_start && !free_window.clear_all, false.B)
  val clearing = RegInit(false.B)
  val clearPtr = RegInit(0.U(addrBits.W))
  when(free_start && free_window.clear_all) {
    clearing := true.B
    clearPtr := 0.U
  }
  free_done := false.B
  when(exactPending) {
    when(valid(addr(free_window.index)) && freeTag === tag(free_window.index)) {
      valid(addr(free_window.index)) := false.B
    }
    free_done := true.B
  }
  when(clearing) {
    valid(clearPtr) := false.B
    when(clearPtr === (n - 1).U) {
      clearing := false.B
      free_done := true.B
    }.otherwise {
      clearPtr := clearPtr + 1.U
    }
  }
}
