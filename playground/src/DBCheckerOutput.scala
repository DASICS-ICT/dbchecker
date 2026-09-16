package DBChecker

import chisel3._
import chisel3.util._
import axi._

/** A commit atomically reserves its address register and optional error entry. */
class DBCheckerOutput extends Module {
  val commit = IO(Flipped(Decoupled(new DBCheckerCommit)))
  val ar = IO(Decoupled(new AxiAddr(64, idWidth = 5)))
  val aw = IO(Decoupled(new AxiAddr(64, idWidth = 5)))
  val error = IO(Decoupled(new DBCheckerErrReq))
  val idle = IO(Output(Bool()))
  val errors = Module(new Queue(new DBCheckerErrReq, 2, pipe = true))
  error <> errors.io.deq
  val arValid = RegInit(false.B)
  val awValid = RegInit(false.B)
  val arBits = Reg(new AxiAddr(64, idWidth = 5))
  val awBits = Reg(new AxiAddr(64, idWidth = 5))
  ar.valid := arValid
  ar.bits := arBits
  aw.valid := awValid
  aw.bits := awBits
  val selectedReady = Mux(commit.bits.request.is_write, !awValid || aw.ready, !arValid || ar.ready)
  commit.ready := selectedReady && (!commit.bits.error || errors.io.enq.ready)
  errors.io.enq.valid := commit.fire && commit.bits.error
  errors.io.enq.bits := commit.bits.error_info
  when(ar.fire) { arValid := false.B }
  when(aw.fire) { awValid := false.B }
  when(commit.fire) {
    val address = WireInit(commit.bits.request.axi_a)
    address.addr := Cat(commit.bits.error, 0.U(15.W), commit.bits.request.axi_a.addr(47, 0))
    when(commit.bits.request.is_write) {
      awBits := address
      awValid := true.B
    }.otherwise {
      arBits := address
      arValid := true.B
    }
  }
  idle := !arValid && !awValid && !errors.io.deq.valid
}
