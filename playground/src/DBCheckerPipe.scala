package DBChecker

import chisel3._
import chisel3.util._
import axi._

/** The address path has a request ring, a combinational check and output regs. */
class DBCheckerPipeline extends Module with DBCheckerConst {
  val m_axi_io_rx = IO(new AxiMaster(64, 128, idWidth = 5))
  val s_axi_io_rx = IO(new AxiSlave(64, 128, idWidth = 5))
  val enable_mask = IO(Input(UInt(32.W)))
  val free_window = IO(Input(new DBCheckerFreeWindow))
  val cache_ready = IO(Input(Bool()))
  val lookup_req = IO(Decoupled(new DBCheckerMetaKey))
  val lookup_done = IO(Flipped(Valid(new DBCheckerMetaDone)))
  val refill_req = IO(Decoupled(new DBCheckerMetaKey))
  val refill_done = IO(Flipped(Valid(new DBCheckerMetaDone)))
  val error = IO(Decoupled(new DBCheckerErrReq))
  val occupancy = IO(Output(UInt(log2Ceil(DBCheckerConfig.requestDepth + 1).W)))
  val idle = IO(Output(Bool()))
  val events = IO(Output(new DBCheckerEvents))
  val debug_if = IO(Output(UInt(128.W)))

  val ring = Module(new DBCheckerRequestRing)
  val output = Module(new DBCheckerOutput)
  ring.lookup_req <> lookup_req
  ring.lookup_done <> lookup_done
  ring.refill_req <> refill_req
  ring.refill_done <> refill_done
  ring.free_window := free_window
  ring.cache_ready := cache_ready
  output.commit <> ring.commit
  error <> output.error
  m_axi_io_rx.ar <> output.ar
  m_axi_io_rx.aw <> output.aw
  s_axi_io_rx.r <> m_axi_io_rx.r
  m_axi_io_rx.w <> s_axi_io_rx.w
  s_axi_io_rx.b <> m_axi_io_rx.b

  val preferWrite = RegInit(false.B)
  val selectWrite = s_axi_io_rx.aw.valid && (!s_axi_io_rx.ar.valid || preferWrite)
  val selected = Mux(selectWrite, s_axi_io_rx.aw.bits.asUInt, s_axi_io_rx.ar.bits.asUInt)
    .asTypeOf(new AxiAddr(64, idWidth = 5))
  ring.in.valid := s_axi_io_rx.ar.valid || s_axi_io_rx.aw.valid
  ring.in.bits.axi_a := selected
  ring.in.bits.is_write := selectWrite
  ring.in.bits.bypass := !enable_mask(Cat(0.U(4.W), selected.id(4)))
  s_axi_io_rx.ar.ready := ring.in.ready && !selectWrite
  s_axi_io_rx.aw.ready := ring.in.ready && selectWrite
  when(ring.in.fire) { preferWrite := !selectWrite }

  occupancy := ring.occupancy
  idle := ring.occupancy === 0.U && output.idle
  events := 0.U.asTypeOf(events)
  events.accepted := ring.in.fire
  events.committed := ring.commit.fire
  events.lookup := lookup_req.fire
  events.hit := lookup_done.valid && lookup_done.bits.status === DBCheckerMetaStatus.data
  events.retry := (lookup_done.valid && lookup_done.bits.status === DBCheckerMetaStatus.retry) ||
    (refill_done.valid && refill_done.bits.status === DBCheckerMetaStatus.retry)
  events.input_stall := ring.in.valid && !ring.in.ready
  events.head_wait := ring.head_wait
  events.output_ar := m_axi_io_rx.ar.fire
  events.output_aw := m_axi_io_rx.aw.fire
  debug_if := Cat(ring.commit.bits.request.axi_a.addr, 0.U(48.W), ring.occupancy.pad(8),
    0.U(5.W), free_window.active, ring.commit.valid, ring.commit.ready)
}
