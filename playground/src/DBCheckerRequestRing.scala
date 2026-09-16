package DBChecker

import chisel3._
import chisel3.util._

/** Fixed request slots. Authorization is combinational at the commit boundary. */
class DBCheckerRequestRing extends Module with DBCheckerConst {
  val in = IO(Flipped(Decoupled(new DBCheckerRequest)))
  val commit = IO(Decoupled(new DBCheckerCommit))
  val lookup_req = IO(Decoupled(new DBCheckerMetaKey))
  val lookup_done = IO(Flipped(Valid(new DBCheckerMetaDone)))
  val refill_req = IO(Decoupled(new DBCheckerMetaKey))
  val refill_done = IO(Flipped(Valid(new DBCheckerMetaDone)))
  val free_window = IO(Input(new DBCheckerFreeWindow))
  val cache_ready = IO(Input(Bool()))
  val occupancy = IO(Output(UInt(log2Ceil(DBCheckerConfig.requestDepth + 1).W)))
  val head_wait = IO(Output(Bool()))

  private val n = DBCheckerConfig.requestDepth
  private val p = DBCheckerConfig.slotBits
  val needLookup = 0.U(3.W)
  val lookupOwned = 1.U(3.W)
  val needRefill = 2.U(3.W)
  val refillOwned = 3.U(3.W)
  val readyHit = 4.U(3.W)
  val readyFill = 5.U(3.W)
  val valid = RegInit(VecInit(Seq.fill(n)(false.B)))
  val state = Reg(Vec(n, UInt(3.W)))
  val requests = Reg(Vec(n, new DBCheckerRequest))
  val hitData = Mem(n, UInt(128.W))
  val refillData = Mem(n, UInt(128.W))
  val head = RegInit(0.U(p.W))
  val tail = RegInit(0.U(p.W))
  val count = RegInit(0.U(log2Ceil(n + 1).W))
  val lookupCursor = RegInit(0.U(p.W))
  val refillCursor = RegInit(0.U(p.W))
  def index(i: UInt): UInt = requests(i).axi_a.addr(63, 48)

  // Round-robin picks only identities, not whole request records. Ingress and
  // completions update state independently; no waiter merging or CAM is used.
  def choose(mask: UInt, cursor: UInt): UInt = {
    val above = mask & ((Fill(n, 1.U(1.W)) << cursor)(n - 1, 0))
    PriorityEncoder(Mux(above.orR, above, mask))
  }
  val lookupMask = VecInit((0 until n).map(i => valid(i) && state(i) === needLookup &&
    !free_window.matches(requests(i).axi_a.addr(63, 48)))).asUInt
  val refillMask = VecInit((0 until n).map(i => valid(i) && state(i) === needRefill &&
    !free_window.matches(requests(i).axi_a.addr(63, 48)))).asUInt
  val lookupSlot = choose(lookupMask, lookupCursor)
  val refillSlot = choose(refillMask, refillCursor)
  lookup_req.valid := cache_ready && lookupMask.orR
  lookup_req.bits.slot := lookupSlot
  lookup_req.bits.index := index(lookupSlot)
  refill_req.valid := cache_ready && refillMask.orR
  refill_req.bits.slot := refillSlot
  refill_req.bits.index := index(refillSlot)
  when(lookup_req.fire) {
    state(lookupSlot) := lookupOwned
    lookupCursor := lookupSlot + 1.U
  }
  when(refill_req.fire) {
    state(refillSlot) := refillOwned
    refillCursor := refillSlot + 1.U
  }
  when(lookup_done.valid) {
    val i = lookup_done.bits.key.slot
    assert(valid(i) && state(i) === lookupOwned)
    assert(index(i) === lookup_done.bits.key.index)
    hitData.write(i, lookup_done.bits.dbte)
    state(i) := Mux(lookup_done.bits.status === DBCheckerMetaStatus.data &&
      !free_window.matches(index(i)), readyHit, needRefill)
  }
  when(refill_done.valid) {
    val i = refill_done.bits.key.slot
    assert(valid(i) && state(i) === refillOwned)
    assert(index(i) === refill_done.bits.key.index)
    refillData.write(i, refill_done.bits.dbte)
    state(i) := Mux(refill_done.bits.status === DBCheckerMetaStatus.retry ||
      free_window.matches(index(i)), needRefill, readyFill)
  }
  when(lookup_done.valid && refill_done.valid) {
    assert(lookup_done.bits.key.slot =/= refill_done.bits.key.slot)
  }

