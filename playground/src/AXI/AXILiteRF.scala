package axi

import chisel3._
import chisel3.util._

object AXILiteState extends ChiselEnum {
  val Idle, readData, writeData, writeResp = Value
}

class AXILiteRF(addrWidth: Int = 32, dataWidth: Int = 32, Depth: Int = 4) extends Module {

  require(dataWidth == 32 || dataWidth == 64, "AxiLite `dataWidth` must be 32 or 64") 
  // require(Depth >= 2 && (Depth&(Depth-1)) == 0, "Depth must be a power of 2") 
  val s_axil = IO(new AxiLiteSlave(addrWidth,dataWidth))
  val reg_out = IO(Output(Vec(Depth, UInt(dataWidth.W))))

  val regFile = RegInit(VecInit(Seq.fill(Depth)(0.U(dataWidth.W))))
  reg_out := regFile

  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)
  
  // Internal signals
  val readAddrReg  = Reg(UInt(addrWidth.W))
  val writeAddrReg = Reg(UInt(addrWidth.W))
  val writeDataReg = Reg(UInt(dataWidth.W))
  val writeStrbReg = Reg(UInt((dataWidth / 8).W))
  // Default outputs
  s_axil.aw.ready     := false.B
  s_axil.w.ready     := false.B
  s_axil.b.valid     := false.B
  s_axil.b.bits.resp := 0.U  // OKAY
  s_axil.ar.ready      := false.B
  s_axil.r.valid      := false.B
  s_axil.r.bits.resp  := 0.U  // OKAY
  s_axil.r.bits.data  := 0.U

  // FSM logic
  switch(state) {
    is(AXILiteState.Idle) {
      // Prs_axilrity to write over read
      when(s_axil.aw.valid && s_axil.w.valid) {
        // Capture write address and data
        writeAddrReg := s_axil.aw.bits.addr
        writeDataReg := s_axil.w.bits.data
        writeStrbReg := s_axil.w.bits.strb
        s_axil.aw.ready := true.B
        s_axil.w.ready := true.B
        state := AXILiteState.writeData
      }.elsewhen(s_axil.ar.valid) {
        // Capture read address
        readAddrReg := s_axil.ar.bits.addr
        s_axil.ar.ready := true.B
        state := AXILiteState.readData
      }
    }
    
    is(AXILiteState.readData) {
      s_axil.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(log2Ceil(Depth) - 1, 0)
      when(index < Depth.U) {
        s_axil.r.bits.data := regFile(index)
      }.otherwise {
        s_axil.r.bits.resp := 2.U  // SLVERR for invalid address
      }
      when(s_axil.r.ready) {
        state := AXILiteState.Idle
      }
    }
    
    is(AXILiteState.writeData) {
      // Perform write operats_axiln
      val index = writeAddrReg(log2Ceil(Depth) - 1, 0)  // aligned
      when(index < Depth.U) {
        // Byte-wise write using w.bits.strb
        val wmask = Cat((0 until (dataWidth / 8)).reverse.map(i => Fill(8, writeStrbReg(i))))
        regFile(index) := (regFile(index) & ~wmask) | (writeDataReg & wmask)
      }.otherwise {
        s_axil.b.bits.resp := 2.U  // SLVERR for invalid address
      }
      state := AXILiteState.writeResp
    }
    
    is(AXILiteState.writeResp) {
      s_axil.b.valid := true.B
      when(s_axil.b.ready) {
        state := AXILiteState.Idle
      }
    }
  }
}