  val request = requests(head)
  val mtdt = Mux(state(head) === readyFill, refillData(head), hitData(head)).asTypeOf(new DBCheckerMtdt)
  val metadataReady = state(head) === readyHit || state(head) === readyFill
  val freeHitsHead = !request.bypass && free_window.matches(index(head))
  val address = request.axi_a.addr(47, 0)
  val beatBytes = 1.U(9.W) << request.axi_a.size
  val span = (request.axi_a.len +& 1.U) << request.axi_a.size
  val beatMask = (beatBytes - 1.U).pad(48)
  val spanMask = (span - 1.U).pad(48)
  val beatBase = address & ~beatMask
  val wrapBase = address & ~spanMask
  val isFixed = request.axi_a.burst === 0.U
  val isWrap = request.axi_a.burst === 2.U
  val lower = Mux(isWrap, wrapBase, address)
  val endBase = Mux(isWrap, wrapBase, beatBase)
  val lastByte = endBase +& Mux(isFixed, beatBytes - 1.U, span - 1.U)
  val legalWrap = (request.axi_a.len === 1.U || request.axi_a.len === 3.U ||
    request.axi_a.len === 7.U || request.axi_a.len === 15.U) && (address & beatMask) === 0.U
  val formatError = request.axi_a.size > 4.U || request.axi_a.burst === 3.U ||
    (isWrap && !legalWrap)
  val invalid = index(head) === 0.U || !mtdt.v
  val below = lower < mtdt.bnd_lo
  val boundError = below || lastByte >= mtdt.bnd_hi.pad(lastByte.getWidth) || formatError
  val permissionError = Mux(request.is_write, !mtdt.w, !mtdt.r)
  val deviceError = mtdt.dev_id =/= request.axi_a.id(4)
  val error = !request.bypass && (invalid || boundError || permissionError || deviceError)
  val info = Wire(new DBCheckerErrInfo)
  info.err_mtdt_index := index(head)
  info.err_info := Mux(invalid, 0.U,
    Mux(boundError, Mux(formatError, 2.U, !below),
      Mux(permissionError, request.is_write.asUInt,
        Cat(mtdt.dev_id.pad(8), Cat(0.U(7.W), request.axi_a.id(4))))))
  commit.valid := valid(head) && metadataReady && !freeHitsHead
  commit.bits.request := request
  commit.bits.error := error
  commit.bits.error_info.typ := Mux(invalid, err_mtdt_finv,
    Mux(boundError, err_bnd_farea, Mux(permissionError, err_bnd_ftype, err_wrong_dev)))
  commit.bits.error_info.info := info.asUInt
  commit.bits.error_info.addr := request.axi_a.addr

  // Already-owned tasks finish through their owner, including poisoned tasks.
  // A FREE of a saved result instead restarts that slot from a memory read.
  for (i <- 0 until n) {
    when(valid(i) && !requests(i).bypass && requests(i).axi_a.addr(63, 48) =/= 0.U && free_window.matches(requests(i).axi_a.addr(63, 48)) &&
      (state(i) === readyHit || state(i) === readyFill)) {
      state(i) := needRefill
    }
  }
  when(commit.fire) {
    valid(head) := false.B
    head := head + 1.U
  }
  in.ready := count =/= n.U || commit.fire
  when(in.fire) {
    valid(tail) := true.B
    requests(tail) := in.bits
    // Index 0 is an immediate invalid result and never generates a table read.
    // A matching FREE still blocks its commit like every checked request.
    state(tail) := Mux(in.bits.bypass || in.bits.axi_a.addr(63, 48) === 0.U, readyHit, needLookup)
    tail := tail + 1.U
  }
  when(in.fire =/= commit.fire) {
    count := Mux(in.fire, count + 1.U, count - 1.U)
  }
  assert(count <= n.U)
  occupancy := count
  head_wait := valid(head) && (!metadataReady || freeHitsHead)
}
